package dev.konduit.queue

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import dev.konduit.retry.BackoffStrategy
import dev.konduit.retry.RetryPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.UUID

@Import(TestWorkflowConfig::class)
class TaskQueueIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var taskQueue: TaskQueue
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var deadLetterRepository: DeadLetterRepository

    private lateinit var workflowId: UUID
    private lateinit var executionId: UUID

    @BeforeEach
    fun setUp() {
        deadLetterRepository.deleteAll()
        taskRepository.deleteAll()
        executionRepository.deleteAll()

        // Ensure a workflow entity exists (WorkflowRegistry creates it on startup,
        // but we need a reference for FK)
        val wf = workflowRepository.findByNameAndVersion("three-step-test", 1)
            ?: workflowRepository.save(
                WorkflowEntity(
                    name = "three-step-test", version = 1,
                    stepDefinitions = mapOf("steps" to emptyList<Any>())
                )
            )
        workflowId = wf.id!!

        val exec = executionRepository.save(
            ExecutionEntity(
                workflowId = workflowId,
                workflowName = "three-step-test",
                workflowVersion = 1,
                status = ExecutionStatus.RUNNING,
                input = mapOf("key" to "value"),
                currentStep = "step-1"
            )
        )
        executionId = exec.id!!
    }

    private fun createPendingTask(stepName: String = "step-1"): TaskEntity {
        return taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = stepName,
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.PENDING,
                input = mapOf("key" to "value"),
                maxAttempts = 3,
                backoffStrategy = dev.konduit.persistence.entity.BackoffStrategy.FIXED,
                backoffBaseMs = 100
            )
        )
    }

    @Test
    fun `acquireTask returns PENDING task with LOCKED status`() {
        val task = createPendingTask()
        val acquired = taskQueue.acquireTask("worker-1")
        assertNotNull(acquired)
        assertEquals(task.id, acquired!!.id)
        assertEquals(TaskStatus.LOCKED, acquired.status)
        assertEquals("worker-1", acquired.lockedBy)
        assertNotNull(acquired.lockedAt)
        assertNotNull(acquired.lockTimeoutAt)
    }

    @Test
    fun `acquireTask returns null when no pending tasks`() {
        val result = taskQueue.acquireTask("worker-1")
        assertNull(result)
    }

    @Test
    fun `completeTask sets COMPLETED status with output and timestamp`() {
        val task = createPendingTask()
        val acquired = taskQueue.acquireTask("worker-1")!!
        val output = mapOf("result" to "success")
        taskQueue.completeTask(acquired.id!!, output)

        val completed = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertEquals(output, completed.output)
        assertNotNull(completed.completedAt)
        assertNull(completed.lockedBy)
    }

    @Test
    fun `failTask with retries remaining sets PENDING with next_retry_at`() {
        val task = createPendingTask()
        taskQueue.acquireTask("worker-1")
        val policy = RetryPolicy(maxAttempts = 3, backoffStrategy = BackoffStrategy.FIXED, baseDelayMs = 100)

        taskQueue.failTask(task.id!!, "transient error", policy)

        val failed = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.PENDING, failed.status)
        assertEquals(1, failed.attempt)
        assertNotNull(failed.nextRetryAt)
        assertEquals("transient error", failed.error)
    }

    @Test
    fun `failTask with retries exhausted sets DEAD_LETTER and creates dead letter entry`() {
        val task = createPendingTask()
        taskQueue.acquireTask("worker-1")
        // maxAttempts=1 means no retries: first failure exhausts
        val policy = RetryPolicy(maxAttempts = 1, backoffStrategy = BackoffStrategy.FIXED, baseDelayMs = 100)

        taskQueue.failTask(task.id!!, "permanent error", policy)

        val deadLettered = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.DEAD_LETTER, deadLettered.status)

        val dlEntries = deadLetterRepository.findAll()
        assertEquals(1, dlEntries.size)
        assertEquals(task.id, dlEntries[0].taskId)
    }

    @Test
    fun `releaseTask sets task back to PENDING`() {
        val task = createPendingTask()
        taskQueue.acquireTask("worker-1")
        taskQueue.releaseTask(task.id!!)

        val released = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.PENDING, released.status)
        assertNull(released.lockedBy)
        assertNull(released.lockedAt)
        assertNull(released.lockTimeoutAt)
    }
}

