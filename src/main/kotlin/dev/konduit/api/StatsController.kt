package dev.konduit.api

import dev.konduit.api.dto.*
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * REST controller for system statistics (GET /api/v1/stats).
 */
@RestController
@RequestMapping("/api/v1/stats")
class StatsController(
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository,
    private val workerRepository: WorkerRepository,
    private val deadLetterRepository: DeadLetterRepository
) {

    /**
     * Get comprehensive system statistics including executions, tasks,
     * workers, dead letters, queue depth, and throughput.
     *
     * Optimized to use aggregate GROUP BY queries — total of 7 DB queries
     * instead of ~18 individual countByStatus() calls.
     */
    @GetMapping
    fun getStats(): ResponseEntity<StatsResponse> {
        // Execution stats: single GROUP BY query (replaces 6 individual countByStatus calls)
        val executionByStatus = executionRepository.countGroupByStatus()
            .associate { row -> (row[0] as ExecutionStatus).name to (row[1] as Long) }
        val executionTotal = executionByStatus.values.sum()

        // Task stats: single GROUP BY query (replaces 6 individual countByStatus calls)
        val taskByStatus = taskRepository.countGroupByStatus()
            .associate { row -> (row[0] as TaskStatus).name to (row[1] as Long) }
        val taskTotal = taskByStatus.values.sum()

        // Queue depth from the already-fetched task stats
        val queueDepth = taskByStatus[TaskStatus.PENDING.name] ?: 0L

        // Worker stats: single aggregate query for active workers (replaces findByStatus + count)
        val workerTotal = workerRepository.count()
        val activeStats = workerRepository.getAggregateStatsByStatus(WorkerStatus.ACTIVE)
        val workerActive = (activeStats[0] as Long)
        val totalConcurrency = (activeStats[1] as Long).toInt()
        val totalActiveTasks = (activeStats[2] as Long).toInt()

        // Dead letters
        val deadLetterCount = deadLetterRepository.count()

        // Throughput: executions and tasks completed in the last hour
        val oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS)
        val executionsCompletedLastHour = executionRepository.countByStatusAndCompletedAtAfter(
            ExecutionStatus.COMPLETED, oneHourAgo
        )
        val tasksCompletedLastHour = taskRepository.countByStatusAndCompletedAtAfter(
            TaskStatus.COMPLETED, oneHourAgo
        )

        return ResponseEntity.ok(
            StatsResponse(
                executions = ExecutionStats(
                    total = executionTotal,
                    byStatus = executionByStatus
                ),
                tasks = TaskStats(
                    total = taskTotal,
                    byStatus = taskByStatus
                ),
                workers = WorkerStats(
                    total = workerTotal,
                    active = workerActive,
                    totalConcurrency = totalConcurrency,
                    totalActiveTasks = totalActiveTasks
                ),
                deadLetters = deadLetterCount,
                queueDepth = queueDepth,
                throughput = ThroughputStats(
                    executionsPerMinute = executionsCompletedLastHour / 60.0,
                    tasksPerMinute = tasksCompletedLastHour / 60.0
                )
            )
        )
    }
}

