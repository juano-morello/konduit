package dev.konduit.retry

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure-function calculator for retry delays.
 * Implements the backoff formulas from PRD §4.5:
 *
 * - **FIXED:** delay = baseDelay
 * - **LINEAR:** delay = baseDelay × attempt
 * - **EXPONENTIAL:** delay = baseDelay × 2^(attempt-1)
 * - **EXPONENTIAL + jitter:** delay = random(0, baseDelay × 2^(attempt-1))
 *
 * All computed delays are capped at [RetryPolicy.maxDelayMs].
 */
object RetryCalculator {

    /**
     * Compute the delay in milliseconds before the next retry attempt.
     *
     * @param policy The retry policy configuration.
     * @param attempt The current attempt number (1-based). Attempt 1 is the first retry
     *                (i.e., the second execution overall).
     * @param random Random instance for jitter (injectable for testing).
     * @return Delay in milliseconds, capped at [RetryPolicy.maxDelayMs].
     * @throws IllegalArgumentException if attempt < 1.
     */
    fun computeDelay(
        policy: RetryPolicy,
        attempt: Int,
        random: Random = Random.Default
    ): Long {
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }

        val rawDelay = when (policy.backoffStrategy) {
            BackoffStrategy.FIXED -> policy.baseDelayMs

            BackoffStrategy.LINEAR -> policy.baseDelayMs * attempt

            BackoffStrategy.EXPONENTIAL -> {
                val exponentialDelay = policy.baseDelayMs * 2.0.pow((attempt - 1).toDouble())
                if (policy.jitter) {
                    // Full jitter: random value in [0, exponentialDelay)
                    (random.nextDouble() * exponentialDelay).toLong()
                } else {
                    exponentialDelay.toLong()
                }
            }
        }

        return min(rawDelay, policy.maxDelayMs)
    }

    /**
     * Check whether another retry attempt is allowed.
     *
     * @param policy The retry policy configuration.
     * @param currentAttempt The current attempt number (1-based, where 1 = first execution).
     * @return true if currentAttempt < maxAttempts (i.e., more retries are available).
     */
    fun shouldRetry(policy: RetryPolicy, currentAttempt: Int): Boolean {
        return currentAttempt < policy.maxAttempts
    }
}

