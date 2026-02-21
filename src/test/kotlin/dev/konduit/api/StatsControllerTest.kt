package dev.konduit.api

import com.ninjasquad.springmockk.MockkBean
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant

@WebMvcTest(StatsController::class)
class StatsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var executionRepository: ExecutionRepository

    @MockkBean
    private lateinit var taskRepository: TaskRepository

    @MockkBean
    private lateinit var workerRepository: WorkerRepository

    @MockkBean
    private lateinit var deadLetterRepository: DeadLetterRepository

    @Test
    fun `GET stats returns 200 with correct structure`() {
        // Execution stats: 2 RUNNING, 3 COMPLETED
        every { executionRepository.countGroupByStatus() } returns listOf(
            arrayOf(ExecutionStatus.RUNNING as Any, 2L as Any),
            arrayOf(ExecutionStatus.COMPLETED as Any, 3L as Any)
        )

        // Task stats: 5 PENDING, 10 COMPLETED
        every { taskRepository.countGroupByStatus() } returns listOf(
            arrayOf(TaskStatus.PENDING as Any, 5L as Any),
            arrayOf(TaskStatus.COMPLETED as Any, 10L as Any)
        )

        // Worker stats
        every { workerRepository.count() } returns 4L
        every { workerRepository.getAggregateStatsByStatus(WorkerStatus.ACTIVE) } returns listOf(
            arrayOf(3L as Any, 15L as Any, 7L as Any)
        )

        // Dead letters
        every { deadLetterRepository.count() } returns 2L

        // Throughput
        every { executionRepository.countByStatusAndCompletedAtAfter(ExecutionStatus.COMPLETED, any<Instant>()) } returns 120L
        every { taskRepository.countByStatusAndCompletedAtAfter(TaskStatus.COMPLETED, any<Instant>()) } returns 360L

        mockMvc.perform(get("/api/v1/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.executions.total").value(5))
            .andExpect(jsonPath("$.executions.byStatus.RUNNING").value(2))
            .andExpect(jsonPath("$.executions.byStatus.COMPLETED").value(3))
            .andExpect(jsonPath("$.tasks.total").value(15))
            .andExpect(jsonPath("$.tasks.byStatus.PENDING").value(5))
            .andExpect(jsonPath("$.tasks.byStatus.COMPLETED").value(10))
            .andExpect(jsonPath("$.workers.total").value(4))
            .andExpect(jsonPath("$.workers.active").value(3))
            .andExpect(jsonPath("$.workers.totalConcurrency").value(15))
            .andExpect(jsonPath("$.workers.totalActiveTasks").value(7))
            .andExpect(jsonPath("$.deadLetters").value(2))
            .andExpect(jsonPath("$.queueDepth").value(5))
            .andExpect(jsonPath("$.throughput.executionsPerMinute").value(2.0))
            .andExpect(jsonPath("$.throughput.tasksPerMinute").value(6.0))
    }

    @Test
    fun `GET stats returns zeros when no data exists`() {
        every { executionRepository.countGroupByStatus() } returns emptyList()
        every { taskRepository.countGroupByStatus() } returns emptyList()
        every { workerRepository.count() } returns 0L
        every { workerRepository.getAggregateStatsByStatus(WorkerStatus.ACTIVE) } returns emptyList()
        every { deadLetterRepository.count() } returns 0L
        every { executionRepository.countByStatusAndCompletedAtAfter(ExecutionStatus.COMPLETED, any<Instant>()) } returns 0L
        every { taskRepository.countByStatusAndCompletedAtAfter(TaskStatus.COMPLETED, any<Instant>()) } returns 0L

        mockMvc.perform(get("/api/v1/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.executions.total").value(0))
            .andExpect(jsonPath("$.executions.byStatus").isEmpty)
            .andExpect(jsonPath("$.tasks.total").value(0))
            .andExpect(jsonPath("$.workers.total").value(0))
            .andExpect(jsonPath("$.workers.active").value(0))
            .andExpect(jsonPath("$.workers.totalConcurrency").value(0))
            .andExpect(jsonPath("$.workers.totalActiveTasks").value(0))
            .andExpect(jsonPath("$.deadLetters").value(0))
            .andExpect(jsonPath("$.queueDepth").value(0))
            .andExpect(jsonPath("$.throughput.executionsPerMinute").value(0.0))
            .andExpect(jsonPath("$.throughput.tasksPerMinute").value(0.0))
    }
}

