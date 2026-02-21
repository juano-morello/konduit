package dev.konduit.coordination

import dev.konduit.engine.ExecutionTimeoutChecker
import dev.konduit.engine.RetentionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Wraps all leader-only scheduled jobs.
 *
 * Only the leader instance runs these background maintenance tasks:
 * - Execution timeout checking
 * - Orphan reclamation (when available via Worker Management)
 * - Stale worker detection (when available via Worker Management)
 *
 * Non-leader instances skip these jobs silently.
 * When Redis is unavailable (NoOpLeaderElection), all instances run these
 * jobs — this is safe because they are idempotent.
 */
@Component
class LeaderOnlyScheduler(
    private val leaderElection: LeaderElectionService,
    private val executionTimeoutChecker: ExecutionTimeoutChecker,
    private val retentionService: RetentionService
) {

    private val log = LoggerFactory.getLogger(LeaderOnlyScheduler::class.java)

    /**
     * Periodically check for timed-out executions.
     * Only runs on the leader instance.
     */
    @Scheduled(fixedRateString = "\${konduit.execution.timeout-check-interval:30000}")
    fun checkExecutionTimeouts() {
        if (!leaderElection.isLeader()) {
            return
        }

        try {
            val count = executionTimeoutChecker.checkTimeouts()
            if (count > 0) {
                log.info("Leader: timed out {} execution(s)", count)
            }
        } catch (e: Exception) {
            log.error("Leader: execution timeout check failed: {}", e.message, e)
        }
    }

    /**
     * Periodically clean up expired terminal-state executions.
     * Only runs on the leader instance.
     */
    @Scheduled(fixedRateString = "\${konduit.retention.cleanup-interval:21600000}")
    fun cleanupExpiredExecutions() {
        if (!leaderElection.isLeader()) {
            return
        }

        try {
            val count = retentionService.cleanupExpiredExecutions()
            if (count > 0) {
                log.info("Leader: retention cleanup deleted {} execution(s)", count)
            }
        } catch (e: Exception) {
            log.error("Leader: retention cleanup failed: {}", e.message, e)
        }
    }
}

