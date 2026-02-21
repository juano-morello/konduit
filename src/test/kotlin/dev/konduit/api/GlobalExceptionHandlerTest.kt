package dev.konduit.api

import com.ninjasquad.springmockk.MockkBean
import dev.konduit.engine.ExecutionEngine
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import io.mockk.every
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

/**
 * Tests for [GlobalExceptionHandler] via [ExecutionController].
 *
 * Each test triggers a specific exception type through the controller layer
 * and verifies the handler produces the correct HTTP status and ErrorResponse body.
 */
@WebMvcTest(ExecutionController::class)
class GlobalExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var executionEngine: ExecutionEngine

    @MockkBean
    private lateinit var executionRepository: ExecutionRepository

    @MockkBean
    private lateinit var taskRepository: TaskRepository

    // ── MethodArgumentNotValidException → 400 ──────────────────────────

    @Test
    fun `MethodArgumentNotValidException returns 400 with field errors`() {
        // POST with blank workflowName triggers @NotBlank validation
        mockMvc.perform(
            post("/api/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"workflowName": ""}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.path").value("/api/v1/executions"))
    }

    // ── ConstraintViolationException → 400 ─────────────────────────────

    @Test
    fun `ConstraintViolationException returns 400`() {
        val violation = io.mockk.mockk<ConstraintViolation<*>>()
        val path = io.mockk.mockk<Path>()
        every { path.toString() } returns "workflowName"
        every { violation.propertyPath } returns path
        every { violation.message } returns "must not be blank"

        every { executionEngine.triggerExecution(any(), any(), any()) } throws
            ConstraintViolationException("Validation failed", setOf(violation))

        mockMvc.perform(
            post("/api/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"workflowName": "test-wf"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
    }

    // ── NoSuchElementException → 404 ───────────────────────────────────

    @Test
    fun `NoSuchElementException returns 404`() {
        val id = UUID.randomUUID()
        every { executionRepository.findById(id) } returns java.util.Optional.empty()

        mockMvc.perform(get("/api/v1/executions/$id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").value("Execution not found: $id"))
    }

    // ── IllegalStateException → 409 ────────────────────────────────────

    @Test
    fun `IllegalStateException returns 409`() {
        val id = UUID.randomUUID()
        every { executionEngine.cancelExecution(id) } throws
            IllegalStateException("Execution is already completed")

        mockMvc.perform(post("/api/v1/executions/$id/cancel"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("Conflict"))
            .andExpect(jsonPath("$.message").value("Execution is already completed"))
    }

    // ── HttpMessageNotReadableException → 400 ──────────────────────────

    @Test
    fun `HttpMessageNotReadableException returns 400 for malformed JSON`() {
        mockMvc.perform(
            post("/api/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid json")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value("Invalid request body"))
    }

    // ── Generic Exception → 500 ────────────────────────────────────────

    @Test
    fun `generic Exception returns 500`() {
        val id = UUID.randomUUID()
        every { executionEngine.cancelExecution(id) } throws
            RuntimeException("Unexpected database error")

        mockMvc.perform(post("/api/v1/executions/$id/cancel"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
    }
}

