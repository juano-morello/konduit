package dev.konduit.api

import dev.konduit.api.dto.BatchReprocessRequest
import dev.konduit.api.dto.DeadLetterResponse
import dev.konduit.api.dto.PageResponse
import dev.konduit.api.dto.TaskResponse
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.queue.DeadLetterFilter
import dev.konduit.queue.DeadLetterQueue
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST controller for dead letter management (PRD §5.3).
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
class DeadLetterController(
    private val deadLetterRepository: DeadLetterRepository,
    private val deadLetterQueue: DeadLetterQueue
) {

    /**
     * List dead letters with optional filters and pagination.
     */
    @GetMapping
    fun listDeadLetters(
        @RequestParam(required = false) workflowName: String?,
        @RequestParam(required = false) executionId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<DeadLetterResponse>> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))

        val resultPage = when {
            workflowName != null -> deadLetterRepository.findByWorkflowName(workflowName, pageable)
            executionId != null -> deadLetterRepository.findByExecutionId(executionId, pageable)
            else -> deadLetterRepository.findAll(pageable)
        }

        val response = PageResponse(
            content = resultPage.content.map { DeadLetterResponse.from(it) },
            page = resultPage.number,
            size = resultPage.size,
            totalElements = resultPage.totalElements,
            totalPages = resultPage.totalPages
        )
        return ResponseEntity.ok(response)
    }

    /**
     * Get a dead letter by ID.
     */
    @GetMapping("/{id}")
    fun getDeadLetter(@PathVariable id: UUID): ResponseEntity<DeadLetterResponse> {
        val deadLetter = deadLetterRepository.findById(id)
            .orElseThrow { NoSuchElementException("Dead letter not found: $id") }
        return ResponseEntity.ok(DeadLetterResponse.from(deadLetter))
    }

    /**
     * Reprocess a single dead letter.
     */
    @PostMapping("/{id}/reprocess")
    fun reprocess(@PathVariable id: UUID): ResponseEntity<TaskResponse> {
        val newTask = deadLetterQueue.reprocess(id)
        return ResponseEntity.ok(TaskResponse.from(newTask))
    }

    /**
     * Batch reprocess dead letters matching filter criteria.
     */
    @PostMapping("/reprocess-batch")
    fun reprocessBatch(
        @RequestBody request: BatchReprocessRequest
    ): ResponseEntity<List<TaskResponse>> {
        val filter = DeadLetterFilter(
            workflowName = request.workflowName,
            executionId = request.executionId,
            stepName = request.stepName
        )
        val newTasks = deadLetterQueue.reprocessBatch(filter)
        return ResponseEntity.ok(newTasks.map { TaskResponse.from(it) })
    }
}

