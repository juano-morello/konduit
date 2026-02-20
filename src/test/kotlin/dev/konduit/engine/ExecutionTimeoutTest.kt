package dev.konduit.engine

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.WorkflowEntity
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant

/**
 * Tests execution timeout checking.
 *
 * Verifies that RUNNING executions with timeout_at in the past
 * are transitioned to TIMED_OUT by ExecutionTimeoutChecker.
 */
@Import(TestWorkflowConfig::class)
class ExecutionTimeoutTest : IntegrationTestBase() {

    @Autowired lateinit var executionTimeoutChecker: ExecutionTimeoutChecker
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var deadLetterRepository: DeadLetterRepository

    @BeforeEach
    fun setUp() {
        deadLetterRepository.deleteAll()
        taskRepository.deleteAll()
        executionRepository.deleteAll()
    }

    private fun createExecution(
        status: ExecutionStatus,
        timeoutAt: Instant? = null
    ): ExecutionEntity {
        val wf = workflowRepository.findByNameAndVersion("three-step-test", 1)
            ?: workflowRepository.save(
                WorkflowEntity(
                    name = "three-step-test", version = 1,
                    stepDefinitions = mapOf("steps" to emptyList<Any>())
                )
            )

        return executionRepository.save(
            ExecutionEntity(
                workflowId = wf.id!!,
                workflowName = "three-step-test",
                workflowVersion = 1,
                status = status,
                input = mapOf("key" to "value"),
                currentStep = "step-1",
                timeoutAt = timeoutAt,
                startedAt = if (status == ExecutionStatus.RUNNING) Instant.now() else null
            )
        )
    }

    @Test
    fun `RUNNING execution with expired timeout is transitioned to TIMED_OUT`() {
        val exec = createExecution(
            status = ExecutionStatus.RUNNING,
            timeoutAt = Instant.now().minus(Duration.ofMinutes(5))
        )

        val count = executionTimeoutChecker.checkTimeouts()

        assertEquals(1, count)
        val updated = executionRepository.findById(exec.id!!).get()
        assertEquals(ExecutionStatus.TIMED_OUT, updated.status)
        assertNotNull(updated.completedAt)
        assertNotNull(updated.error)
        assertTrue(updated.error!!.contains("timed out"))
    }

    @Test
    fun `RUNNING execution with future timeout is not affected`() {
        val exec = createExecution(
            status = ExecutionStatus.RUNNING,
            timeoutAt = Instant.now().plus(Duration.ofMinutes(30))
        )

        val count = executionTimeoutChecker.checkTimeouts()

        assertEquals(0, count)
        val unchanged = executionRepository.findById(exec.id!!).get()
        assertEquals(ExecutionStatus.RUNNING, unchanged.status)
    }

    @Test
    fun `RUNNING execution without timeout is not affected`() {
        val exec = createExecution(
            status = ExecutionStatus.RUNNING,
            timeoutAt = null
        )

        val count = executionTimeoutChecker.checkTimeouts()

        assertEquals(0, count)
        val unchanged = executionRepository.findById(exec.id!!).get()
        assertEquals(ExecutionStatus.RUNNING, unchanged.status)
    }

    @Test
    fun `COMPLETED execution with expired timeout is not affected`() {
        val exec = createExecution(
            status = ExecutionStatus.RUNNING,
            timeoutAt = Instant.now().minus(Duration.ofMinutes(5))
        )
        // Manually set to COMPLETED to simulate a race condition
        exec.status = ExecutionStatus.COMPLETED
        exec.completedAt = Instant.now()
        executionRepository.save(exec)

        val count = executionTimeoutChecker.checkTimeouts()

        assertEquals(0, count)
        val unchanged = executionRepository.findById(exec.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, unchanged.status)
    }

    @Test
    fun `multiple timed-out executions are all transitioned`() {
        val execs = (1..3).map {
            createExecution(
                status = ExecutionStatus.RUNNING,
                timeoutAt = Instant.now().minus(Duration.ofMinutes(it.toLong()))
            )
        }

        val count = executionTimeoutChecker.checkTimeouts()

        assertEquals(3, count)
        execs.forEach { exec ->
            val updated = executionRepository.findById(exec.id!!).get()
            assertEquals(ExecutionStatus.TIMED_OUT, updated.status)
        }
    }
}

