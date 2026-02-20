package dev.konduit.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Request to trigger a new workflow execution via POST /api/v1/executions.
 */
data class TriggerExecutionRequest(
    @field:NotBlank(message = "workflowName must not be blank")
    @field:Size(max = 255, message = "workflowName must be at most 255 characters")
    val workflowName: String,

    val input: Map<String, Any>? = null,

    @field:Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    val idempotencyKey: String? = null
)

/**
 * Request to batch-reprocess dead letters via POST /api/v1/dead-letters/reprocess-batch.
 */
data class BatchReprocessRequest(
    @field:Size(max = 255, message = "workflowName must be at most 255 characters")
    val workflowName: String? = null,

    val executionId: UUID? = null,

    @field:Size(max = 255, message = "stepName must be at most 255 characters")
    val stepName: String? = null
)

