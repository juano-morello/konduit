package dev.konduit.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import dev.konduit.api.dto.BatchReprocessRequest
import dev.konduit.persistence.entity.DeadLetterEntity
import dev.konduit.persistence.entity.StepType
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.queue.DeadLetterQueue
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

@WebMvcTest(DeadLetterController::class)
class DeadLetterControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var deadLetterRepository: DeadLetterRepository

    @MockkBean
    lateinit var deadLetterQueue: DeadLetterQueue

    private val deadLetterId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()
    private val now = Instant.now()

    private fun sampleDeadLetter(
        id: UUID = deadLetterId,
        workflowName: String = "test-workflow",
        reprocessed: Boolean = false
    ) = DeadLetterEntity(
        id = id,
        taskId = taskId,
        executionId = executionId,
        workflowName = workflowName,
        stepName = "step-1",
        input = mapOf("key" to "value"),
        errorHistory = listOf(mapOf("attempt" to 1, "error" to "Something failed")),
        reprocessed = reprocessed,
        error = "Something failed",
        attempts = 3,
        createdAt = now
    )

    private fun sampleTask(id: UUID = UUID.randomUUID()) = TaskEntity(
        id = id,
        executionId = executionId,
        stepName = "step-1",
        stepType = StepType.SEQUENTIAL,
        stepOrder = 0,
        status = TaskStatus.PENDING,
        input = mapOf("key" to "value"),
        attempt = 0,
        maxAttempts = 3,
        createdAt = now,
        backoffStrategy = BackoffStrategy.EXPONENTIAL,
        backoffBaseMs = 1000
    )

    // ── GET /api/v1/dead-letters — list ─────────────────────────────────

    @Test
    fun `GET list dead letters returns 200 with paginated response`() {
        val dl = sampleDeadLetter()
        val page = PageImpl(listOf(dl))
        every { deadLetterRepository.findAll(any<Pageable>()) } returns page

        mockMvc.get("/api/v1/dead-letters")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].workflowName") { value("test-workflow") }
                jsonPath("$.page") { value(0) }
                jsonPath("$.totalElements") { value(1) }
            }
    }

    @Test
    fun `GET list dead letters with workflowName filter`() {
        val dl = sampleDeadLetter(workflowName = "my-wf")
        val page = PageImpl(listOf(dl))
        every { deadLetterRepository.findByWorkflowName("my-wf", any()) } returns page

        mockMvc.get("/api/v1/dead-letters") {
            param("workflowName", "my-wf")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].workflowName") { value("my-wf") }
        }
    }

    @Test
    fun `GET list dead letters with executionId filter`() {
        val dl = sampleDeadLetter()
        val page = PageImpl(listOf(dl))
        every { deadLetterRepository.findByExecutionId(executionId, any()) } returns page

        mockMvc.get("/api/v1/dead-letters") {
            param("executionId", executionId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].executionId") { value(executionId.toString()) }
        }
    }

    @Test
    fun `GET list dead letters returns empty page`() {
        val page = PageImpl<DeadLetterEntity>(emptyList())
        every { deadLetterRepository.findAll(any<Pageable>()) } returns page

        mockMvc.get("/api/v1/dead-letters")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(0) }
                jsonPath("$.totalElements") { value(0) }
            }
    }

    // ── GET /api/v1/dead-letters/{id} — get by ID ───────────────────────

    @Test
    fun `GET dead letter by ID returns 200`() {
        val dl = sampleDeadLetter()
        every { deadLetterRepository.findById(deadLetterId) } returns Optional.of(dl)

        mockMvc.get("/api/v1/dead-letters/$deadLetterId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(deadLetterId.toString()) }
                jsonPath("$.workflowName") { value("test-workflow") }
                jsonPath("$.stepName") { value("step-1") }
                jsonPath("$.reprocessed") { value(false) }
            }
    }

    @Test
    fun `GET dead letter by ID returns 404 when not found`() {
        val missingId = UUID.randomUUID()
        every { deadLetterRepository.findById(missingId) } returns Optional.empty()

        mockMvc.get("/api/v1/dead-letters/$missingId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
            }
    }

    // ── POST /api/v1/dead-letters/{id}/reprocess ────────────────────────

    @Test
    fun `POST reprocess returns 200 with new task`() {
        val newTask = sampleTask()
        every { deadLetterQueue.reprocess(deadLetterId) } returns newTask

        mockMvc.post("/api/v1/dead-letters/$deadLetterId/reprocess")
            .andExpect {
                status { isOk() }
                jsonPath("$.stepName") { value("step-1") }
                jsonPath("$.status") { value("PENDING") }
            }
    }

    @Test
    fun `POST reprocess returns 400 when dead letter not found`() {
        val missingId = UUID.randomUUID()
        every { deadLetterQueue.reprocess(missingId) } throws
            IllegalArgumentException("Dead letter not found: $missingId")

        mockMvc.post("/api/v1/dead-letters/$missingId/reprocess")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.status") { value(400) }
            }
    }

    @Test
    fun `POST reprocess returns 400 when already reprocessed`() {
        every { deadLetterQueue.reprocess(deadLetterId) } throws
            IllegalArgumentException("Dead letter already reprocessed: $deadLetterId")

        mockMvc.post("/api/v1/dead-letters/$deadLetterId/reprocess")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.status") { value(400) }
            }
    }

    // ── POST /api/v1/dead-letters/reprocess-batch ───────────────────────

    @Test
    fun `POST reprocess-batch returns 200 with new tasks`() {
        val task1 = sampleTask()
        val task2 = sampleTask(id = UUID.randomUUID())
        every { deadLetterQueue.reprocessBatch(any()) } returns listOf(task1, task2)

        mockMvc.post("/api/v1/dead-letters/reprocess-batch") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                BatchReprocessRequest(workflowName = "test-workflow")
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].stepName") { value("step-1") }
        }
    }

    @Test
    fun `POST reprocess-batch returns 200 with empty list when no matches`() {
        every { deadLetterQueue.reprocessBatch(any()) } returns emptyList()

        mockMvc.post("/api/v1/dead-letters/reprocess-batch") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                BatchReprocessRequest(workflowName = "no-match")
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `POST reprocess-batch with empty body returns 200`() {
        every { deadLetterQueue.reprocessBatch(any()) } returns emptyList()

        mockMvc.post("/api/v1/dead-letters/reprocess-batch") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
        }
    }
}

