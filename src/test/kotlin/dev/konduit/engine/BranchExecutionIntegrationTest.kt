package dev.konduit.engine

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.StepType
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
class BranchExecutionIntegrationTest : IntegrationTestBase() {

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

    // ── Branch selection (LOW → fast-track) ─────────────────────────────

    @Test
    fun `branch selection routes to matching condition`() {
        val execution = executionEngine.triggerExecution(
            "branch-test", mapOf("data" to "start")
        )
        assertEquals(ExecutionStatus.RUNNING, execution.status)

        // Complete "evaluate" with output that maps to "LOW"
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(1, tasks1.size)
        assertEquals("evaluate", tasks1[0].stepName)

        // extractBranchCondition checks for "result" key
        taskQueue.completeTask(tasks1[0], mapOf("result" to "LOW"))
        executionEngine.onTaskCompleted(tasks1[0], executionRepository.findById(execution.id!!).get())

        // Only "fast-track" should be created (not deep-review, escalate, or manual)
        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val branchTasks = tasks2.filter { it.stepType == StepType.BRANCH }
        assertEquals(1, branchTasks.size)
        assertEquals("fast-track", branchTasks[0].stepName)
        assertEquals(TaskStatus.PENDING, branchTasks[0].status)
        assertNotNull(branchTasks[0].branchKey)
        assertEquals("LOW", branchTasks[0].branchKey)

        // No deep-review, escalate, or manual tasks
        assertFalse(tasks2.any { it.stepName == "deep-review" })
        assertFalse(tasks2.any { it.stepName == "escalate" })
        assertFalse(tasks2.any { it.stepName == "manual" })

        // Complete fast-track → finalize should be created
        taskQueue.completeTask(branchTasks[0], mapOf("result" to "fast-tracked"))
        executionEngine.onTaskCompleted(branchTasks[0], executionRepository.findById(execution.id!!).get())

        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val finalizeTask = tasks3.firstOrNull { it.stepName == "finalize" }
        assertNotNull(finalizeTask, "finalize should be created after branch completes")
        assertEquals(TaskStatus.PENDING, finalizeTask!!.status)

        // Complete finalize → execution COMPLETED
        taskQueue.completeTask(finalizeTask, mapOf("finalized" to true))
        executionEngine.onTaskCompleted(finalizeTask, executionRepository.findById(execution.id!!).get())

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status)
        assertNotNull(completedExec.completedAt)
    }

    // ── Otherwise fallback ──────────────────────────────────────────────

    @Test
    fun `otherwise fallback is used when no branch matches`() {
        val execution = executionEngine.triggerExecution(
            "branch-test", mapOf("data" to "start")
        )

        // Complete "evaluate" with "UNKNOWN" (no matching branch)
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        taskQueue.completeTask(tasks1[0], mapOf("result" to "UNKNOWN"))
        executionEngine.onTaskCompleted(tasks1[0], executionRepository.findById(execution.id!!).get())

        // "manual" task from otherwise branch should be created
        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val branchTasks = tasks2.filter { it.stepType == StepType.BRANCH }
        assertEquals(1, branchTasks.size)
        assertEquals("manual", branchTasks[0].stepName)
        assertEquals("otherwise", branchTasks[0].branchKey)

        // Complete manual → finalize → execution COMPLETED
        taskQueue.completeTask(branchTasks[0], mapOf("result" to "manual-done"))
        executionEngine.onTaskCompleted(branchTasks[0], executionRepository.findById(execution.id!!).get())

        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val finalizeTask = tasks3.first { it.stepName == "finalize" }
        taskQueue.completeTask(finalizeTask, mapOf("finalized" to true))
        executionEngine.onTaskCompleted(finalizeTask, executionRepository.findById(execution.id!!).get())

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status)
    }

    // ── Multi-step branch (HIGH → deep-review → escalate) ──────────────

    @Test
    fun `multi-step branch executes steps sequentially within branch`() {
        val execution = executionEngine.triggerExecution(
            "branch-test", mapOf("data" to "start")
        )

        // Complete "evaluate" with "HIGH"
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        taskQueue.completeTask(tasks1[0], mapOf("result" to "HIGH"))
        executionEngine.onTaskCompleted(tasks1[0], executionRepository.findById(execution.id!!).get())

        // "deep-review" should be created first (not "escalate")
        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val branchTasks = tasks2.filter { it.stepType == StepType.BRANCH }
        assertEquals(1, branchTasks.size, "Only first branch step should be created")
        assertEquals("deep-review", branchTasks[0].stepName)
        assertEquals("HIGH", branchTasks[0].branchKey)
        assertFalse(tasks2.any { it.stepName == "escalate" }, "escalate should not exist yet")

        // Complete deep-review → escalate should be created (sequential within branch)
        taskQueue.completeTask(branchTasks[0], mapOf("result" to "reviewed"))
        executionEngine.onTaskCompleted(branchTasks[0], executionRepository.findById(execution.id!!).get())

        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val escalateTask = tasks3.firstOrNull { it.stepName == "escalate" }
        assertNotNull(escalateTask, "escalate should be created after deep-review completes")
        assertEquals(TaskStatus.PENDING, escalateTask!!.status)
        assertEquals(StepType.BRANCH, escalateTask.stepType)
        assertEquals("HIGH", escalateTask.branchKey)

        // Complete escalate → finalize should be created (post-branch continuation)
        taskQueue.completeTask(escalateTask, mapOf("result" to "escalated"))
        executionEngine.onTaskCompleted(escalateTask, executionRepository.findById(execution.id!!).get())

        val tasks4 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val finalizeTask = tasks4.firstOrNull { it.stepName == "finalize" }
        assertNotNull(finalizeTask, "finalize should be created after branch completes")
        assertEquals(TaskStatus.PENDING, finalizeTask!!.status)

        // Complete finalize → execution COMPLETED
        taskQueue.completeTask(finalizeTask, mapOf("finalized" to true))
        executionEngine.onTaskCompleted(finalizeTask, executionRepository.findById(execution.id!!).get())

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status)
        assertNotNull(completedExec.completedAt)
    }

    // ── Branch dead letter → execution FAILED ───────────────────────────

    @Test
    fun `branch step failure leads to execution FAILED`() {
        val execution = executionEngine.triggerExecution(
            "branch-test", mapOf("data" to "start")
        )

        // Complete "evaluate" with "LOW"
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        taskQueue.completeTask(tasks1[0], mapOf("result" to "LOW"))
        executionEngine.onTaskCompleted(tasks1[0], executionRepository.findById(execution.id!!).get())

        // Get the branch task
        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val branchTask = tasks2.first { it.stepName == "fast-track" }

        // Fail the branch task until dead-lettered
        val policy = RetryPolicy(
            maxAttempts = 1,
            backoffStrategy = BackoffStrategy.FIXED,
            baseDelayMs = 100
        )
        taskQueue.failTask(branchTask.id!!, "branch step failed", policy)
        val deadLetteredBranch = taskRepository.findById(branchTask.id!!).get()
        executionEngine.onTaskDeadLettered(deadLetteredBranch, executionRepository.findById(execution.id!!).get())

        // Execution should be FAILED
        val failedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.FAILED, failedExec.status)
        assertNotNull(failedExec.completedAt)
        assertNotNull(failedExec.error)
        assertTrue(failedExec.error!!.contains("dead-lettered"))
    }
}

