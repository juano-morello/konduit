package dev.konduit.api

import dev.konduit.api.dto.WorkerResponse
import dev.konduit.persistence.repository.WorkerRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for worker management (/api/v1/workers).
 */
@RestController
@RequestMapping("/api/v1/workers")
@Tag(name = "Workers", description = "Worker instance management")
class WorkerController(
    private val workerRepository: WorkerRepository
) {

    @Operation(summary = "List registered workers")
    @ApiResponse(responseCode = "200", description = "List of all registered worker instances")
    @GetMapping
    fun listWorkers(): ResponseEntity<List<WorkerResponse>> {
        val workers = workerRepository.findAll()
        return ResponseEntity.ok(workers.map { WorkerResponse.from(it) })
    }
}

