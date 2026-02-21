package dev.konduit.api

import com.ninjasquad.springmockk.MockkBean
import dev.konduit.persistence.entity.WorkerEntity
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.WorkerRepository
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.UUID

@WebMvcTest(WorkerController::class)
class WorkerControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var workerRepository: WorkerRepository

    @Test
    fun `GET workers returns 200 with list of workers`() {
        val workerId = UUID.randomUUID()
        val now = Instant.now()
        val worker = WorkerEntity(
            id = workerId,
            workerId = "worker-1",
            status = WorkerStatus.ACTIVE,
            hostname = "host-1",
            concurrency = 5,
            activeTasks = 2,
            lastHeartbeat = now,
            startedAt = now,
            stoppedAt = null
        )

        every { workerRepository.findAll() } returns listOf(worker)

        mockMvc.perform(get("/api/v1/workers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(workerId.toString()))
            .andExpect(jsonPath("$[0].workerId").value("worker-1"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].hostname").value("host-1"))
            .andExpect(jsonPath("$[0].concurrency").value(5))
            .andExpect(jsonPath("$[0].activeTasks").value(2))
    }

    @Test
    fun `GET workers returns empty list when no workers`() {
        every { workerRepository.findAll() } returns emptyList()

        mockMvc.perform(get("/api/v1/workers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET workers returns multiple workers`() {
        val now = Instant.now()
        val worker1 = WorkerEntity(
            id = UUID.randomUUID(),
            workerId = "worker-1",
            status = WorkerStatus.ACTIVE,
            hostname = "host-1",
            concurrency = 5,
            activeTasks = 2,
            lastHeartbeat = now,
            startedAt = now
        )
        val worker2 = WorkerEntity(
            id = UUID.randomUUID(),
            workerId = "worker-2",
            status = WorkerStatus.STOPPED,
            hostname = "host-2",
            concurrency = 10,
            activeTasks = 0,
            lastHeartbeat = now,
            startedAt = now,
            stoppedAt = now
        )

        every { workerRepository.findAll() } returns listOf(worker1, worker2)

        mockMvc.perform(get("/api/v1/workers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].workerId").value("worker-1"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].workerId").value("worker-2"))
            .andExpect(jsonPath("$[1].status").value("STOPPED"))
    }
}

