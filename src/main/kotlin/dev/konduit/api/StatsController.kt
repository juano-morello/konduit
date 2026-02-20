package dev.konduit.api

import dev.konduit.api.dto.StatsResponse
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for system statistics (PRD §5.4).
 */
@RestController
@RequestMapping("/api/v1/stats")
class StatsController(
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository
) {

    /**
     * Get counts of executions and tasks grouped by status.
     */
    @GetMapping
    fun getStats(): ResponseEntity<StatsResponse> {
        val executionCounts = ExecutionStatus.entries.associate { status ->
            status.name to executionRepository.countByStatus(status)
        }

        val taskCounts = TaskStatus.entries.associate { status ->
            status.name to taskRepository.countByStatus(status)
        }

        return ResponseEntity.ok(
            StatsResponse(
                executions = executionCounts,
                tasks = taskCounts
            )
        )
    }
}

