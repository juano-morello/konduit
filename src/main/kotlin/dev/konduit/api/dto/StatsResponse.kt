package dev.konduit.api.dto

/**
 * Enhanced stats response DTO (PRD §5.4).
 * Provides comprehensive system statistics including executions, tasks,
 * workers, dead letters, queue depth, and throughput metrics.
 */
data class StatsResponse(
    val executions: ExecutionStats,
    val tasks: TaskStats,
    val workers: WorkerStats,
    val deadLetters: Long,
    val queueDepth: Long,
    val throughput: ThroughputStats
)

data class ExecutionStats(
    val total: Long,
    val byStatus: Map<String, Long>
)

data class TaskStats(
    val total: Long,
    val byStatus: Map<String, Long>
)

data class WorkerStats(
    val total: Long,
    val active: Long,
    val totalConcurrency: Int,
    val totalActiveTasks: Int
)

data class ThroughputStats(
    val executionsPerMinute: Double,
    val tasksPerMinute: Double
)

