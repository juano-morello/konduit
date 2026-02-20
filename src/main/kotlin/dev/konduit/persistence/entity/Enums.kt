package dev.konduit.persistence.entity

/**
 * Task lifecycle states matching PRD state machine.
 * Maps to Postgres 'task_status' enum.
 * Note: The DB enum also contains 'CANCELLED' (cannot remove Postgres enum values),
 * but it is intentionally not mapped here per PRD.
 */
enum class TaskStatus {
    PENDING,
    LOCKED,
    RUNNING,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}

/**
 * Execution lifecycle states matching PRD state machine.
 * Maps to Postgres 'execution_status' enum.
 */
enum class ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}

/**
 * Worker lifecycle states.
 * Maps to Postgres 'worker_status' enum.
 */
enum class WorkerStatus {
    ACTIVE,
    DRAINING,
    STOPPED
}

/**
 * Step types in a workflow definition.
 * Maps to Postgres 'step_type' enum.
 */
enum class StepType {
    SEQUENTIAL,
    PARALLEL,
    BRANCH
}



