package dev.konduit.api

import com.ninjasquad.springmockk.MockkBean
import dev.konduit.dsl.StepDefinition
import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.persistence.entity.WorkflowEntity
import dev.konduit.persistence.repository.WorkflowRepository
import dev.konduit.retry.RetryPolicy
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

@WebMvcTest(WorkflowController::class)
class WorkflowControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true)
    private lateinit var workflowRegistry: WorkflowRegistry

    @MockkBean
    private lateinit var workflowRepository: WorkflowRepository

    private fun sampleDefinition(name: String = "test-workflow", version: Int = 1): WorkflowDefinition {
        return WorkflowDefinition(
            name = name,
            version = version,
            description = "A test workflow",
            elements = listOf(
                StepDefinition(
                    name = "step-1",
                    handler = { null },
                    retryPolicy = RetryPolicy(maxAttempts = 3),
                    timeout = Duration.ofSeconds(30)
                ),
                StepDefinition(
                    name = "step-2",
                    handler = { null },
                    retryPolicy = RetryPolicy(maxAttempts = 1)
                )
            )
        )
    }

    @Test
    fun `GET workflows returns 200 with list of workflows`() {
        val definition = sampleDefinition()
        val entity = WorkflowEntity(
            id = UUID.randomUUID(),
            name = "test-workflow",
            version = 1,
            description = "A test workflow",
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2025-01-02T00:00:00Z")
        )

        every { workflowRegistry.getAll() } returns listOf(definition)
        every { workflowRepository.findByNameAndVersion("test-workflow", 1) } returns entity

        mockMvc.perform(get("/api/v1/workflows"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("test-workflow"))
            .andExpect(jsonPath("$[0].version").value(1))
            .andExpect(jsonPath("$[0].description").value("A test workflow"))
            .andExpect(jsonPath("$[0].steps[0].name").value("step-1"))
            .andExpect(jsonPath("$[0].steps[0].timeoutMs").value(30000))
            .andExpect(jsonPath("$[0].steps[1].name").value("step-2"))
            .andExpect(jsonPath("$[0].steps[1].timeoutMs").doesNotExist())
    }

    @Test
    fun `GET workflows returns empty list when no workflows registered`() {
        every { workflowRegistry.getAll() } returns emptyList()

        mockMvc.perform(get("/api/v1/workflows"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET workflow by name returns 200 when found`() {
        val definition = sampleDefinition()
        val entity = WorkflowEntity(
            id = UUID.randomUUID(),
            name = "test-workflow",
            version = 1,
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2025-01-02T00:00:00Z")
        )

        every { workflowRegistry.findByName("test-workflow") } returns definition
        every { workflowRepository.findByNameAndVersion("test-workflow", 1) } returns entity

        mockMvc.perform(get("/api/v1/workflows/test-workflow"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("test-workflow"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.steps").isArray)
            .andExpect(jsonPath("$.steps.length()").value(2))
    }

    @Test
    fun `GET workflow by name returns 404 when not found`() {
        every { workflowRegistry.findByName("nonexistent") } returns null

        mockMvc.perform(get("/api/v1/workflows/nonexistent"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").value("Workflow 'nonexistent' not found"))
    }
}

