package dev.konduit.api.dto

import java.time.Instant
import java.util.UUID

/**
 * Timeline response DTO showing execution progress with step-level detail.
 */
data class TimelineResponse(
    val executionId: UUID,
    val workflowName: String,
    val status: String,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val durationMs: Long?,
    val steps: List<TimelineStepEntry>
)

/**
 * Individual step entry in the execution timeline.
 */
data class TimelineStepEntry(
    val stepName: String,
    val stepType: String,
    val status: String,
    val attempt: Int,
    val maxAttempts: Int,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val durationMs: Long?,
    val error: String?,
    val parallelGroup: String?,
    val branchKey: String?,
    val input: Map<String, Any>?,
    val output: Map<String, Any>?
)

