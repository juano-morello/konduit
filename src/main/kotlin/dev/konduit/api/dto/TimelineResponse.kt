package dev.konduit.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Timeline response DTO showing execution progress with step-level detail.
 */
@Schema(description = "Execution timeline with step-level detail")
data class TimelineResponse(
    @Schema(description = "Execution identifier")
    val executionId: UUID,
    @Schema(description = "Name of the workflow")
    val workflowName: String,
    @Schema(description = "Current execution status")
    val status: String,
    @Schema(description = "Timestamp when the execution started", nullable = true)
    val startedAt: Instant?,
    @Schema(description = "Timestamp when the execution completed", nullable = true)
    val completedAt: Instant?,
    @Schema(description = "Total execution duration in milliseconds", nullable = true)
    val durationMs: Long?,
    @Schema(description = "Ordered list of step entries in the timeline")
    val steps: List<TimelineStepEntry>
)

/**
 * Individual step entry in the execution timeline.
 */
@Schema(description = "Individual step entry in the execution timeline")
data class TimelineStepEntry(
    @Schema(description = "Name of the step")
    val stepName: String,
    @Schema(description = "Type of step (SEQUENTIAL, PARALLEL, BRANCH)")
    val stepType: String,
    @Schema(description = "Current step status")
    val status: String,
    @Schema(description = "Current attempt number")
    val attempt: Int,
    @Schema(description = "Maximum retry attempts allowed")
    val maxAttempts: Int,
    @Schema(description = "Timestamp when the step started", nullable = true)
    val startedAt: Instant?,
    @Schema(description = "Timestamp when the step completed", nullable = true)
    val completedAt: Instant?,
    @Schema(description = "Step duration in milliseconds", nullable = true)
    val durationMs: Long?,
    @Schema(description = "Error message if the step failed", nullable = true)
    val error: String?,
    @Schema(description = "Parallel group identifier for parallel steps", nullable = true)
    val parallelGroup: String?,
    @Schema(description = "Branch key for branching steps", nullable = true)
    val branchKey: String?,
    @Schema(description = "Input data for this step", nullable = true)
    val input: Map<String, Any>?,
    @Schema(description = "Output data produced by this step", nullable = true)
    val output: Map<String, Any>?
)

