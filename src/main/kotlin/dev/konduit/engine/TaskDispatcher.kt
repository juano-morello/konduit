package dev.konduit.engine

import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.persistence.entity.BackoffStrategy
import dev.konduit.persistence.entity.StepType
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Determines which tasks to create next based on the workflow step graph.
 *
 * For Phase 1 (sequential only): when step N completes, creates a task for step N+1
 * with step N's output as input. Parallel fan-out/fan-in and conditional branching
 * are Phase 2 concerns.
 */
@Component
class TaskDispatcher(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(TaskDispatcher::class.java)

    /**
     * Create the first task for a new execution.
     *
     * @param executionId The execution this task belongs to.
     * @param workflowDefinition The workflow definition to get the first step from.
     * @param input The execution input to pass to the first step.
     * @return The created task entity.
     */
    fun createFirstTask(
        executionId: UUID,
        workflowDefinition: WorkflowDefinition,
        input: Map<String, Any>?
    ): TaskEntity {
        val firstStep = workflowDefinition.steps.first()

        val task = TaskEntity(
            executionId = executionId,
            stepName = firstStep.name,
            stepType = StepType.SEQUENTIAL,
            stepOrder = 0,
            status = TaskStatus.PENDING,
            input = input,
            maxAttempts = firstStep.retryPolicy.maxAttempts,
            backoffStrategy = mapBackoffStrategy(firstStep.retryPolicy.backoffStrategy),
            backoffBaseMs = firstStep.retryPolicy.baseDelayMs
        )

        val saved = taskRepository.save(task)
        log.info(
            "Created first task {} for execution {}, step '{}'",
            saved.id, executionId, firstStep.name
        )
        return saved
    }

    /**
     * Dispatch the next sequential task after a completed step.
     *
     * Finds the next step in the workflow definition by order and creates
     * a new task for it with the completed step's output as input.
     *
     * @param executionId The execution ID.
     * @param completedStepName The name of the step that just completed.
     * @param completedOutput The output from the completed step.
     * @param workflowDefinition The workflow definition.
     * @return The new task entity, or null if the workflow is complete (no more steps).
     */
    fun dispatchNext(
        executionId: UUID,
        completedStepName: String,
        completedOutput: Map<String, Any>?,
        workflowDefinition: WorkflowDefinition
    ): TaskEntity? {
        val steps = workflowDefinition.steps
        val completedIndex = steps.indexOfFirst { it.name == completedStepName }

        if (completedIndex == -1) {
            throw IllegalArgumentException(
                "Step '$completedStepName' not found in workflow '${workflowDefinition.name}'"
            )
        }

        val nextIndex = completedIndex + 1
        if (nextIndex >= steps.size) {
            log.info(
                "Execution {} completed: step '{}' was the last step",
                executionId, completedStepName
            )
            return null
        }

        val nextStep = steps[nextIndex]
        val task = TaskEntity(
            executionId = executionId,
            stepName = nextStep.name,
            stepType = StepType.SEQUENTIAL,
            stepOrder = nextIndex,
            status = TaskStatus.PENDING,
            input = completedOutput,
            maxAttempts = nextStep.retryPolicy.maxAttempts,
            backoffStrategy = mapBackoffStrategy(nextStep.retryPolicy.backoffStrategy),
            backoffBaseMs = nextStep.retryPolicy.baseDelayMs
        )

        val saved = taskRepository.save(task)
        log.info(
            "Dispatched next task {} for execution {}, step '{}' (order {})",
            saved.id, executionId, nextStep.name, nextIndex
        )
        return saved
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

