package dev.konduit.queue

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * CRITICAL TEST: Verifies that SKIP LOCKED prevents duplicate task acquisition
 * under concurrent access. 10 tasks, 10 concurrent workers, each gets a unique task.
 */
@Import(TestWorkflowConfig::class)
class SkipLockedConcurrencyTest : IntegrationTestBase() {

    @Autowired lateinit var taskQueue: TaskQueue
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
                input = mapOf("test" to "concurrent"),
                currentStep = "step-1"
            )
        )
        executionId = exec.id!!
    }

    @Test
    fun `10 concurrent workers acquire 10 different tasks with no duplicates or deadlocks`() {
        // Insert 10 PENDING tasks
        val taskIds = (1..10).map { i ->
            taskRepository.save(
                TaskEntity(
                    executionId = executionId,
                    stepName = "step-$i",
                    stepType = StepType.SEQUENTIAL,
                    stepOrder = i,
                    status = TaskStatus.PENDING,
                    input = mapOf("task" to i),
                    maxAttempts = 3,
                    backoffStrategy = dev.konduit.retry.BackoffStrategy.FIXED,
                    backoffBaseMs = 100
                )
            ).id!!
        }.toSet()

        assertEquals(10, taskIds.size, "Should have created 10 unique tasks")

        // Launch 10 coroutines concurrently, each acquiring a task
        val acquiredTasks = ConcurrentLinkedQueue<UUID>()
        val errors = ConcurrentLinkedQueue<Throwable>()

        runBlocking {
            val jobs = (1..10).map { workerId ->
                async(Dispatchers.IO) {
                    try {
                        val task = taskQueue.acquireTask("worker-$workerId")
                        if (task != null) {
                            acquiredTasks.add(task.id!!)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            jobs.awaitAll()
        }

        // Assert: no exceptions (no deadlocks)
        assertTrue(errors.isEmpty(), "Expected no exceptions but got: ${errors.map { it.message }}")

        // Assert: all 10 acquired different tasks (no duplicates)
        val acquiredSet = acquiredTasks.toSet()
        assertEquals(10, acquiredSet.size, "All 10 workers should acquire different tasks, got ${acquiredTasks.size} total, ${acquiredSet.size} unique")

        // Assert: all acquired tasks are from our original set
        assertTrue(taskIds.containsAll(acquiredSet), "Acquired tasks should be from the original set")

        // Verify all tasks are now LOCKED in the database
        val lockedTasks = taskRepository.findAll().filter { it.status == TaskStatus.LOCKED }
        assertEquals(10, lockedTasks.size, "All 10 tasks should be LOCKED")
    }
}

