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
     */
    @GetMapping
    fun getStats(): ResponseEntity<StatsResponse> {
        // Execution stats
        val executionByStatus = ExecutionStatus.entries.associate { status ->
            status.name to executionRepository.countByStatus(status)
        }
        val executionTotal = executionByStatus.values.sum()

        // Task stats
        val taskByStatus = TaskStatus.entries.associate { status ->
            status.name to taskRepository.countByStatus(status)
        }
        val taskTotal = taskByStatus.values.sum()

        // Worker stats
        val activeWorkers = workerRepository.findByStatus(WorkerStatus.ACTIVE)
        val workerTotal = workerRepository.count()
        val workerActive = activeWorkers.size.toLong()
        val totalConcurrency = activeWorkers.sumOf { it.concurrency }
        val totalActiveTasks = activeWorkers.sumOf { it.activeTasks }

        // Dead letters
        val deadLetterCount = deadLetterRepository.count()

        // Queue depth: PENDING tasks
        val queueDepth = taskRepository.countByStatus(TaskStatus.PENDING)

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

