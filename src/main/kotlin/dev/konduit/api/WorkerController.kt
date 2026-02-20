package dev.konduit.api

import dev.konduit.api.dto.WorkerResponse
import dev.konduit.persistence.repository.WorkerRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for worker management (/api/v1/workers).
 */
@RestController
@RequestMapping("/api/v1/workers")
class WorkerController(
    private val workerRepository: WorkerRepository
) {

    /**
     * List all registered workers.
     */
    @GetMapping
    fun listWorkers(): ResponseEntity<List<WorkerResponse>> {
        val workers = workerRepository.findAll()
        return ResponseEntity.ok(workers.map { WorkerResponse.from(it) })
    }
}

