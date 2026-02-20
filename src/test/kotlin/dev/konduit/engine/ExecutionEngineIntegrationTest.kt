package dev.konduit.engine

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkflowRepository
import dev.konduit.queue.TaskQueue
import dev.konduit.retry.BackoffStrategy
import dev.konduit.retry.RetryPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@Import(TestWorkflowConfig::class)
class ExecutionEngineIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var executionEngine: ExecutionEngine
    @Autowired lateinit var taskQueue: TaskQueue
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

    // ── Full 3-step sequential workflow ────────────────────────────────

    @Test
    fun `full 3-step workflow completes successfully with chained output`() {
        // 1. Trigger execution
        val input = mapOf("data" to "initial")
        val execution = executionEngine.triggerExecution("three-step-test", input)

        assertNotNull(execution.id)
        assertEquals(ExecutionStatus.RUNNING, execution.status)
        assertNotNull(execution.startedAt)

        // First task should be PENDING
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(1, tasks1.size)
        assertEquals("step-1", tasks1[0].stepName)
        assertEquals(TaskStatus.PENDING, tasks1[0].status)
        assertEquals(input, tasks1[0].input)

        // 2. Complete step-1 → step-2 should be created
        val step1Output = mapOf("step1" to "done")
        taskQueue.completeTask(tasks1[0], step1Output)
        executionEngine.onTaskCompleted(tasks1[0], executionRepository.findById(execution.id!!).get())

        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(2, tasks2.size)
        val step2Task = tasks2.first { it.stepName == "step-2" }
        assertEquals(TaskStatus.PENDING, step2Task.status)
        assertEquals(step1Output, step2Task.input) // chained output

        // 3. Complete step-2 → step-3 should be created
        val step2Output = mapOf("step2" to "done")
        taskQueue.completeTask(step2Task, step2Output)
        executionEngine.onTaskCompleted(step2Task, executionRepository.findById(execution.id!!).get())

        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(3, tasks3.size)
        val step3Task = tasks3.first { it.stepName == "step-3" }
        assertEquals(TaskStatus.PENDING, step3Task.status)
        assertEquals(step2Output, step3Task.input)

        // 4. Complete step-3 → execution should be COMPLETED
        val finalOutput = mapOf("final" to "result")
        taskQueue.completeTask(step3Task, finalOutput)
        executionEngine.onTaskCompleted(step3Task, executionRepository.findById(execution.id!!).get())

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status)
        assertEquals(finalOutput, completedExec.output)
        assertNotNull(completedExec.completedAt)
        assertNull(completedExec.currentStep)
    }

    // ── Retry exhaustion → execution FAILED ────────────────────────────

    @Test
    fun `retry exhaustion leads to execution FAILED`() {
        val execution = executionEngine.triggerExecution(
            "single-step-test", mapOf("data" to "will-fail")
        )

        val tasks = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(1, tasks.size)
        val task = tasks[0]

        // Fail with maxAttempts=1 → immediate dead letter
        val policy = RetryPolicy(
            maxAttempts = 1,
            backoffStrategy = BackoffStrategy.FIXED,
            baseDelayMs = 100
        )
        taskQueue.failTask(task.id!!, "fatal error", policy)
        val deadLetteredTask = taskRepository.findById(task.id!!).get()
        executionEngine.onTaskDeadLettered(deadLetteredTask, executionRepository.findById(execution.id!!).get())

        val failedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.FAILED, failedExec.status)
        assertNotNull(failedExec.completedAt)
        assertNotNull(failedExec.error)
        assertTrue(failedExec.error!!.contains("dead-lettered"))
    }

    // ── Cancel execution ───────────────────────────────────────────────

    @Test
    fun `cancelExecution sets CANCELLED and prevents new task dispatch`() {
        val execution = executionEngine.triggerExecution(
            "three-step-test", mapOf("data" to "will-cancel")
        )
        assertEquals(ExecutionStatus.RUNNING, execution.status)

        // Cancel the execution
        val cancelled = executionEngine.cancelExecution(execution.id!!)
        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
        assertNotNull(cancelled.completedAt)

        // Complete the first task — engine should NOT create step-2
        val tasks = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val step1 = tasks[0]
        taskQueue.completeTask(step1, mapOf("done" to true))
        executionEngine.onTaskCompleted(step1, executionRepository.findById(execution.id!!).get())

        // Should still be only 1 task (no step-2 created)
        val allTasks = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(1, allTasks.size, "No new tasks should be dispatched after cancellation")

        // Execution should still be CANCELLED
        val finalExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.CANCELLED, finalExec.status)
    }
}

