package dev.konduit.coordination

import dev.konduit.IntegrationTestBase
import dev.konduit.TestWorkflowConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * Tests that when Redis is disabled (konduit.redis.enabled=false in test profile),
 * the NoOp implementations are used for TaskNotifier and LeaderElectionService.
 *
 * The test profile already sets konduit.redis.enabled=false and excludes
 * Redis auto-configuration, so these tests verify the correct beans are wired.
 */
@Import(TestWorkflowConfig::class)
class RedisUnavailabilityTest : IntegrationTestBase() {

    @Autowired
    lateinit var taskNotifier: TaskNotifier

    @Autowired
    lateinit var leaderElectionService: LeaderElectionService

    @Test
    fun `NoOpTaskNotifier is used when Redis is disabled`() {
        assertInstanceOf(NoOpTaskNotifier::class.java, taskNotifier,
            "TaskNotifier should be NoOpTaskNotifier when Redis is disabled")
    }

    @Test
    fun `NoOpLeaderElection is used when Redis is disabled`() {
        assertInstanceOf(NoOpLeaderElection::class.java, leaderElectionService,
            "LeaderElectionService should be NoOpLeaderElection when Redis is disabled")
    }

    @Test
    fun `NoOpLeaderElection always reports as leader`() {
        assertTrue(leaderElectionService.isLeader(),
            "NoOpLeaderElection should always report as leader")
    }

    @Test
    fun `NoOpLeaderElection returns local as leader ID`() {
        assertEquals("local", leaderElectionService.getLeaderId(),
            "NoOpLeaderElection should return 'local' as leader ID")
    }

    @Test
    fun `NoOpTaskNotifier notifyTasksAvailable does not throw`() {
        // Should be a no-op — just verify it doesn't throw
        assertDoesNotThrow { taskNotifier.notifyTasksAvailable() }
    }

    @Test
    fun `application context loads successfully without Redis`() {
        // If we got here, the Spring context loaded successfully without Redis.
        // This test documents that the app can start without Redis.
        assertNotNull(taskNotifier)
        assertNotNull(leaderElectionService)
    }
}

