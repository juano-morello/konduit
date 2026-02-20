package dev.konduit.queue

import java.time.Instant

/**
 * Record of a single task execution attempt, used to build error history
 * for dead-lettered tasks. Stored as JSONB in the dead_letters table.
 *
 * @property attempt The attempt number (1-based).
 * @property error The error message from this attempt.
 * @property timestamp When this attempt occurred.
 */
data class AttemptRecord(
    val attempt: Int,
    val error: String,
    val timestamp: Instant = Instant.now()
) {
    /**
     * Convert to a map suitable for JSONB serialization.
     */
    fun toMap(): Map<String, Any> = mapOf(
        "attempt" to attempt,
        "error" to error,
        "timestamp" to timestamp.toString()
    )
}

