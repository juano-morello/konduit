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
class ParallelExecutionIntegrationTest : IntegrationTestBase() {

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

    // ── Fan-out/fan-in happy path ───────────────────────────────────────

    @Test
    fun `parallel fan-out fan-in happy path completes successfully`() {
        val input = mapOf("data" to "start")
        val execution = executionEngine.triggerExecution("parallel-test", input)
        assertEquals(ExecutionStatus.RUNNING, execution.status)

        // 1. First task "prepare" should be PENDING
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertEquals(1, tasks1.size)
        assertEquals("prepare", tasks1[0].stepName)
        assertEquals(TaskStatus.PENDING, tasks1[0].status)

        // 2. Complete "prepare" → 3 parallel tasks created
        val prepareOutput = mapOf("prepared" to true)
        taskQueue.completeTask(tasks1[0].id!!, prepareOutput)
        executionEngine.onTaskCompleted(tasks1[0].id!!)

        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val parallelTasks = tasks2.filter { it.stepType == StepType.PARALLEL }
        assertEquals(3, parallelTasks.size)

        val parallelNames = parallelTasks.map { it.stepName }.toSet()
        assertEquals(setOf("check-a", "check-b", "check-c"), parallelNames)

        // All parallel tasks share the same parallelGroup
        val groups = parallelTasks.map { it.parallelGroup }.toSet()
        assertEquals(1, groups.size)
        assertNotNull(groups.first())

        // All should be PENDING
        assertTrue(parallelTasks.all { it.status == TaskStatus.PENDING })

        // 3. Complete check-a → still waiting
        val checkA = parallelTasks.first { it.stepName == "check-a" }
        taskQueue.completeTask(checkA.id!!, mapOf("a" to "done"))
        executionEngine.onTaskCompleted(checkA.id!!)

        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertFalse(tasks3.any { it.stepName == "combine" }, "combine should not exist yet")

        // 4. Complete check-b → still waiting
        val checkB = parallelTasks.first { it.stepName == "check-b" }
        taskQueue.completeTask(checkB.id!!, mapOf("b" to "done"))
        executionEngine.onTaskCompleted(checkB.id!!)

        val tasks4 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        assertFalse(tasks4.any { it.stepName == "combine" }, "combine should not exist yet")

        // 5. Complete check-c → fan-in triggers → "combine" created
        val checkC = parallelTasks.first { it.stepName == "check-c" }
        taskQueue.completeTask(checkC.id!!, mapOf("c" to "done"))
        executionEngine.onTaskCompleted(checkC.id!!)

        val tasks5 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val combineTask = tasks5.firstOrNull { it.stepName == "combine" }
        assertNotNull(combineTask, "combine task should be created after fan-in")
        assertEquals(TaskStatus.PENDING, combineTask!!.status)

        // Verify combine receives parallelOutputs as input
        val combineInput = combineTask.input
        assertNotNull(combineInput)

        // 6. Complete combine → execution COMPLETED
        taskQueue.completeTask(combineTask.id!!, mapOf("final" to "result"))
        executionEngine.onTaskCompleted(combineTask.id!!)

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status)
        assertNotNull(completedExec.completedAt)
    }

    // ── Parallel failure isolation (ADR-004) ────────────────────────────

    @Test
    fun `parallel failure isolation allows continuation with partial results`() {
        val input = mapOf("data" to "start")
        val execution = executionEngine.triggerExecution("parallel-test", input)

        // Complete "prepare"
        val tasks1 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        taskQueue.completeTask(tasks1[0].id!!, mapOf("prepared" to true))
        executionEngine.onTaskCompleted(tasks1[0].id!!)

        // Get parallel tasks
        val tasks2 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val parallelTasks = tasks2.filter { it.stepType == StepType.PARALLEL }
        assertEquals(3, parallelTasks.size)

        // Complete check-a successfully
        val checkA = parallelTasks.first { it.stepName == "check-a" }
        taskQueue.completeTask(checkA.id!!, mapOf("a" to "done"))
        executionEngine.onTaskCompleted(checkA.id!!)

        // Fail check-b until dead-lettered (maxAttempts=1 in workflow config)
        val checkB = parallelTasks.first { it.stepName == "check-b" }
        val policy = RetryPolicy(
            maxAttempts = 1,
            backoffStrategy = BackoffStrategy.FIXED,
            baseDelayMs = 100
        )
        taskQueue.failTask(checkB.id!!, "check-b failed", policy)
        executionEngine.onTaskDeadLettered(checkB.id!!)

        // Execution should NOT be FAILED — still waiting for check-c
        val execAfterFail = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.RUNNING, execAfterFail.status)

        // Complete check-c successfully
        val checkC = parallelTasks.first { it.stepName == "check-c" }
        taskQueue.completeTask(checkC.id!!, mapOf("c" to "done"))
        executionEngine.onTaskCompleted(checkC.id!!)

        // Fan-in should trigger → "combine" created with partial results
        val tasks3 = taskRepository.findByExecutionIdOrderByStepOrderAsc(execution.id!!)
        val combineTask = tasks3.firstOrNull { it.stepName == "combine" }
        assertNotNull(combineTask, "combine task should be created after fan-in with partial results")
        assertEquals(TaskStatus.PENDING, combineTask!!.status)

        // Verify combine input contains only successful outputs (check-a and check-c, not check-b)
        val combineInput = combineTask.input
        assertNotNull(combineInput)
        // The parallel outputs are passed as input — should have check-a and check-c but not check-b
        assertTrue(combineInput!!.containsKey("check-a"), "Should contain check-a output")
        assertTrue(combineInput.containsKey("check-c"), "Should contain check-c output")
        assertFalse(combineInput.containsKey("check-b"), "Should NOT contain dead-lettered check-b output")

        // Complete combine → execution COMPLETED (not FAILED)
        taskQueue.completeTask(combineTask.id!!, mapOf("final" to "partial-result"))
        executionEngine.onTaskCompleted(combineTask.id!!)

        val completedExec = executionRepository.findById(execution.id!!).get()
        assertEquals(ExecutionStatus.COMPLETED, completedExec.status,
            "Execution should COMPLETE despite one parallel step being dead-lettered")
        assertNotNull(completedExec.completedAt)
    }
}

