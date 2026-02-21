package dev.konduit.api.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Enhanced stats response DTO for the GET /api/v1/stats endpoint.
 * Provides comprehensive system statistics including executions, tasks,
 * workers, dead letters, queue depth, and throughput metrics.
 */
@Schema(description = "Comprehensive system statistics")
data class StatsResponse(
    @Schema(description = "Execution statistics by status")
    val executions: ExecutionStats,
    @Schema(description = "Task statistics by status")
    val tasks: TaskStats,
    @Schema(description = "Worker statistics")
    val workers: WorkerStats,
    @Schema(description = "Total number of dead letters")
    val deadLetters: Long,
    @Schema(description = "Number of tasks currently pending in the queue")
    val queueDepth: Long,
    @Schema(description = "Throughput metrics for the last hour")
    val throughput: ThroughputStats
)

@Schema(description = "Execution count statistics")
data class ExecutionStats(
    @Schema(description = "Total number of executions")
    val total: Long,
    @Schema(description = "Execution counts grouped by status")
    val byStatus: Map<String, Long>
)

@Schema(description = "Task count statistics")
data class TaskStats(
    @Schema(description = "Total number of tasks")
    val total: Long,
    @Schema(description = "Task counts grouped by status")
    val byStatus: Map<String, Long>
)

@Schema(description = "Worker statistics")
data class WorkerStats(
    @Schema(description = "Total number of registered workers")
    val total: Long,
    @Schema(description = "Number of active workers")
    val active: Long,
    @Schema(description = "Total concurrency across all active workers")
    val totalConcurrency: Int,
    @Schema(description = "Total tasks currently being processed")
    val totalActiveTasks: Int
)

@Schema(description = "Throughput metrics for the last hour")
data class ThroughputStats(
    @Schema(description = "Average executions completed per minute in the last hour")
    val executionsPerMinute: Double,
    @Schema(description = "Average tasks completed per minute in the last hour")
    val tasksPerMinute: Double
)

