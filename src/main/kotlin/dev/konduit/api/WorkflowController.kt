package dev.konduit.api

import dev.konduit.api.dto.StepSummary
import dev.konduit.api.dto.WorkflowResponse
import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.persistence.repository.WorkflowRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for workflow definitions (/api/v1/workflows).
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Workflow definition management")
class WorkflowController(
    private val workflowRegistry: WorkflowRegistry,
    private val workflowRepository: WorkflowRepository
) {

    @Operation(summary = "List registered workflows")
    @ApiResponse(responseCode = "200", description = "List of all registered workflow definitions")
    @GetMapping
    fun listWorkflows(): ResponseEntity<List<WorkflowResponse>> {
        val definitions = workflowRegistry.getAll()
        val responses = definitions.map { toResponse(it) }
        return ResponseEntity.ok(responses)
    }

    @Operation(summary = "Get workflow by name")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Workflow definition found"),
        ApiResponse(responseCode = "404", description = "Workflow not found")
    ])
    @GetMapping("/{name}")
    fun getWorkflow(@PathVariable name: String): ResponseEntity<WorkflowResponse> {
        val definition = workflowRegistry.findByName(name)
            ?: throw NoSuchElementException("Workflow '$name' not found")
        return ResponseEntity.ok(toResponse(definition))
    }

    private fun toResponse(definition: WorkflowDefinition): WorkflowResponse {
        // Look up the persisted entity for timestamps
        val entity = workflowRepository.findByNameAndVersion(definition.name, definition.version)

        return WorkflowResponse(
            name = definition.name,
            version = definition.version,
            description = definition.description,
            steps = definition.steps.map { step ->
                StepSummary(
                    name = step.name,
                    retryPolicy = step.retryPolicy.toMap(),
                    timeoutMs = step.timeout?.toMillis()
                )
            },
            createdAt = entity?.createdAt,
            updatedAt = entity?.updatedAt
        )
    }
}

