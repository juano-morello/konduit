package dev.konduit.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Request to trigger a new workflow execution via POST /api/v1/executions.
 */
@Schema(description = "Request to trigger a new workflow execution")
data class TriggerExecutionRequest(
    @field:NotBlank(message = "workflowName must not be blank")
    @field:Size(max = 255, message = "workflowName must be at most 255 characters")
    @Schema(description = "Name of the registered workflow to execute", example = "npo-onboarding")
    val workflowName: String,

    @Schema(description = "Input data passed to the first step of the workflow", nullable = true)
    val input: Map<String, Any>? = null,

    @field:Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    @Schema(description = "Optional idempotency key to prevent duplicate executions", nullable = true)
    val idempotencyKey: String? = null
)

/**
 * Request to batch-reprocess dead letters via POST /api/v1/dead-letters/reprocess-batch.
 */
@Schema(description = "Filter criteria for batch reprocessing dead letters")
data class BatchReprocessRequest(
    @field:Size(max = 255, message = "workflowName must be at most 255 characters")
    @Schema(description = "Filter by workflow name", nullable = true)
    val workflowName: String? = null,

    @Schema(description = "Filter by execution ID", nullable = true)
    val executionId: UUID? = null,

    @field:Size(max = 255, message = "stepName must be at most 255 characters")
    @Schema(description = "Filter by step name", nullable = true)
    val stepName: String? = null
)

