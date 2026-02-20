package dev.konduit.worker

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.*
import dev.konduit.retry.BackoffStrategy
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import dev.konduit.queue.TaskQueue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests worker concurrency limit enforcement.
 *
 * Verifies that the worker's poll loop respects the configured concurrency
 * limit and never holds more than the allowed number of active tasks.
 *
 * Since the TaskWorker auto-start is disabled in test config, we test
 * the concurrency enforcement via TaskWorkerState's active task tracking
 * and the TaskQueue's acquire mechanism.
 */
@Import(TestWorkflowConfig::class)
class ConcurrencyLimitTest : IntegrationTestBase() {

    @Autowired lateinit var taskQueue: TaskQueue
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var executionRepository: ExecutionRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var taskWorkerState: TaskWorkerState
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
                input = mapOf("key" to "value"),
                currentStep = "step-1"
            )
        )
        executionId = exec.id!!

        // Reset worker state
        taskWorkerState.workerId = "test-worker"
        taskWorkerState.status.set(WorkerLifecycleStatus.RUNNING)
        // Reset active task count to 0
        while (taskWorkerState.activeTaskCount > 0) {
            taskWorkerState.decrementActiveTasks()
        }
    }

    private fun createPendingTask(stepName: String): TaskEntity {
        return taskRepository.save(
            TaskEntity(
                executionId = executionId,
                stepName = stepName,
                stepType = StepType.SEQUENTIAL,
                stepOrder = 0,
                status = TaskStatus.PENDING,
                input = mapOf("key" to "value"),
                maxAttempts = 3,
                backoffStrategy = BackoffStrategy.FIXED,
                backoffBaseMs = 100
            )
        )
    }

    @Test
    fun `TaskWorkerState tracks active task count correctly`() {
        assertEquals(0, taskWorkerState.activeTaskCount)

        taskWorkerState.incrementActiveTasks()
        assertEquals(1, taskWorkerState.activeTaskCount)

        taskWorkerState.incrementActiveTasks()
        assertEquals(2, taskWorkerState.activeTaskCount)

        taskWorkerState.decrementActiveTasks()
        assertEquals(1, taskWorkerState.activeTaskCount)

        taskWorkerState.decrementActiveTasks()
        assertEquals(0, taskWorkerState.activeTaskCount)
    }

    @Test
    fun `concurrent task acquisition respects SKIP LOCKED - no duplicate acquisition`() {
        // Create 5 pending tasks
        val tasks = (1..5).map { createPendingTask("step-$it") }

        // Acquire tasks from two different "workers" — each should get a unique task
        val acquired1 = taskQueue.acquireTask("worker-1")
        val acquired2 = taskQueue.acquireTask("worker-2")

        assertNotNull(acquired1)
        assertNotNull(acquired2)
        assertNotEquals(acquired1!!.id, acquired2!!.id, "Two workers should not acquire the same task")
    }

    @Test
    fun `worker state prevents over-acquisition when at concurrency limit`() {
        val concurrencyLimit = 2

        // Create 5 pending tasks
        (1..5).forEach { createPendingTask("step-$it") }

        // Simulate the worker's concurrency check logic from pollAndExecute()
        val acquiredTasks = mutableListOf<TaskEntity>()
        for (i in 1..5) {
            // This mirrors the check in TaskWorker.pollAndExecute()
            if (taskWorkerState.activeTaskCount >= concurrencyLimit) {
                break
            }
            val task = taskQueue.acquireTask("test-worker")
            if (task != null) {
                taskWorkerState.incrementActiveTasks()
                acquiredTasks.add(task)
            }
        }

        assertEquals(concurrencyLimit, acquiredTasks.size,
            "Worker should only acquire $concurrencyLimit tasks when at concurrency limit")
        assertEquals(concurrencyLimit, taskWorkerState.activeTaskCount)

        // Remaining tasks should still be PENDING
        val pendingCount = taskRepository.countByStatus(TaskStatus.PENDING)
        assertEquals(3, pendingCount, "3 tasks should remain PENDING")
    }

    @Test
    fun `active task count is thread-safe under concurrent increments`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val maxObserved = AtomicInteger(0)

        val threads = (1..threadCount).map {
            Thread {
                taskWorkerState.incrementActiveTasks()
                val current = taskWorkerState.activeTaskCount
                maxObserved.updateAndGet { max -> maxOf(max, current) }
                latch.countDown()
            }
        }

        threads.forEach { it.start() }
        latch.await()

        assertEquals(threadCount, taskWorkerState.activeTaskCount,
            "All increments should be reflected")
    }
}

