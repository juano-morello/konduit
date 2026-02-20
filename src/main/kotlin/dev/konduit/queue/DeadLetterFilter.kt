package dev.konduit.queue

import java.util.UUID

/**
 * Filter criteria for batch reprocessing of dead-lettered tasks.
 *
 * All fields are optional — null means "no filter on this field".
 * Multiple non-null fields are combined with AND logic.
 *
 * @property workflowName Filter by workflow name.
 * @property executionId Filter by execution ID.
 * @property stepName Filter by step name.
 */
data class DeadLetterFilter(
    val workflowName: String? = null,
    val executionId: UUID? = null,
    val stepName: String? = null
)

