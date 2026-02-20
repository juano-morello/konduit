package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.TaskEntity

/**
 * Callback interface for advancing workflow executions when tasks complete or fail.
 *
 * This interface decouples the task queue layer from the execution engine,
 * preventing circular dependencies. The task queue's `completeTask`/`failTask`
 * methods call these callbacks to notify the engine about task lifecycle events.
 *
 * Entities are passed directly to avoid redundant database lookups — the caller
 * already has the task and execution loaded.
 *
 * Implemented by [ExecutionEngine].
 */
interface ExecutionAdvancer {

    /**
     * Called when a task has been successfully completed by a worker.
     *
     * The engine should:
     * 1. Determine if there's a next step in the workflow
     * 2. If yes: create the next task with this task's output as input
     * 3. If no: mark the execution as COMPLETED with the last task's output
     *
     * @param task The completed task entity (already persisted with output).
     * @param execution The execution entity this task belongs to.
     */
    fun onTaskCompleted(task: TaskEntity, execution: ExecutionEntity)

    /**
     * Called when a task has been moved to the dead letter queue
     * (all retry attempts exhausted).
     *
     * The engine should transition the execution to FAILED.
     *
     * @param task The dead-lettered task entity.
     * @param execution The execution entity this task belongs to.
     */
    fun onTaskDeadLettered(task: TaskEntity, execution: ExecutionEntity)
}

