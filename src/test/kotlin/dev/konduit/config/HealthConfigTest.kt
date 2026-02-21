package dev.konduit.config

import dev.konduit.coordination.LeaderElectionService
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.worker.TaskWorkerState
import dev.konduit.worker.WorkerLifecycleStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status

class HealthConfigTest {

    private val config = HealthConfig()

    // ── Worker Health Indicator ─────────────────────────────────────────

    @Nested
    inner class WorkerHealth {

        private val taskWorkerState = TaskWorkerState()

        @Test
        fun `RUNNING worker maps to UP`() {
            taskWorkerState.workerId = "worker-1"
            taskWorkerState.status.set(WorkerLifecycleStatus.RUNNING)

            val indicator = config.workerHealthIndicator(taskWorkerState)
            val health = indicator.health()

            assertEquals(Status.UP, health.status)
            assertEquals("worker-1", health.details["workerId"])
            assertEquals("RUNNING", health.details["status"])
        }

        @Test
        fun `DRAINING worker maps to OUT_OF_SERVICE`() {
            taskWorkerState.workerId = "worker-2"
            taskWorkerState.status.set(WorkerLifecycleStatus.DRAINING)

            val indicator = config.workerHealthIndicator(taskWorkerState)
            val health = indicator.health()

            assertEquals(Status.OUT_OF_SERVICE, health.status)
            assertEquals("DRAINING", health.details["status"])
        }

        @Test
        fun `STOPPED worker maps to DOWN`() {
            taskWorkerState.workerId = "worker-3"
            taskWorkerState.status.set(WorkerLifecycleStatus.STOPPED)

            val indicator = config.workerHealthIndicator(taskWorkerState)
            val health = indicator.health()

            assertEquals(Status.DOWN, health.status)
            assertEquals("STOPPED", health.details["status"])
        }

        @Test
        fun `STARTING worker maps to UNKNOWN`() {
            // Default state is STARTING, workerId is null
            val indicator = config.workerHealthIndicator(taskWorkerState)
            val health = indicator.health()

            assertEquals(Status.UNKNOWN, health.status)
            assertEquals("not-registered", health.details["workerId"])
            assertEquals("STARTING", health.details["status"])
        }

        @Test
        fun `active task count is included in details`() {
            taskWorkerState.status.set(WorkerLifecycleStatus.RUNNING)
            taskWorkerState.incrementActiveTasks()
            taskWorkerState.incrementActiveTasks()

            val indicator = config.workerHealthIndicator(taskWorkerState)
            val health = indicator.health()

            assertEquals(2, health.details["activeTasks"])
        }
    }

    // ── Leader Health Indicator ──────────────────────────────────────────

    @Nested
    inner class LeaderHealth {

        @Test
        fun `leader health includes isLeader and leaderId`() {
            val leaderService = mockk<LeaderElectionService>()
            every { leaderService.isLeader() } returns true
            every { leaderService.getLeaderId() } returns "leader-abc"

            val indicator = config.leaderHealthIndicator(leaderService)
            val health = indicator.health()

            assertEquals(Status.UP, health.status)
            assertEquals(true, health.details["isLeader"])
            assertEquals("leader-abc", health.details["leaderId"])
        }

        @Test
        fun `leader health with null leaderId shows unknown`() {
            val leaderService = mockk<LeaderElectionService>()
            every { leaderService.isLeader() } returns false
            every { leaderService.getLeaderId() } returns null

            val indicator = config.leaderHealthIndicator(leaderService)
            val health = indicator.health()

            assertEquals(Status.UP, health.status)
            assertEquals(false, health.details["isLeader"])
            assertEquals("unknown", health.details["leaderId"])
        }
    }

    // ── Queue Health Indicator ───────────────────────────────────────────

    @Nested
    inner class QueueHealth {

        @Test
        fun `queue health returns correct counts from countGroupByStatus`() {
            val taskRepository = mockk<TaskRepository>()
            every { taskRepository.countGroupByStatus() } returns listOf(
                arrayOf(TaskStatus.PENDING, 10L),
                arrayOf(TaskStatus.LOCKED, 3L),
                arrayOf(TaskStatus.RUNNING, 5L),
                arrayOf(TaskStatus.COMPLETED, 100L)
            )

            val indicator = config.queueHealthIndicator(taskRepository)
            val health = indicator.health()

            assertEquals(Status.UP, health.status)
            assertEquals(10L, health.details["pendingTasks"])
            assertEquals(3L, health.details["lockedTasks"])
            assertEquals(5L, health.details["runningTasks"])
        }

        @Test
        fun `queue health defaults to zero for missing statuses`() {
            val taskRepository = mockk<TaskRepository>()
            every { taskRepository.countGroupByStatus() } returns emptyList()

            val indicator = config.queueHealthIndicator(taskRepository)
            val health = indicator.health()

            assertEquals(Status.UP, health.status)
            assertEquals(0L, health.details["pendingTasks"])
            assertEquals(0L, health.details["lockedTasks"])
            assertEquals(0L, health.details["runningTasks"])
        }
    }
}

