package dev.konduit.queue

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
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
import java.util.UUID

/**
 * Tests orphan reclamation for tasks with expired lock timeouts (see [ADR-007](docs/adr/007-orphan-reclamation.md)).
 *
 * An orphaned task is one with status=LOCKED whose lock_timeout_at has expired.
 * The reclaimer resets these to PENDING without incrementing the attempt counter
 * (a lock timeout is not a task failure).
 *
 * Note: OrphanReclaimer has @ConditionalOnProperty(auto-start=true) so it's not
 * auto-created in tests. We manually instantiate it here.
 */
@Import(TestWorkflowConfig::class)
class OrphanReclaimerTest : IntegrationTestBase() {

    private lateinit var orphanReclaimer: OrphanReclaimer
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var deadLetterRepository: DeadLetterRepository

    private lateinit var executionId: UUID

    @BeforeEach
    fun setUp() {
        orphanReclaimer = OrphanReclaimer(taskRepository)
        deadLetterRepository.deleteAll()
        taskRepository.deleteAll()
        executionRepository.deleteAll()

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
    fun `orphaned task with expired lock timeout is reclaimed to PENDING`() {
        val task = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = "dead-worker",
                lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                attempt = 0,
                maxAttempts = 3
            )
        )

        orphanReclaimer.reclaimOrphanedTasks()

        val reclaimed = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.PENDING, reclaimed.status)
        assertNull(reclaimed.lockedBy)
        assertNull(reclaimed.lockedAt)
        assertNull(reclaimed.lockTimeoutAt)
        // Attempt counter must NOT be incremented — lock timeout is not a failure (see ADR-007)
        assertEquals(0, reclaimed.attempt)
    }

    @Test
    fun `task with lock timeout in the future is not reclaimed`() {
        val task = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = "active-worker",
                lockedAt = Instant.now(),
                lockTimeoutAt = Instant.now().plus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                attempt = 0,
                maxAttempts = 3
            )
        )

        orphanReclaimer.reclaimOrphanedTasks()

        val unchanged = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.LOCKED, unchanged.status)
        assertEquals("active-worker", unchanged.lockedBy)
    }

    @Test
    fun `PENDING task is not affected by orphan reclamation`() {
        val task = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.PENDING,
                input = mapOf("key" to "value"),
                attempt = 0,
                maxAttempts = 3
            )
        )

        orphanReclaimer.reclaimOrphanedTasks()

        val unchanged = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.PENDING, unchanged.status)
        assertEquals(0, unchanged.attempt)
    }

    @Test
    fun `multiple orphaned tasks are all reclaimed`() {
        val tasks = (1..3).map { i ->
            taskRepository.save(
                TaskEntity(
                    executionId = executionId,
                    stepName = "step-$i",
                    stepType = StepType.SEQUENTIAL,
                    stepOrder = i - 1,
                    status = TaskStatus.LOCKED,
                    lockedBy = "dead-worker-$i",
                    lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                    lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                    input = mapOf("key" to "value"),
                    attempt = 0,
                    maxAttempts = 3
                )
            )
        }

        orphanReclaimer.reclaimOrphanedTasks()

        tasks.forEach { task ->
            val reclaimed = taskRepository.findById(task.id!!).get()
            assertEquals(TaskStatus.PENDING, reclaimed.status, "Task ${task.stepName} should be PENDING")
            assertNull(reclaimed.lockedBy)
            assertEquals(0, reclaimed.attempt)
        }
    }
}

