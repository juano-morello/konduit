package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.queue.TaskQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Atomic task completion service that wraps task completion and workflow advancement
 * in a single database transaction.
 *
 * **Problem:** Previously, `TaskQueue.completeTask()` and `ExecutionEngine.onTaskCompleted()`
 * ran in separate `@Transactional` boundaries. A crash between the two calls could leave
 * a task marked COMPLETED but the workflow not advanced — a stuck execution.
 *
 * **Solution:** This service provides a single `@Transactional` method that:
 * 1. Marks the task as COMPLETED (with status guard)
 * 2. Advances the workflow (dispatches next task or completes execution)
 *
 * Both operations commit or roll back together, ensuring atomicity.
 */
@Service
class TaskCompletionService(
    private val taskQueue: TaskQueue,
    private val executionAdvancer: ExecutionAdvancer
) {
    private val log = LoggerFactory.getLogger(TaskCompletionService::class.java)

    /**
     * Atomically complete a task and advance the workflow.
     *
     * This method runs in a single transaction boundary. If either the task
     * completion or the workflow advancement fails, both are rolled back.
     *
     * @param task The task entity to complete (must be in RUNNING or LOCKED status).
     * @param execution The execution entity this task belongs to.
     * @param output The task output to store (as a JSON-compatible map).
     * @throws IllegalStateException if the task is not in RUNNING or LOCKED status.
     */
    @Transactional
    fun completeAndAdvance(
        task: TaskEntity,
        execution: ExecutionEntity,
        output: Map<String, Any>?
    ) {
        log.debug(
            "Atomic complete+advance: taskId={}, stepName={}, executionId={}",
            task.id, task.stepName, execution.id
        )

        // Step 1: Mark task as COMPLETED (includes status guard)
        taskQueue.completeTask(task, output)

        // Step 2: Advance the workflow (includes task status re-check)
        executionAdvancer.onTaskCompleted(task, execution)
    }
}

