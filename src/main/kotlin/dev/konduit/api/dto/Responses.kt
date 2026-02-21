package dev.konduit.api.dto

import dev.konduit.persistence.entity.*
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Execution response DTO for the /api/v1/executions endpoints.
 */
@Schema(description = "Workflow execution details")
data class ExecutionResponse(
    @Schema(description = "Unique execution identifier")
    val id: UUID,
    @Schema(description = "Name of the workflow being executed")
    val workflowName: String,
    @Schema(description = "Version of the workflow definition used")
    val workflowVersion: Int,
    @Schema(description = "Current execution status")
    val status: ExecutionStatus,
    @Schema(description = "Input data provided when the execution was triggered", nullable = true)
    val input: Map<String, Any>?,
    @Schema(description = "Output data produced by the workflow", nullable = true)
    val output: Map<String, Any>?,
    @Schema(description = "Error message if the execution failed", nullable = true)
    val error: String?,
    @Schema(description = "Name of the currently executing step", nullable = true)
    val currentStep: String?,
    @Schema(description = "Timestamp when the execution was created")
    val createdAt: Instant,
    @Schema(description = "Timestamp when the execution started running", nullable = true)
    val startedAt: Instant?,
    @Schema(description = "Timestamp when the execution completed", nullable = true)
    val completedAt: Instant?,
    @Schema(description = "Webhook callback URL for terminal state notifications", nullable = true)
    val callbackUrl: String? = null,
    @Schema(description = "Webhook delivery status: NONE, PENDING, DELIVERED, or FAILED", nullable = true)
    val callbackStatus: String? = null
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
            completedAt = entity.completedAt,
            callbackUrl = entity.callbackUrl,
            callbackStatus = entity.callbackStatus
        )
    }
}

/**
 * Task response DTO for the /api/v1/executions/{id}/tasks endpoint.
 */
@Schema(description = "Task execution details within a workflow")
data class TaskResponse(
    @Schema(description = "Unique task identifier")
    val id: UUID,
    @Schema(description = "ID of the parent execution")
    val executionId: UUID,
    @Schema(description = "Name of the workflow step")
    val stepName: String,
    @Schema(description = "Type of step (SEQUENTIAL, PARALLEL, BRANCH)")
    val stepType: StepType,
    @Schema(description = "Order of the step within the workflow")
    val stepOrder: Int,
    @Schema(description = "Current task status")
    val status: TaskStatus,
    @Schema(description = "Input data for this task", nullable = true)
    val input: Map<String, Any>?,
    @Schema(description = "Output data produced by this task", nullable = true)
    val output: Map<String, Any>?,
    @Schema(description = "Current attempt number")
    val attempt: Int,
    @Schema(description = "Maximum retry attempts allowed")
    val maxAttempts: Int,
    @Schema(description = "Error message if the task failed", nullable = true)
    val error: String?,
    @Schema(description = "Task priority (0-100). Higher values are processed first.")
    val priority: Int,
    @Schema(description = "Timestamp when the task was created")
    val createdAt: Instant,
    @Schema(description = "Timestamp when the task completed", nullable = true)
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
            priority = entity.priority,
            createdAt = entity.createdAt,
            completedAt = entity.completedAt
        )
    }
}

/**
 * Workflow response DTO for the /api/v1/workflows endpoints.
 */
@Schema(description = "Workflow definition details")
data class WorkflowResponse(
    @Schema(description = "Workflow name")
    val name: String,
    @Schema(description = "Workflow version number")
    val version: Int,
    @Schema(description = "Workflow description", nullable = true)
    val description: String?,
    @Schema(description = "List of steps in the workflow")
    val steps: List<StepSummary>,
    @Schema(description = "Timestamp when the workflow was first registered", nullable = true)
    val createdAt: Instant?,
    @Schema(description = "Timestamp when the workflow was last updated", nullable = true)
    val updatedAt: Instant?
)

@Schema(description = "Summary of a workflow step")
data class StepSummary(
    @Schema(description = "Step name")
    val name: String,
    @Schema(description = "Retry policy configuration", nullable = true)
    val retryPolicy: Map<String, Any?>?,
    @Schema(description = "Step timeout in milliseconds", nullable = true)
    val timeoutMs: Long?
)

/**
 * Dead letter response DTO for the /api/v1/dead-letters endpoints.
 */
@Schema(description = "Dead letter entry for a failed task that exhausted retries")
data class DeadLetterResponse(
    @Schema(description = "Unique dead letter identifier")
    val id: UUID,
    @Schema(description = "ID of the parent execution")
    val executionId: UUID,
    @Schema(description = "ID of the original failed task")
    val taskId: UUID,
    @Schema(description = "Name of the workflow")
    val workflowName: String,
    @Schema(description = "Name of the failed step")
    val stepName: String,
    @Schema(description = "History of errors across all retry attempts")
    val errorHistory: List<Map<String, Any>>,
    @Schema(description = "Whether this dead letter has been reprocessed")
    val reprocessed: Boolean,
    @Schema(description = "Timestamp when the dead letter was created")
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
@Schema(description = "Worker instance details")
data class WorkerResponse(
    @Schema(description = "Unique worker record identifier")
    val id: UUID,
    @Schema(description = "Logical worker identifier")
    val workerId: String,
    @Schema(description = "Current worker status")
    val status: WorkerStatus,
    @Schema(description = "Hostname of the worker machine", nullable = true)
    val hostname: String?,
    @Schema(description = "Maximum concurrent tasks this worker can handle")
    val concurrency: Int,
    @Schema(description = "Number of tasks currently being processed")
    val activeTasks: Int,
    @Schema(description = "Timestamp of the last heartbeat")
    val lastHeartbeat: Instant,
    @Schema(description = "Timestamp when the worker started")
    val startedAt: Instant,
    @Schema(description = "Timestamp when the worker stopped", nullable = true)
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
@Schema(description = "Paginated response wrapper")
data class PageResponse<T>(
    @Schema(description = "List of items in the current page")
    val content: List<T>,
    @Schema(description = "Current page number (zero-based)")
    val page: Int,
    @Schema(description = "Number of items per page")
    val size: Int,
    @Schema(description = "Total number of items across all pages")
    val totalElements: Long,
    @Schema(description = "Total number of pages")
    val totalPages: Int
)

