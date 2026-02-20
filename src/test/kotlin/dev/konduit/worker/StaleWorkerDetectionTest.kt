package dev.konduit.worker

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import dev.konduit.persistence.repository.WorkflowRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tests stale worker detection and task reclamation.
 *
 * Verifies that workers with expired heartbeats are detected as stale,
 * marked STOPPED, and their locked tasks are reclaimed to PENDING.
 */
@Import(TestWorkflowConfig::class)
class StaleWorkerDetectionTest : IntegrationTestBase() {

    @Autowired lateinit var workerRegistry: WorkerRegistry
    @Autowired lateinit var workerRepository: WorkerRepository
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var deadLetterRepository: DeadLetterRepository

    private lateinit var executionId: UUID

    @BeforeEach
    fun setUp() {
        deadLetterRepository.deleteAll()
        taskRepository.deleteAll()
        executionRepository.deleteAll()
        workerRepository.deleteAll()

        // Create a workflow + execution for FK references
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
    }

    @Test
    fun `stale worker is detected and marked STOPPED`() {
        // Register a worker then set heartbeat far in the past
        // (must be two saves because @PrePersist overrides lastHeartbeat)
        val worker = workerRepository.save(
            WorkerEntity(
                workerId = "stale-worker-1",
                hostname = "test-host",
                status = WorkerStatus.ACTIVE,
                concurrency = 5,
                activeTasks = 1
            )
        )
        worker.lastHeartbeat = Instant.now().minus(Duration.ofMinutes(10))
        workerRepository.save(worker)

        val count = workerRegistry.detectStaleWorkers(Duration.ofSeconds(60))

        assertEquals(1, count)
        val updated = workerRepository.findByWorkerId("stale-worker-1")!!
        assertEquals(WorkerStatus.STOPPED, updated.status)
        assertNotNull(updated.stoppedAt)
        assertEquals(0, updated.activeTasks)
    }

    @Test
    fun `locked tasks from stale worker are reclaimed to PENDING`() {
        // Register a stale worker (two saves — @PrePersist overrides lastHeartbeat)
        val staleWorker = workerRepository.save(
            WorkerEntity(
                workerId = "stale-worker-2",
                hostname = "test-host",
                status = WorkerStatus.ACTIVE,
                concurrency = 5,
                activeTasks = 1
            )
        )
        staleWorker.lastHeartbeat = Instant.now().minus(Duration.ofMinutes(10))
        workerRepository.save(staleWorker)

        // Create a task locked by the stale worker
        val task = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = "stale-worker-2",
                lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                attempt = 0,
                maxAttempts = 3
            )
        )

        workerRegistry.detectStaleWorkers(Duration.ofSeconds(60))

        val reclaimed = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.PENDING, reclaimed.status)
        assertNull(reclaimed.lockedBy)
        assertNull(reclaimed.lockedAt)
        assertNull(reclaimed.lockTimeoutAt)
        // Attempt counter should NOT be incremented (stale detection is not a failure)
        assertEquals(0, reclaimed.attempt)
    }

    @Test
    fun `active worker with recent heartbeat is not detected as stale`() {
        workerRepository.save(
            WorkerEntity(
                workerId = "healthy-worker",
                hostname = "test-host",
                status = WorkerStatus.ACTIVE,
                concurrency = 5,
                activeTasks = 0,
                lastHeartbeat = Instant.now()
            )
        )

        val count = workerRegistry.detectStaleWorkers(Duration.ofSeconds(60))

        assertEquals(0, count)
        val worker = workerRepository.findByWorkerId("healthy-worker")!!
        assertEquals(WorkerStatus.ACTIVE, worker.status)
    }

    @Test
    fun `already STOPPED worker is not detected as stale`() {
        val worker = workerRepository.save(
            WorkerEntity(
                workerId = "stopped-worker",
                hostname = "test-host",
                status = WorkerStatus.STOPPED,
                concurrency = 5,
                activeTasks = 0
            )
        )
        worker.lastHeartbeat = Instant.now().minus(Duration.ofMinutes(10))
        workerRepository.save(worker)

        val count = workerRegistry.detectStaleWorkers(Duration.ofSeconds(60))
        assertEquals(0, count)
    }
}

