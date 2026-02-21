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
import org.springframework.transaction.annotation.Transactional
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
@Transactional
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

    @Test
    fun `task completed by slow worker before reclamation is not reset to PENDING`() {
        // Simulate: a task was LOCKED with an expired lock timeout, but a slow worker
        // completed it (LOCKED→COMPLETED) before the reclaimer runs.
        // The atomic UPDATE WHERE status='LOCKED' should be a no-op for this task.
        val task = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "step-1",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = "slow-worker",
                lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                input = mapOf("key" to "value"),
                attempt = 1,
                maxAttempts = 3
            )
        )

        // Slow worker completes the task before reclaimer runs
        val loaded = taskRepository.findById(task.id!!).get()
        loaded.status = TaskStatus.COMPLETED
        loaded.output = mapOf("result" to "done")
        loaded.completedAt = Instant.now()
        taskRepository.save(loaded)

        // Now the reclaimer runs — the task is COMPLETED, not LOCKED, so the
        // atomic UPDATE WHERE status='LOCKED' should skip it
        orphanReclaimer.reclaimOrphanedTasks()

        val afterReclaim = taskRepository.findById(task.id!!).get()
        assertEquals(TaskStatus.COMPLETED, afterReclaim.status,
            "Completed task must NOT be reset to PENDING by orphan reclaimer")
        assertEquals(mapOf("result" to "done"), afterReclaim.output,
            "Completed task output must be preserved")
        assertEquals(1, afterReclaim.attempt,
            "Attempt counter must be preserved")
    }

    @Test
    fun `concurrent reclamation and completion does not produce duplicates`() {
        // Create multiple tasks: some will be completed by a "slow worker" concurrently
        // with the reclaimer running. The atomic UPDATE ensures no double-processing.
        val orphanedTask = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "orphan-step",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.LOCKED,
                lockedBy = "dead-worker",
                lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                input = mapOf("key" to "orphan"),
                attempt = 0,
                maxAttempts = 3
            )
        )

        val completedTask = taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = "completed-step",
                stepType = StepType.SEQUENTIAL,
                stepOrder = 1,
                status = TaskStatus.LOCKED,
                lockedBy = "slow-worker",
                lockedAt = Instant.now().minus(Duration.ofMinutes(10)),
                lockTimeoutAt = Instant.now().minus(Duration.ofMinutes(5)),
                input = mapOf("key" to "completed"),
                attempt = 1,
                maxAttempts = 3
            )
        )

        // Slow worker completes one task before reclaimer runs
        val loaded = taskRepository.findById(completedTask.id!!).get()
        loaded.status = TaskStatus.COMPLETED
        loaded.output = mapOf("result" to "success")
        loaded.completedAt = Instant.now()
        taskRepository.save(loaded)

        // Reclaimer runs — should only reclaim the truly orphaned task
        orphanReclaimer.reclaimOrphanedTasks()

        // Verify: orphaned task was reclaimed
        val reclaimedOrphan = taskRepository.findById(orphanedTask.id!!).get()
        assertEquals(TaskStatus.PENDING, reclaimedOrphan.status,
            "Truly orphaned task should be reclaimed to PENDING")
        assertNull(reclaimedOrphan.lockedBy)

        // Verify: completed task was NOT affected
        val stillCompleted = taskRepository.findById(completedTask.id!!).get()
        assertEquals(TaskStatus.COMPLETED, stillCompleted.status,
            "Completed task must remain COMPLETED — no double-processing")
        assertEquals(mapOf("result" to "success"), stillCompleted.output,
            "Completed task output must be preserved")

        // Verify: no duplicate PENDING tasks exist for the completed step
        val allTasks = taskRepository.findByExecutionId(executionId)
        val pendingTasks = allTasks.filter { it.status == TaskStatus.PENDING }
        assertEquals(1, pendingTasks.size,
            "Only the truly orphaned task should be PENDING, not the completed one")
        assertEquals("orphan-step", pendingTasks[0].stepName)
    }
}

