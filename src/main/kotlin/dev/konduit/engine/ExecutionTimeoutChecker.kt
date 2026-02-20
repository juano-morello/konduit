package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.repository.ExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Checks for and transitions timed-out executions.
 *
 * Finds executions where `timeout_at IS NOT NULL AND timeout_at <= now() AND status = 'RUNNING'`
 * and transitions them to TIMED_OUT via [ExecutionStateMachine].
 *
 * This is intended to be called by the [dev.konduit.coordination.LeaderOnlyScheduler]
 * as a leader-only scheduled task.
 */
@Component
class ExecutionTimeoutChecker(
    private val executionRepository: ExecutionRepository,
    private val stateMachine: ExecutionStateMachine
) {

    private val log = LoggerFactory.getLogger(ExecutionTimeoutChecker::class.java)

    /**
     * Check for timed-out executions and transition them to TIMED_OUT.
     *
     * @return The number of executions that were timed out.
     */
    @Transactional
    fun checkTimeouts(): Int {
        val now = Instant.now()
        val timedOut = executionRepository.findTimedOutExecutions(now)

        if (timedOut.isEmpty()) {
            return 0
        }

        var count = 0
        for (execution in timedOut) {
            try {
                execution.error = "Execution timed out at ${execution.timeoutAt}"
                stateMachine.transition(execution, ExecutionStatus.TIMED_OUT)
                executionRepository.save(execution)
                count++
                log.warn(
                    "Execution {} timed out: timeoutAt={}, now={}",
                    execution.id, execution.timeoutAt, now
                )
            } catch (e: Exception) {
                log.error(
                    "Failed to transition execution {} to TIMED_OUT: {}",
                    execution.id, e.message
                )
            }
        }

        if (count > 0) {
            log.info("Timed out {} execution(s)", count)
        }

        return count
    }
}

