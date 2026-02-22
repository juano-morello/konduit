package dev.konduit.api

import dev.konduit.api.dto.BatchReprocessRequest
import dev.konduit.api.dto.DeadLetterResponse
import dev.konduit.api.dto.PageResponse
import dev.konduit.api.dto.TaskResponse
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.queue.DeadLetterFilter
import dev.konduit.queue.DeadLetterQueue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST controller for dead letter management (/api/v1/dead-letters).
 */
@Validated
@RestController
@RequestMapping("/api/v1/dead-letters")
@Tag(name = "Dead Letters", description = "Dead letter queue management")
class DeadLetterController(
    private val deadLetterRepository: DeadLetterRepository,
    private val deadLetterQueue: DeadLetterQueue
) {

    @Operation(summary = "List dead letters (paginated, filterable)")
    @ApiResponse(responseCode = "200", description = "Paginated list of dead letters")
    @GetMapping
    fun listDeadLetters(
        @RequestParam(required = false) workflowName: String?,
        @RequestParam(required = false) executionId: UUID?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
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

    @Operation(summary = "Get dead letter by ID")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Dead letter found"),
        ApiResponse(responseCode = "404", description = "Dead letter not found")
    ])
    @GetMapping("/{id}")
    fun getDeadLetter(@PathVariable id: UUID): ResponseEntity<DeadLetterResponse> {
        val deadLetter = deadLetterRepository.findById(id)
            .orElseThrow { NoSuchElementException("Dead letter not found: $id") }
        return ResponseEntity.ok(DeadLetterResponse.from(deadLetter))
    }

    @Operation(summary = "Reprocess a dead letter")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Dead letter reprocessed, new task created"),
        ApiResponse(responseCode = "404", description = "Dead letter not found"),
        ApiResponse(responseCode = "409", description = "Dead letter already reprocessed")
    ])
    @PostMapping("/{id}/reprocess")
    fun reprocess(@PathVariable id: UUID): ResponseEntity<TaskResponse> {
        val newTask = deadLetterQueue.reprocess(id)
        return ResponseEntity.ok(TaskResponse.from(newTask))
    }

    @Operation(summary = "Batch reprocess dead letters")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Matching dead letters reprocessed"),
        ApiResponse(responseCode = "400", description = "Invalid filter criteria")
    ])
    @PostMapping("/reprocess-batch")
    fun reprocessBatch(
        @Valid @RequestBody request: BatchReprocessRequest
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

