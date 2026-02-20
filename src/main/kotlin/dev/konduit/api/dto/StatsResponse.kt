package dev.konduit.api.dto

/**
 * Stats response DTO (PRD §5.4).
 * Counts of executions and tasks grouped by status.
 */
data class StatsResponse(
    val executions: Map<String, Long>,
    val tasks: Map<String, Long>
)

