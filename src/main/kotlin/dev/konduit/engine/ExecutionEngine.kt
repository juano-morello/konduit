package dev.konduit.engine

import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.observability.MetricsService
import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.StepType
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Core orchestrator for workflow executions.
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

    @Autowired(required = false)
    private var metricsService: MetricsService? = null

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
            workflowId = requireNotNull(workflowEntity.id) { "Workflow entity ID must not be null" },
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
            executionId = requireNotNull(savedExecution.id) { "Saved execution ID must not be null" },
            workflowDefinition = workflowDef,
            input = input
        )

        // Transition to RUNNING
        stateMachine.transition(savedExecution, ExecutionStatus.RUNNING)
        savedExecution.startedAt = Instant.now()
        executionRepository.save(savedExecution)

        // Record metrics
        metricsService?.recordExecutionStarted(workflowDef.name)

        log.info("Execution {} is now RUNNING", savedExecution.id)
        return savedExecution
    }

    /**
     * Called when a task has been successfully completed by a worker.
     *
     * Advances the workflow:
     * - Sequential tasks: dispatches the next element immediately.
     * - Parallel tasks: checks fan-in condition (all tasks in group terminal).
     *   Only advances when all parallel tasks are done.
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

        // Handle parallel tasks: check fan-in before advancing
        if (task.stepType == StepType.PARALLEL && task.parallelGroup != null) {
            if (!taskDispatcher.isParallelGroupComplete(
                    requireNotNull(execution.id) { "Execution ID must not be null" },
                    requireNotNull(task.parallelGroup) { "Parallel group must not be null for parallel task" }
                )) {
                log.info(
                    "Parallel task {} (step '{}', group '{}') completed, but group not yet complete. Waiting for fan-in.",
                    taskId, task.stepName, task.parallelGroup
                )
                return
            }

            // Fan-in complete — collect outputs and advance
            log.info(
                "Fan-in complete for execution {}, parallel group '{}'",
                execution.id, task.parallelGroup
            )
            val executionId = requireNotNull(execution.id) { "Execution ID must not be null" }
            val parallelGroup = requireNotNull(task.parallelGroup) { "Parallel group must not be null for parallel task" }
            val parallelOutputs = taskDispatcher.collectParallelOutputs(executionId, parallelGroup)

            val nextTask = taskDispatcher.dispatchNext(
                executionId = executionId,
                completedStepName = task.stepName,
                completedOutput = task.output,
                workflowDefinition = workflowDef,
                parallelOutputs = parallelOutputs
            )

            if (nextTask != null) {
                execution.currentStep = nextTask.stepName
                executionRepository.save(execution)
                log.info("Execution {} advanced past parallel block to step '{}'", execution.id, nextTask.stepName)
            } else {
                // Parallel block was the last element — use aggregated outputs
                @Suppress("UNCHECKED_CAST")
                execution.output = parallelOutputs as? Map<String, Any>
                execution.currentStep = null
                stateMachine.transition(execution, ExecutionStatus.COMPLETED)
                executionRepository.save(execution)
                recordExecutionCompletion(execution)
                log.info("Execution {} completed successfully after parallel block", execution.id)
            }
            return
        }

        // Handle branch tasks: check if there are more steps in the same branch
        if (task.stepType == StepType.BRANCH && task.branchKey != null) {
            val branchBlock = taskDispatcher.findBranchBlock(workflowDef, task.stepName)
            if (branchBlock != null) {
                val branchKey = requireNotNull(task.branchKey) { "Branch key must not be null for branch task" }
                val nextBranchStep = taskDispatcher.findNextBranchStep(
                    branchBlock, branchKey, task.stepName
                )

                if (nextBranchStep != null) {
                    // More steps in this branch — dispatch the next one
                    val nextTask = taskDispatcher.dispatchNextBranchStep(
                        executionId = requireNotNull(execution.id) { "Execution ID must not be null" },
                        nextStep = nextBranchStep,
                        elementIndex = task.stepOrder,
                        input = task.output,
                        branchBlockName = requireNotNull(task.parallelGroup) { "Parallel group (branch block name) must not be null for branch task" },
                        branchKey = branchKey
                    )
                    execution.currentStep = nextTask.stepName
                    executionRepository.save(execution)
                    log.info(
                        "Execution {} advanced to next branch step '{}' (branch '{}', key '{}')",
                        execution.id, nextTask.stepName, branchBlock.name, task.branchKey
                    )
                    return
                }

                // Last step in branch — advance to next workflow element
                log.info(
                    "Branch '{}' (key '{}') completed for execution {}",
                    branchBlock.name, task.branchKey, execution.id
                )
                val nextTask = taskDispatcher.dispatchNext(
                    executionId = requireNotNull(execution.id) { "Execution ID must not be null" },
                    completedStepName = task.stepName,
                    completedOutput = task.output,
                    workflowDefinition = workflowDef
                )

                if (nextTask != null) {
                    execution.currentStep = nextTask.stepName
                    executionRepository.save(execution)
                    log.info(
                        "Execution {} advanced past branch block to step '{}'",
                        execution.id, nextTask.stepName
                    )
                } else {
                    execution.output = task.output
                    execution.currentStep = null
                    stateMachine.transition(execution, ExecutionStatus.COMPLETED)
                    executionRepository.save(execution)
                    recordExecutionCompletion(execution)
                    log.info("Execution {} completed successfully after branch block", execution.id)
                }
                return
            }
        }

        // Sequential task: dispatch next element
        val nextTask = taskDispatcher.dispatchNext(
            executionId = requireNotNull(execution.id) { "Execution ID must not be null" },
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
            recordExecutionCompletion(execution)
            log.info("Execution {} completed successfully", execution.id)
        }
    }

    /**
     * Called when a task has been moved to the dead letter queue
     * (all retry attempts exhausted).
     *
     * For sequential tasks: transitions the execution to FAILED immediately.
     * For parallel tasks (ADR-004): does NOT cancel siblings. Checks if all
     * parallel tasks are in terminal states. If so, advances with partial results.
     * The post-parallel step receives only successful outputs.
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

        // Parallel task failure isolation (ADR-004): don't fail execution immediately
        if (task.stepType == StepType.PARALLEL && task.parallelGroup != null) {
            log.warn(
                "Parallel task {} (step '{}', group '{}') dead-lettered. Siblings continue.",
                taskId, task.stepName, task.parallelGroup
            )

            val executionId = requireNotNull(execution.id) { "Execution ID must not be null" }
            val parallelGroup = requireNotNull(task.parallelGroup) { "Parallel group must not be null for parallel task" }

            // Check if all parallel tasks are now in terminal states
            if (!taskDispatcher.isParallelGroupComplete(executionId, parallelGroup)) {
                log.info(
                    "Parallel group '{}' not yet complete after dead letter. Waiting for remaining tasks.",
                    task.parallelGroup
                )
                return
            }

            // Fan-in complete (with partial failures) — advance with partial results
            log.info(
                "Fan-in complete for execution {} (with failures), parallel group '{}'",
                execution.id, task.parallelGroup
            )

            val workflowDef = workflowRegistry.findByNameAndVersion(
                execution.workflowName, execution.workflowVersion
            ) ?: throw IllegalStateException(
                "Workflow '${execution.workflowName}' v${execution.workflowVersion} " +
                    "not found in registry for execution ${execution.id}"
            )

            val parallelOutputs = taskDispatcher.collectParallelOutputs(executionId, parallelGroup)

            val nextTask = taskDispatcher.dispatchNext(
                executionId = executionId,
                completedStepName = task.stepName,
                completedOutput = null,
                workflowDefinition = workflowDef,
                parallelOutputs = parallelOutputs
            )

            if (nextTask != null) {
                execution.currentStep = nextTask.stepName
                executionRepository.save(execution)
                log.info(
                    "Execution {} advanced past parallel block (with failures) to step '{}'",
                    execution.id, nextTask.stepName
                )
            } else {
                // Parallel block was the last element
                @Suppress("UNCHECKED_CAST")
                execution.output = parallelOutputs as? Map<String, Any>
                execution.currentStep = null
                stateMachine.transition(execution, ExecutionStatus.COMPLETED)
                executionRepository.save(execution)
                recordExecutionCompletion(execution)
                log.info(
                    "Execution {} completed (with partial parallel failures) after parallel block",
                    execution.id
                )
            }
            return
        }

        // Branch task: fail the execution immediately (no fan-out within a branch)
        if (task.stepType == StepType.BRANCH) {
            execution.error = "Branch task '${task.stepName}' (branch '${task.parallelGroup}', " +
                "key '${task.branchKey}') dead-lettered after ${task.maxAttempts} attempts: ${task.error}"
            execution.currentStep = task.stepName
            stateMachine.transition(execution, ExecutionStatus.FAILED)
            executionRepository.save(execution)
            recordExecutionFailure(execution)

            log.warn(
                "Execution {} FAILED: branch task {} (step '{}', branch '{}', key '{}') dead-lettered",
                execution.id, taskId, task.stepName, task.parallelGroup, task.branchKey
            )
            return
        }

        // Sequential task: fail the execution immediately
        execution.error = "Task '${task.stepName}' dead-lettered after ${task.maxAttempts} attempts: ${task.error}"
        execution.currentStep = task.stepName
        stateMachine.transition(execution, ExecutionStatus.FAILED)
        executionRepository.save(execution)
        recordExecutionFailure(execution)

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

        metricsService?.recordExecutionCancelled(execution.workflowName)

        log.info("Execution {} cancelled", executionId)
        return execution
    }

    private fun recordExecutionCompletion(execution: ExecutionEntity) {
        val duration = if (execution.startedAt != null) {
            Duration.between(execution.startedAt, Instant.now())
        } else null
        metricsService?.recordExecutionCompleted(execution.workflowName, duration ?: Duration.ZERO)
    }

    private fun recordExecutionFailure(execution: ExecutionEntity) {
        val duration = if (execution.startedAt != null) {
            Duration.between(execution.startedAt, Instant.now())
        } else null
        metricsService?.recordExecutionFailed(execution.workflowName, duration)
    }
}

