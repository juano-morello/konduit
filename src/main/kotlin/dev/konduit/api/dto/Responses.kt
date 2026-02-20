package dev.konduit.api.dto

import dev.konduit.persistence.entity.*
import java.time.Instant
import java.util.UUID

/**
 * Execution response DTO for the /api/v1/executions endpoints.
 */
data class ExecutionResponse(
    val id: UUID,
    val workflowName: String,
    val workflowVersion: Int,
    val status: ExecutionStatus,
    val input: Map<String, Any>?,
    val output: Map<String, Any>?,
    val error: String?,
    val currentStep: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?
) {
    companion object {
        fun from(entity: ExecutionEntity) = ExecutionResponse(
            id = requireNotNull(entity.id) { "Execution entity ID must not be null" },
            workflowName = entity.workflowName,
            workflowVersion = entity.workflowVersion,
            status = entity.status,
            input = entity.input,
            output = entity.output,
            error = entity.error,
            currentStep = entity.currentStep,
            createdAt = entity.createdAt,
            startedAt = entity.startedAt,
            completedAt = entity.completedAt
        )
    }
}

/**
 * Task response DTO for the /api/v1/executions/{id}/tasks endpoint.
 */
data class TaskResponse(
    val id: UUID,
    val executionId: UUID,
    val stepName: String,
    val stepType: StepType,
    val stepOrder: Int,
    val status: TaskStatus,
    val input: Map<String, Any>?,
    val output: Map<String, Any>?,
    val attempt: Int,
    val maxAttempts: Int,
    val error: String?,
    val createdAt: Instant,
    val completedAt: Instant?
) {
    companion object {
        fun from(entity: TaskEntity) = TaskResponse(
            id = requireNotNull(entity.id) { "Task entity ID must not be null" },
            executionId = entity.executionId,
            stepName = entity.stepName,
            stepType = entity.stepType,
            stepOrder = entity.stepOrder,
            status = entity.status,
            input = entity.input,
            output = entity.output,
            attempt = entity.attempt,
            maxAttempts = entity.maxAttempts,
            error = entity.error,
            createdAt = entity.createdAt,
            completedAt = entity.completedAt
        )
    }
}

/**
 * Workflow response DTO for the /api/v1/workflows endpoints.
 */
data class WorkflowResponse(
    val name: String,
    val version: Int,
    val description: String?,
    val steps: List<StepSummary>,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

data class StepSummary(
    val name: String,
    val retryPolicy: Map<String, Any?>?,
    val timeoutMs: Long?
)

/**
 * Dead letter response DTO for the /api/v1/dead-letters endpoints.
 */
data class DeadLetterResponse(
    val id: UUID,
    val executionId: UUID,
    val taskId: UUID,
    val workflowName: String,
    val stepName: String,
    val errorHistory: List<Map<String, Any>>,
    val reprocessed: Boolean,
    val createdAt: Instant
) {
    companion object {
        fun from(entity: DeadLetterEntity) = DeadLetterResponse(
            id = requireNotNull(entity.id) { "Dead letter entity ID must not be null" },
            executionId = entity.executionId,
            taskId = entity.taskId,
            workflowName = entity.workflowName,
            stepName = entity.stepName,
            errorHistory = entity.errorHistory,
            reprocessed = entity.reprocessed,
            createdAt = entity.createdAt
        )
    }
}

/**
 * Worker response DTO for the /api/v1/workers endpoint.
 */
data class WorkerResponse(
    val id: UUID,
    val workerId: String,
    val status: WorkerStatus,
    val hostname: String?,
    val concurrency: Int,
    val activeTasks: Int,
    val lastHeartbeat: Instant,
    val startedAt: Instant,
    val stoppedAt: Instant?
) {
    companion object {
        fun from(entity: WorkerEntity) = WorkerResponse(
            id = requireNotNull(entity.id) { "Worker entity ID must not be null" },
            workerId = entity.workerId,
            status = entity.status,
            hostname = entity.hostname,
            concurrency = entity.concurrency,
            activeTasks = entity.activeTasks,
            lastHeartbeat = entity.lastHeartbeat,
            startedAt = entity.startedAt,
            stoppedAt = entity.stoppedAt
        )
    }
}

/**
 * Paginated response wrapper.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

