package dev.konduit.engine

import java.util.UUID

/**
 * Callback interface for advancing workflow executions when tasks complete or fail.
 *
 * This interface decouples the task queue layer from the execution engine,
 * preventing circular dependencies. The task queue's `completeTask`/`failTask`
 * methods call these callbacks to notify the engine about task lifecycle events.
 *
 * Implemented by [ExecutionEngine].
 */
interface ExecutionAdvancer {

    /**
     * Called when a task has been successfully completed by a worker.
     *
     * The engine should:
     * 1. Find the task and its execution
     * 2. Determine if there's a next step in the workflow
     * 3. If yes: create the next task with this task's output as input
     * 4. If no: mark the execution as COMPLETED with the last task's output
     *
     * @param taskId The ID of the completed task.
     */
    fun onTaskCompleted(taskId: UUID)

    /**
     * Called when a task has been moved to the dead letter queue
     * (all retry attempts exhausted).
     *
     * The engine should transition the execution to FAILED.
     *
     * @param taskId The ID of the dead-lettered task.
     */
    fun onTaskDeadLettered(taskId: UUID)
}

