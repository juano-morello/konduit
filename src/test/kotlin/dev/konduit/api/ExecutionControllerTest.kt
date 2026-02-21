package dev.konduit.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import dev.konduit.api.dto.TriggerExecutionRequest
import dev.konduit.engine.ExecutionEngine
import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.StepType
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.retry.BackoffStrategy
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.*

@WebMvcTest(ExecutionController::class)
class ExecutionControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var executionEngine: ExecutionEngine

    @MockkBean
    lateinit var executionRepository: ExecutionRepository

    @MockkBean
    lateinit var taskRepository: TaskRepository

    private val executionId = UUID.randomUUID()
    private val workflowId = UUID.randomUUID()
    private val now = Instant.now()

    private fun sampleExecution(
        id: UUID = executionId,
        status: ExecutionStatus = ExecutionStatus.RUNNING,
        workflowName: String = "test-workflow"
    ) = ExecutionEntity(
        id = id,
        workflowId = workflowId,
        workflowName = workflowName,
        workflowVersion = 1,
        status = status,
        input = mapOf("key" to "value"),
        currentStep = "step-1",
        createdAt = now,
        startedAt = now
    )

    private fun sampleTask(
        id: UUID = UUID.randomUUID(),
        execId: UUID = executionId,
        stepName: String = "step-1",
        stepOrder: Int = 0
    ) = TaskEntity(
        id = id,
        executionId = execId,
        stepName = stepName,
        stepType = StepType.SEQUENTIAL,
        stepOrder = stepOrder,
        status = TaskStatus.COMPLETED,
        input = mapOf("in" to "data"),
        output = mapOf("out" to "result"),
        attempt = 1,
        maxAttempts = 3,
        createdAt = now,
        startedAt = now,
        completedAt = now,
        backoffStrategy = BackoffStrategy.FIXED,
        backoffBaseMs = 1000
    )

    // ── POST /api/v1/executions — trigger ───────────────────────────────

    @Test
    fun `POST trigger returns 201 with execution response`() {
        val exec = sampleExecution()
        every { executionEngine.triggerExecution("test-workflow", mapOf("key" to "value"), null) } returns exec

        mockMvc.post("/api/v1/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                TriggerExecutionRequest(workflowName = "test-workflow", input = mapOf("key" to "value"))
            )
        }.andExpect {
            status { isCreated() }
            jsonPath("$.workflowName") { value("test-workflow") }
            jsonPath("$.status") { value("RUNNING") }
            jsonPath("$.id") { isNotEmpty() }
        }
    }

    @Test
    fun `POST trigger with blank workflowName returns 400`() {
        mockMvc.post("/api/v1/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"workflowName": "", "input": {}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
        }
    }

    @Test
    fun `POST trigger with missing body returns 400`() {
        mockMvc.post("/api/v1/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST trigger returns 404 when workflow not found`() {
        every { executionEngine.triggerExecution("missing-wf", any(), any()) } throws
            NoSuchElementException("Workflow not found: missing-wf")

        mockMvc.post("/api/v1/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                TriggerExecutionRequest(workflowName = "missing-wf")
            )
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
        }
    }

    // ── GET /api/v1/executions/{id} — get by ID ────────────────────────

    @Test
    fun `GET execution by ID returns 200`() {
        val exec = sampleExecution()
        every { executionRepository.findById(executionId) } returns Optional.of(exec)

        mockMvc.get("/api/v1/executions/$executionId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(executionId.toString()) }
                jsonPath("$.workflowName") { value("test-workflow") }
                jsonPath("$.status") { value("RUNNING") }
            }
    }

    @Test
    fun `GET execution by ID returns 404 when not found`() {
        val missingId = UUID.randomUUID()
        every { executionRepository.findById(missingId) } returns Optional.empty()

        mockMvc.get("/api/v1/executions/$missingId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
            }
    }

    // ── GET /api/v1/executions — list ───────────────────────────────────

    @Test
    fun `GET list executions returns 200 with paginated response`() {
        val exec = sampleExecution()
        val page = PageImpl(listOf(exec))
        every { executionRepository.findAll(any<Pageable>()) } returns page

        mockMvc.get("/api/v1/executions")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].workflowName") { value("test-workflow") }
                jsonPath("$.page") { value(0) }
                jsonPath("$.totalElements") { value(1) }
            }
    }

    @Test
    fun `GET list executions with status filter`() {
        val exec = sampleExecution(status = ExecutionStatus.COMPLETED)
        val page = PageImpl(listOf(exec))
        every { executionRepository.findByStatus(ExecutionStatus.COMPLETED, any()) } returns page

        mockMvc.get("/api/v1/executions") {
            param("status", "COMPLETED")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].status") { value("COMPLETED") }
        }
    }

    @Test
    fun `GET list executions with workflowName filter`() {
        val exec = sampleExecution(workflowName = "my-wf")
        val page = PageImpl(listOf(exec))
        every { executionRepository.findByWorkflowName("my-wf", any()) } returns page

        mockMvc.get("/api/v1/executions") {
            param("workflowName", "my-wf")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].workflowName") { value("my-wf") }
        }
    }

    @Test
    fun `GET list executions with both status and workflowName filters`() {
        val exec = sampleExecution(status = ExecutionStatus.FAILED, workflowName = "my-wf")
        val page = PageImpl(listOf(exec))
        every { executionRepository.findByStatusAndWorkflowName(ExecutionStatus.FAILED, "my-wf", any()) } returns page

        mockMvc.get("/api/v1/executions") {
            param("status", "FAILED")
            param("workflowName", "my-wf")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].status") { value("FAILED") }
            jsonPath("$.content[0].workflowName") { value("my-wf") }
        }
    }

    @Test
    fun `GET list executions returns empty page`() {
        val page = PageImpl<ExecutionEntity>(emptyList())
        every { executionRepository.findAll(any<Pageable>()) } returns page

        mockMvc.get("/api/v1/executions")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(0) }
                jsonPath("$.totalElements") { value(0) }
            }
    }

    // ── GET /api/v1/executions/{id}/tasks ───────────────────────────────

    @Test
    fun `GET execution tasks returns 200 with task list`() {
        val task1 = sampleTask(stepName = "step-1", stepOrder = 0)
        val task2 = sampleTask(stepName = "step-2", stepOrder = 1)
        every { executionRepository.existsById(executionId) } returns true
        every { taskRepository.findByExecutionIdOrderByStepOrderAsc(executionId) } returns listOf(task1, task2)

        mockMvc.get("/api/v1/executions/$executionId/tasks")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].stepName") { value("step-1") }
                jsonPath("$[1].stepName") { value("step-2") }
            }
    }

    @Test
    fun `GET execution tasks returns 404 when execution not found`() {
        val missingId = UUID.randomUUID()
        every { executionRepository.existsById(missingId) } returns false

        mockMvc.get("/api/v1/executions/$missingId/tasks")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
            }
    }

    // ── GET /api/v1/executions/{id}/timeline ────────────────────────────

    @Test
    fun `GET execution timeline returns 200`() {
        val exec = sampleExecution()
        exec.completedAt = now
        val task = sampleTask()
        every { executionRepository.findById(executionId) } returns Optional.of(exec)
        every { taskRepository.findByExecutionIdOrderByStepOrderAsc(executionId) } returns listOf(task)

        mockMvc.get("/api/v1/executions/$executionId/timeline")
            .andExpect {
                status { isOk() }
                jsonPath("$.executionId") { value(executionId.toString()) }
                jsonPath("$.workflowName") { value("test-workflow") }
                jsonPath("$.steps.length()") { value(1) }
                jsonPath("$.steps[0].stepName") { value("step-1") }
            }
    }

    @Test
    fun `GET execution timeline returns 404 when not found`() {
        val missingId = UUID.randomUUID()
        every { executionRepository.findById(missingId) } returns Optional.empty()

        mockMvc.get("/api/v1/executions/$missingId/timeline")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
            }
    }

    // ── POST /api/v1/executions/{id}/cancel ─────────────────────────────

    @Test
    fun `POST cancel returns 200 with cancelled execution`() {
        val exec = sampleExecution(status = ExecutionStatus.CANCELLED)
        every { executionEngine.cancelExecution(executionId) } returns exec

        mockMvc.post("/api/v1/executions/$executionId/cancel")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CANCELLED") }
            }
    }

    @Test
    fun `POST cancel returns 404 when execution not found`() {
        val missingId = UUID.randomUUID()
        every { executionEngine.cancelExecution(missingId) } throws
            NoSuchElementException("Execution not found: $missingId")

        mockMvc.post("/api/v1/executions/$missingId/cancel")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
            }
    }

    @Test
    fun `POST cancel returns 409 when execution not cancellable`() {
        every { executionEngine.cancelExecution(executionId) } throws
            IllegalStateException("Execution is not in a cancellable state")

        mockMvc.post("/api/v1/executions/$executionId/cancel")
            .andExpect {
                status { isConflict() }
                jsonPath("$.status") { value(409) }
            }
    }
}
