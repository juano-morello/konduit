package dev.konduit.engine

import dev.konduit.dsl.ParallelBlock
import dev.konduit.dsl.StepDefinition
import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.WorkflowElement
import dev.konduit.persistence.entity.BackoffStrategy
import dev.konduit.persistence.entity.StepType
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Determines which tasks to create next based on the workflow element graph.
 *
 * Supports:
 * - Sequential steps: when step N completes, creates a task for step N+1
 * - Parallel blocks (fan-out): creates all tasks in the block simultaneously
 * - Fan-in: checks if all parallel tasks are in terminal states before advancing
 */
@Component
class TaskDispatcher(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(TaskDispatcher::class.java)

    /**
     * Create the first task(s) for a new execution.
     *
     * If the first element is a sequential step, creates one task.
     * If the first element is a parallel block, creates all tasks in the block (fan-out).
     *
     * @param executionId The execution this task belongs to.
     * @param workflowDefinition The workflow definition to get the first element from.
     * @param input The execution input to pass to the first step(s).
     * @return The first created task entity (for sequential) or the first parallel task.
     */
    fun createFirstTask(
        executionId: UUID,
        workflowDefinition: WorkflowDefinition,
        input: Map<String, Any>?
    ): TaskEntity {
        val firstElement = workflowDefinition.elements.first()
        return when (firstElement) {
            is StepDefinition -> {
                createSequentialTask(executionId, firstElement, 0, input)
            }
            is ParallelBlock -> {
                val tasks = createParallelTasks(executionId, firstElement, 0, input)
                tasks.first()
            }
        }
    }

    /**
     * Dispatch the next element after a completed step.
     *
     * For sequential steps: finds the next element and creates task(s).
     * For parallel steps: this should only be called after fan-in is confirmed.
     *
     * @param executionId The execution ID.
     * @param completedStepName The name of the step that just completed.
     * @param completedOutput The output from the completed step (or aggregated parallel outputs).
     * @param workflowDefinition The workflow definition.
     * @param parallelOutputs Aggregated outputs from a completed parallel block (if applicable).
     * @return The new task entity, or null if the workflow is complete (no more elements).
     */
    fun dispatchNext(
        executionId: UUID,
        completedStepName: String,
        completedOutput: Map<String, Any>?,
        workflowDefinition: WorkflowDefinition,
        parallelOutputs: Map<String, Any?>? = null
    ): TaskEntity? {
        val elements = workflowDefinition.elements

        // Find which element the completed step belongs to
        val completedElementIndex = findElementIndex(elements, completedStepName)

        if (completedElementIndex == -1) {
            throw IllegalArgumentException(
                "Step '$completedStepName' not found in workflow '${workflowDefinition.name}'"
            )
        }

        val nextIndex = completedElementIndex + 1
        if (nextIndex >= elements.size) {
            log.info(
                "Execution {} completed: step '{}' was in the last element",
                executionId, completedStepName
            )
            return null
        }

        val nextElement = elements[nextIndex]
        // Determine the input for the next element
        val nextInput = if (parallelOutputs != null) {
            // After a parallel block, pass the aggregated outputs as input
            @Suppress("UNCHECKED_CAST")
            parallelOutputs as? Map<String, Any> ?: completedOutput
        } else {
            completedOutput
        }

        return when (nextElement) {
            is StepDefinition -> {
                createSequentialTask(executionId, nextElement, nextIndex, nextInput)
            }
            is ParallelBlock -> {
                val tasks = createParallelTasks(executionId, nextElement, nextIndex, nextInput)
                tasks.first()
            }
        }
    }

    /**
     * Check if all tasks in a parallel group have reached terminal states.
     *
     * @return true if all tasks in the group are COMPLETED or DEAD_LETTER.
     */
    fun isParallelGroupComplete(executionId: UUID, parallelGroup: String): Boolean {
        val tasks = taskRepository.findByExecutionIdAndParallelGroup(executionId, parallelGroup)
        if (tasks.isEmpty()) return false
        return tasks.all { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.DEAD_LETTER }
    }

    /**
     * Collect outputs from all completed tasks in a parallel group.
     * Dead-lettered tasks are omitted (partial results per ADR-004).
     *
     * @return Map of step name → output for successfully completed parallel tasks.
     */
    fun collectParallelOutputs(executionId: UUID, parallelGroup: String): Map<String, Any?> {
        val tasks = taskRepository.findByExecutionIdAndParallelGroup(executionId, parallelGroup)
        return tasks
            .filter { it.status == TaskStatus.COMPLETED }
            .associate { it.stepName to it.output as Any? }
    }

    /**
     * Check if any task in a parallel group has dead-lettered.
     */
    fun hasParallelFailures(executionId: UUID, parallelGroup: String): Boolean {
        val count = taskRepository.countByExecutionIdAndParallelGroupAndStatus(
            executionId, parallelGroup, TaskStatus.DEAD_LETTER
        )
        return count > 0
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun createSequentialTask(
        executionId: UUID,
        step: StepDefinition,
        elementIndex: Int,
        input: Map<String, Any>?
    ): TaskEntity {
        val task = TaskEntity(
            executionId = executionId,
            stepName = step.name,
            stepType = StepType.SEQUENTIAL,
            stepOrder = elementIndex,
            status = TaskStatus.PENDING,
            input = input,
            maxAttempts = step.retryPolicy.maxAttempts,
            backoffStrategy = mapBackoffStrategy(step.retryPolicy.backoffStrategy),
            backoffBaseMs = step.retryPolicy.baseDelayMs
        )

        val saved = taskRepository.save(task)
        log.info(
            "Created sequential task {} for execution {}, step '{}' (element {})",
            saved.id, executionId, step.name, elementIndex
        )
        return saved
    }

    /**
     * Fan-out: create all tasks in a parallel block simultaneously.
     * All tasks share the same parallelGroup and stepOrder (element index).
     */
    private fun createParallelTasks(
        executionId: UUID,
        block: ParallelBlock,
        elementIndex: Int,
        input: Map<String, Any>?
    ): List<TaskEntity> {
        val tasks = block.steps.map { step ->
            TaskEntity(
                executionId = executionId,
                stepName = step.name,
                stepType = StepType.PARALLEL,
                stepOrder = elementIndex,
                status = TaskStatus.PENDING,
                input = input,
                parallelGroup = block.name,
                maxAttempts = step.retryPolicy.maxAttempts,
                backoffStrategy = mapBackoffStrategy(step.retryPolicy.backoffStrategy),
                backoffBaseMs = step.retryPolicy.baseDelayMs
            )
        }

        val saved = taskRepository.saveAll(tasks)
        log.info(
            "Fan-out: created {} parallel tasks for execution {}, block '{}' (element {}): [{}]",
            saved.size, executionId, block.name, elementIndex,
            saved.joinToString(", ") { "${it.stepName}(${it.id})" }
        )
        return saved
    }

    /**
     * Find the element index that contains the given step name.
     */
    private fun findElementIndex(elements: List<WorkflowElement>, stepName: String): Int {
        return elements.indexOfFirst { element ->
            when (element) {
                is StepDefinition -> element.name == stepName
                is ParallelBlock -> element.steps.any { it.name == stepName }
            }
        }
    }

    /**
     * Map from the retry module's BackoffStrategy to the entity's BackoffStrategy.
     */
    private fun mapBackoffStrategy(
        strategy: dev.konduit.retry.BackoffStrategy
    ): BackoffStrategy {
        return when (strategy) {
            dev.konduit.retry.BackoffStrategy.FIXED -> BackoffStrategy.FIXED
            dev.konduit.retry.BackoffStrategy.LINEAR -> BackoffStrategy.LINEAR
            dev.konduit.retry.BackoffStrategy.EXPONENTIAL -> BackoffStrategy.EXPONENTIAL
        }
    }
}

