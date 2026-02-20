package dev.konduit.engine

import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Core orchestrator for workflow executions (PRD §3.2.1).
 *
 * Responsibilities:
 * - Trigger new executions (with idempotency key support)
 * - Advance workflows when tasks complete (sequential step chaining)
 * - Handle task failures (dead letter → execution FAILED)
 * - Cancel executions (prevent new task dispatch)
 *
 * Implements [ExecutionAdvancer] so the task queue layer can call back
 * without a circular dependency on this class.
 */
@Component
class ExecutionEngine(
    private val workflowRegistry: WorkflowRegistry,
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository,
    private val workflowRepository: WorkflowRepository,
    private val stateMachine: ExecutionStateMachine,
    private val taskDispatcher: TaskDispatcher
) : ExecutionAdvancer {

    private val log = LoggerFactory.getLogger(ExecutionEngine::class.java)

    /**
     * Trigger a new workflow execution.
     *
     * 1. If an idempotency key is provided and an execution exists with that key,
     *    return the existing execution (idempotent).
     * 2. Look up the workflow definition from the registry.
     * 3. Create an ExecutionEntity in PENDING status.
     * 4. Create the first step's TaskEntity in PENDING status.
     * 5. Transition the execution to RUNNING.
     *
     * @param workflowName The name of the workflow to execute.
     * @param input The input data for the workflow.
     * @param idempotencyKey Optional key for idempotent execution creation.
     * @return The created (or existing) execution entity.
     */
    @Transactional
    fun triggerExecution(
        workflowName: String,
        input: Map<String, Any>?,
        idempotencyKey: String? = null
    ): ExecutionEntity {
        // Idempotency check
        if (idempotencyKey != null) {
            val existing = executionRepository.findByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                log.info(
                    "Idempotent hit: execution {} already exists for key '{}'",
                    existing.id, idempotencyKey
                )
                return existing
            }
        }

        // Resolve workflow definition
        val workflowDef = workflowRegistry.getByName(workflowName)

        // Find the persisted workflow entity for the FK reference
        val workflowEntity = workflowRepository.findByNameAndVersion(
            workflowDef.name, workflowDef.version
        ) ?: throw IllegalStateException(
            "Workflow '${workflowDef.name}' v${workflowDef.version} not found in database. " +
                "Was the registry initialized?"
        )

        // Create execution in PENDING
        val execution = ExecutionEntity(
            workflowId = workflowEntity.id!!,
            workflowName = workflowDef.name,
            workflowVersion = workflowDef.version,
            status = ExecutionStatus.PENDING,
            input = input,
            idempotencyKey = idempotencyKey,
            currentStep = workflowDef.steps.first().name
        )
        val savedExecution = executionRepository.save(execution)

        log.info(
            "Created execution {} for workflow '{}' v{}",
            savedExecution.id, workflowDef.name, workflowDef.version
        )

        // Create the first task
        taskDispatcher.createFirstTask(
            executionId = savedExecution.id!!,
            workflowDefinition = workflowDef,
            input = input
        )

        // Transition to RUNNING
        stateMachine.transition(savedExecution, ExecutionStatus.RUNNING)
        executionRepository.save(savedExecution)

        log.info("Execution {} is now RUNNING", savedExecution.id)
        return savedExecution
    }

    /**
     * Called when a task has been successfully completed by a worker.
     *
     * Advances the workflow:
     * - If there's a next step: creates the next task with this task's output as input.
     * - If this was the last step: marks the execution as COMPLETED with the final output.
     * - If the execution is CANCELLED: does nothing (prevents new task dispatch).
     */
    @Transactional
    override fun onTaskCompleted(taskId: UUID) {
        val task = taskRepository.findById(taskId).orElseThrow {
            IllegalArgumentException("Task $taskId not found")
        }

        val execution = executionRepository.findById(task.executionId).orElseThrow {
            IllegalStateException("Execution ${task.executionId} not found for task $taskId")
        }

        // If execution is cancelled or in a terminal state, don't advance
        if (stateMachine.isTerminal(execution.status)) {
            log.info(
                "Execution {} is in terminal state {}, skipping advancement for task {}",
                execution.id, execution.status, taskId
            )
            return
        }

        // Resolve workflow definition
        val workflowDef = workflowRegistry.findByNameAndVersion(
            execution.workflowName, execution.workflowVersion
        ) ?: throw IllegalStateException(
            "Workflow '${execution.workflowName}' v${execution.workflowVersion} " +
                "not found in registry for execution ${execution.id}"
        )

        // Try to dispatch the next task
        val nextTask = taskDispatcher.dispatchNext(
            executionId = execution.id!!,
            completedStepName = task.stepName,
            completedOutput = task.output,
            workflowDefinition = workflowDef
        )

        if (nextTask != null) {
            // Update current step tracking
            execution.currentStep = nextTask.stepName
            executionRepository.save(execution)
            log.info(
                "Execution {} advanced to step '{}'",
                execution.id, nextTask.stepName
            )
        } else {
            // Workflow complete — set output and transition to COMPLETED
            execution.output = task.output
            execution.currentStep = null
            stateMachine.transition(execution, ExecutionStatus.COMPLETED)
            executionRepository.save(execution)
            log.info("Execution {} completed successfully", execution.id)
        }
    }

    /**
     * Called when a task has been moved to the dead letter queue
     * (all retry attempts exhausted).
     *
     * Transitions the execution to FAILED.
     */
    @Transactional
    override fun onTaskDeadLettered(taskId: UUID) {
        val task = taskRepository.findById(taskId).orElseThrow {
            IllegalArgumentException("Task $taskId not found")
        }

        val execution = executionRepository.findById(task.executionId).orElseThrow {
            IllegalStateException("Execution ${task.executionId} not found for task $taskId")
        }

        // If already in a terminal state, skip
        if (stateMachine.isTerminal(execution.status)) {
            log.info(
                "Execution {} already in terminal state {}, skipping dead letter for task {}",
                execution.id, execution.status, taskId
            )
            return
        }

        execution.error = "Task '${task.stepName}' dead-lettered after ${task.maxAttempts} attempts: ${task.error}"
        execution.currentStep = task.stepName
        stateMachine.transition(execution, ExecutionStatus.FAILED)
        executionRepository.save(execution)

        log.warn(
            "Execution {} FAILED: task {} (step '{}') dead-lettered",
            execution.id, taskId, task.stepName
        )
    }

    /**
     * Cancel a running execution.
     *
     * Sets the execution status to CANCELLED. Does not cancel in-progress tasks —
     * they will complete but no new tasks will be dispatched (checked in [onTaskCompleted]).
     *
     * @param executionId The ID of the execution to cancel.
     * @return The updated execution entity.
     * @throws IllegalArgumentException if the execution is not found.
     * @throws IllegalStateException if the execution cannot be cancelled (already terminal).
     */
    @Transactional
    fun cancelExecution(executionId: UUID): ExecutionEntity {
        val execution = executionRepository.findById(executionId).orElseThrow {
            IllegalArgumentException("Execution $executionId not found")
        }

        stateMachine.transition(execution, ExecutionStatus.CANCELLED)
        executionRepository.save(execution)

        log.info("Execution {} cancelled", executionId)
        return execution
    }
}

