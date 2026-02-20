package dev.konduit.api.dto

import java.util.UUID

/**
 * Request to trigger a new workflow execution (PRD §5.2).
 */
data class TriggerExecutionRequest(
    val workflowName: String,
    val input: Map<String, Any>? = null,
    val idempotencyKey: String? = null
)

/**
 * Request to batch-reprocess dead letters (PRD §5.3).
 */
data class BatchReprocessRequest(
    val workflowName: String? = null,
    val executionId: UUID? = null,
    val stepName: String? = null
)

