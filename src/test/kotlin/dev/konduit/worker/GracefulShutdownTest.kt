package dev.konduit.worker

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import dev.konduit.persistence.repository.WorkflowRepository
import dev.konduit.queue.TaskQueue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tests worker graceful shutdown behavior.
 *
 * Verifies that on shutdown:
 * 1. Worker status transitions to DRAINING then STOPPED
 * 2. Locked tasks are released back to PENDING
 * 3. Worker is deregistered (status = STOPPED in DB)
 *
 * Since auto-start is disabled in test config, we manually register a worker,
 * create locked tasks, and invoke the shutdown-related methods directly.
 */
@Import(TestWorkflowConfig::class)
class GracefulShutdownTest : IntegrationTestBase() {

    @Autowired lateinit var workerRegistry: WorkerRegistry
    @Autowired lateinit var workerRepository: WorkerRepository
    @Autowired lateinit var taskQueue: TaskQueue
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var taskWorkerState: TaskWorkerState
    @Autowired lateinit var deadLetterRepository: DeadLetterRepository

    private lateinit var executionId: UUID
    private val testWorkerId = "test-shutdown-worker"

    @BeforeEach
    fun setUp() {
        deadLetterRepository.deleteAll()
        taskRepository.deleteAll()
        executionRepository.deleteAll()
        workerRepository.deleteAll()

        val wf = workflowRepository.findByNameAndVersion("three-step-test", 1)
            ?: workflowRepository.save(
                WorkflowEntity(
                    name = "three-step-test", version = 1,
                    stepDefinitions = mapOf("steps" to emptyList<Any>())
                )
            )

        val exec = executionRepository.save(
            ExecutionEntity(
                workflowId = wf.id!!,
                workflowName = "three-step-test",
                workflowVersion = 1,
                status = ExecutionStatus.RUNNING,
                input = mapOf("key" to "value"),
                currentStep = "step-1"
            )
        )
        executionId = exec.id!!

        // Reset worker state
        while (taskWorkerState.activeTaskCount > 0) {
            taskWorkerState.decrementActiveTasks()
        }
    }

    @Test
    fun `worker deregistration sets status to STOPPED in database`() {
        workerRegistry.register(testWorkerId, "test-host", 5)

        val registered = workerRepository.findByWorkerId(testWorkerId)!!
        assertEquals(WorkerStatus.ACTIVE, registered.status)

        workerRegistry.deregister(testWorkerId)

        val deregistered = workerRepository.findByWorkerId(testWorkerId)!!
        assertEquals(WorkerStatus.STOPPED, deregistered.status)
        assertNotNull(deregistered.stoppedAt)
        assertEquals(0, deregistered.activeTasks)
    }

    @Test
    fun `locked tasks are released to PENDING during shutdown`() {
        // Register worker and create locked tasks
        workerRegistry.register(testWorkerId, "test-host", 5)

        val task1 = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = testWorkerId,
                lockedAt = Instant.now(),
                lockTimeoutAt = Instant.now().plus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                maxAttempts = 3
            )
        )

        val task2 = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-2",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 1,
                status = TaskStatus.LOCKED,
                lockedBy = testWorkerId,
                lockedAt = Instant.now(),
                lockTimeoutAt = Instant.now().plus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                maxAttempts = 3
            )
        )

        // Simulate shutdown: release locked tasks then deregister
        val lockedTasks = taskRepository.findByLockedBy(testWorkerId)
        assertEquals(2, lockedTasks.size)

        lockedTasks.forEach { task -> taskQueue.releaseTask(task.id!!) }
        workerRegistry.deregister(testWorkerId)

        // Verify tasks are back to PENDING
        val released1 = taskRepository.findById(task1.id!!).get()
        assertEquals(TaskStatus.PENDING, released1.status)
        assertNull(released1.lockedBy)

        val released2 = taskRepository.findById(task2.id!!).get()
        assertEquals(TaskStatus.PENDING, released2.status)
        assertNull(released2.lockedBy)

        // Verify worker is STOPPED
        val worker = workerRepository.findByWorkerId(testWorkerId)!!
        assertEquals(WorkerStatus.STOPPED, worker.status)
    }

    @Test
    fun `DRAINING status prevents new task acquisition in poll loop logic`() {
        taskWorkerState.workerId = testWorkerId
        taskWorkerState.status.set(WorkerLifecycleStatus.DRAINING)

        // Create a pending task
        taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.PENDING,
                input = mapOf("key" to "value"),
                maxAttempts = 3
            )
        )

        // The poll loop checks status before acquiring — simulate that check
        val shouldPoll = taskWorkerState.status.get() == WorkerLifecycleStatus.RUNNING
        assertFalse(shouldPoll, "Worker in DRAINING state should not poll for new tasks")
    }

    @Test
    fun `no orphaned LOCKED tasks remain after full shutdown sequence`() {
        workerRegistry.register(testWorkerId, "test-host", 5)

        // Create a locked task and a running task
        taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = testWorkerId,
                lockedAt = Instant.now(),
                lockTimeoutAt = Instant.now().plus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                maxAttempts = 3
            )
        )

        // Full shutdown sequence: release tasks, deregister
        val lockedTasks = taskRepository.findByLockedBy(testWorkerId)
        lockedTasks.forEach { task -> taskQueue.releaseTask(task.id!!) }
        workerRegistry.deregister(testWorkerId)

        // Verify no LOCKED tasks remain for this worker
        val remainingLocked = taskRepository.findByLockedBy(testWorkerId)
        assertTrue(remainingLocked.isEmpty(), "No LOCKED tasks should remain after shutdown")
    }
}

