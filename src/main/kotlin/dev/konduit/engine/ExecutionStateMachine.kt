package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Enforces valid state transitions for workflow executions (PRD §3.3.1).
 *
 * Valid transitions:
 * - PENDING → RUNNING, CANCELLED
 * - RUNNING → COMPLETED, FAILED, TIMED_OUT, CANCELLED
 *
 * Terminal states (COMPLETED, FAILED, TIMED_OUT, CANCELLED) have no outgoing transitions.
 */
@Component
class ExecutionStateMachine {

    private val log = LoggerFactory.getLogger(ExecutionStateMachine::class.java)

    companion object {
        /** Map of valid state transitions: source → set of allowed targets. */
        val VALID_TRANSITIONS: Map<ExecutionStatus, Set<ExecutionStatus>> = mapOf(
            ExecutionStatus.PENDING to setOf(
                ExecutionStatus.RUNNING,
                ExecutionStatus.CANCELLED
            ),
            ExecutionStatus.RUNNING to setOf(
                ExecutionStatus.COMPLETED,
                ExecutionStatus.FAILED,
                ExecutionStatus.TIMED_OUT,
                ExecutionStatus.CANCELLED
            ),
            // Terminal states — no outgoing transitions
            ExecutionStatus.COMPLETED to emptySet(),
            ExecutionStatus.FAILED to emptySet(),
            ExecutionStatus.TIMED_OUT to emptySet(),
            ExecutionStatus.CANCELLED to emptySet()
        )

        /** States that represent a finished execution. */
        val TERMINAL_STATES: Set<ExecutionStatus> = setOf(
            ExecutionStatus.COMPLETED,
            ExecutionStatus.FAILED,
            ExecutionStatus.TIMED_OUT,
            ExecutionStatus.CANCELLED
        )
    }

    /**
     * Transition an execution to a new status.
     *
     * Validates the transition is allowed, updates the status, and sets
     * appropriate timestamps:
     * - RUNNING: sets [ExecutionEntity.startedAt]
     * - Terminal states: sets [ExecutionEntity.completedAt]
     *
     * @param execution The execution entity to transition.
     * @param newStatus The target status.
     * @return The updated execution entity.
     * @throws IllegalStateException if the transition is not valid.
     */
    fun transition(execution: ExecutionEntity, newStatus: ExecutionStatus): ExecutionEntity {
        val currentStatus = execution.status
        val allowedTargets = VALID_TRANSITIONS[currentStatus] ?: emptySet()

        if (newStatus !in allowedTargets) {
            throw IllegalStateException(
                "Invalid execution state transition: $currentStatus → $newStatus " +
                    "for execution ${execution.id}. Allowed transitions from $currentStatus: $allowedTargets"
            )
        }

        log.info(
            "Execution {} transitioning: {} → {}",
            execution.id, currentStatus, newStatus
        )

        val now = Instant.now()
        execution.status = newStatus

        // Set timestamps based on the target state
        when (newStatus) {
            ExecutionStatus.RUNNING -> {
                execution.startedAt = now
            }
            in TERMINAL_STATES -> {
                execution.completedAt = now
            }
            else -> { /* no timestamp changes for other states */ }
        }

        return execution
    }

    /**
     * Check if a transition from [currentStatus] to [newStatus] is valid.
     */
    fun isValidTransition(currentStatus: ExecutionStatus, newStatus: ExecutionStatus): Boolean {
        return newStatus in (VALID_TRANSITIONS[currentStatus] ?: emptySet())
    }

    /**
     * Check if the given status is a terminal (final) state.
     */
    fun isTerminal(status: ExecutionStatus): Boolean {
        return status in TERMINAL_STATES
    }
}

