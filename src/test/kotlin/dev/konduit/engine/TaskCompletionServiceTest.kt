package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.queue.TaskQueue
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class TaskCompletionServiceTest {

    private val taskQueue = mockk<TaskQueue>()
    private val executionAdvancer = mockk<ExecutionAdvancer>()
    private val service = TaskCompletionService(taskQueue, executionAdvancer)

    private lateinit var task: TaskEntity
    private lateinit var execution: ExecutionEntity

    @BeforeEach
    fun setUp() {
        task = TaskEntity(
            id = UUID.randomUUID(),
            executionId = UUID.randomUUID(),
            stepName = "step-1",
            status = TaskStatus.RUNNING
        )
        execution = ExecutionEntity(
            id = task.executionId,
            workflowName = "test-workflow",
            status = ExecutionStatus.RUNNING
        )
    }

    @Test
    fun `completeAndAdvance calls taskQueue then advancer in order`() {
        val callOrder = mutableListOf<String>()

        every { taskQueue.completeTask(task, any()) } answers {
            callOrder.add("completeTask")
        }
        every { executionAdvancer.onTaskCompleted(task, execution) } answers {
            callOrder.add("onTaskCompleted")
        }

        val output = mapOf("result" to "success")
        service.completeAndAdvance(task, execution, output)

        assertEquals(listOf("completeTask", "onTaskCompleted"), callOrder)
        verify(exactly = 1) { taskQueue.completeTask(task, output) }
        verify(exactly = 1) { executionAdvancer.onTaskCompleted(task, execution) }
    }

    @Test
    fun `taskQueue throws then advancer is not called`() {
        every { taskQueue.completeTask(task, any()) } throws
            IllegalStateException("Cannot complete task: already in terminal status")

        assertThrows<IllegalStateException> {
            service.completeAndAdvance(task, execution, null)
        }

        verify(exactly = 1) { taskQueue.completeTask(task, null) }
        verify(exactly = 0) { executionAdvancer.onTaskCompleted(any(), any()) }
    }

    @Test
    fun `advancer throws then exception is propagated`() {
        every { taskQueue.completeTask(task, any()) } just Runs
        every { executionAdvancer.onTaskCompleted(task, execution) } throws
            RuntimeException("Advancement failed")

        val ex = assertThrows<RuntimeException> {
            service.completeAndAdvance(task, execution, mapOf("key" to "val"))
        }

        assertEquals("Advancement failed", ex.message)
        verify(exactly = 1) { taskQueue.completeTask(task, mapOf("key" to "val")) }
        verify(exactly = 1) { executionAdvancer.onTaskCompleted(task, execution) }
    }

    @Test
    fun `completeAndAdvance passes null output correctly`() {
        every { taskQueue.completeTask(task, null) } just Runs
        every { executionAdvancer.onTaskCompleted(task, execution) } just Runs

        service.completeAndAdvance(task, execution, null)

        verify(exactly = 1) { taskQueue.completeTask(task, null) }
        verify(exactly = 1) { executionAdvancer.onTaskCompleted(task, execution) }
    }
}

