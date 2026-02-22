package dev.konduit.api

import dev.konduit.api.dto.ExecutionResponse
import dev.konduit.api.dto.PageResponse
import dev.konduit.api.dto.TaskResponse
import dev.konduit.api.dto.TimelineResponse
import dev.konduit.api.dto.TimelineStepEntry
import dev.konduit.api.dto.TriggerExecutionRequest
import dev.konduit.engine.ExecutionEngine
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.validation.annotation.Validated
import java.util.UUID

/**
 * REST controller for workflow executions (/api/v1/executions).
 */
@Validated
@RestController
@RequestMapping("/api/v1/executions")
@Tag(name = "Executions", description = "Workflow execution management")
class ExecutionController(
    private val executionEngine: ExecutionEngine,
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository
) {

    @Operation(summary = "Trigger a new workflow execution")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Execution created successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request body"),
        ApiResponse(responseCode = "404", description = "Workflow not found")
    ])
    @PostMapping
    fun triggerExecution(
        @Valid @RequestBody request: TriggerExecutionRequest
    ): ResponseEntity<ExecutionResponse> {
        val execution = executionEngine.triggerExecution(
            workflowName = request.workflowName,
            input = request.input,
            idempotencyKey = request.idempotencyKey,
            callbackUrl = request.callbackUrl,
            priority = request.priority
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ExecutionResponse.from(execution))
    }

    @Operation(summary = "Get execution by ID")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Execution found"),
        ApiResponse(responseCode = "404", description = "Execution not found")
    ])
    @GetMapping("/{id}")
    fun getExecution(@PathVariable id: UUID): ResponseEntity<ExecutionResponse> {
        val execution = executionRepository.findById(id)
            .orElseThrow { NoSuchElementException("Execution not found: $id") }
        return ResponseEntity.ok(ExecutionResponse.from(execution))
    }

    @Operation(summary = "List executions (paginated, filterable)")
    @ApiResponse(responseCode = "200", description = "Paginated list of executions")
    @GetMapping
    fun listExecutions(
        @RequestParam(required = false) status: ExecutionStatus?,
        @RequestParam(required = false) workflowName: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<PageResponse<ExecutionResponse>> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))

        val resultPage = when {
            status != null && workflowName != null -> {
                executionRepository.findByStatusAndWorkflowName(status, workflowName, pageable)
            }
            status != null -> executionRepository.findByStatus(status, pageable)
            workflowName != null -> executionRepository.findByWorkflowName(workflowName, pageable)
            else -> executionRepository.findAll(pageable)
        }

        val response = PageResponse(
            content = resultPage.content.map { ExecutionResponse.from(it) },
            page = resultPage.number,
            size = resultPage.size,
            totalElements = resultPage.totalElements,
            totalPages = resultPage.totalPages
        )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "Get tasks for an execution")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of tasks for the execution"),
        ApiResponse(responseCode = "404", description = "Execution not found")
    ])
    @GetMapping("/{id}/tasks")
    fun getExecutionTasks(@PathVariable id: UUID): ResponseEntity<List<TaskResponse>> {
        // Verify execution exists
        if (!executionRepository.existsById(id)) {
            throw NoSuchElementException("Execution not found: $id")
        }
        val tasks = taskRepository.findByExecutionIdOrderByStepOrderAsc(id)
        return ResponseEntity.ok(tasks.map { TaskResponse.from(it) })
    }

    @Operation(summary = "Get execution timeline")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Execution timeline with step-level detail"),
        ApiResponse(responseCode = "404", description = "Execution not found")
    ])
    @GetMapping("/{id}/timeline")
    fun getExecutionTimeline(@PathVariable id: UUID): ResponseEntity<TimelineResponse> {
        val execution = executionRepository.findById(id)
            .orElseThrow { NoSuchElementException("Execution not found: $id") }

        val tasks = taskRepository.findByExecutionIdOrderByStepOrderAsc(id)

        val steps = tasks.map { task ->
            val stepDuration = if (task.startedAt != null && task.completedAt != null) {
                java.time.Duration.between(task.startedAt, task.completedAt).toMillis()
            } else null

            TimelineStepEntry(
                stepName = task.stepName,
                stepType = task.stepType.name,
                status = task.status.name,
                attempt = task.attempt,
                maxAttempts = task.maxAttempts,
                startedAt = task.startedAt,
                completedAt = task.completedAt,
                durationMs = stepDuration,
                error = task.error,
                parallelGroup = task.parallelGroup,
                branchKey = task.branchKey,
                input = task.input,
                output = task.output
            )
        }

        val executionDuration = if (execution.startedAt != null && execution.completedAt != null) {
            java.time.Duration.between(execution.startedAt, execution.completedAt).toMillis()
        } else null

        val timeline = TimelineResponse(
            executionId = requireNotNull(execution.id) { "Execution ID must not be null" },
            workflowName = execution.workflowName,
            status = execution.status.name,
            startedAt = execution.startedAt,
            completedAt = execution.completedAt,
            durationMs = executionDuration,
            steps = steps
        )

        return ResponseEntity.ok(timeline)
    }

    @Operation(summary = "Cancel a running execution")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Execution cancelled successfully"),
        ApiResponse(responseCode = "404", description = "Execution not found"),
        ApiResponse(responseCode = "409", description = "Execution is not in a cancellable state")
    ])
    @PostMapping("/{id}/cancel")
    fun cancelExecution(@PathVariable id: UUID): ResponseEntity<ExecutionResponse> {
        val execution = executionEngine.cancelExecution(id)
        return ResponseEntity.ok(ExecutionResponse.from(execution))
    }
}

