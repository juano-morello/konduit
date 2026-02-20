package dev.konduit.retry

/**
 * Configuration for retry behavior on step failure.
 * See PRD §4.5 for specification.
 *
 * @property maxAttempts Maximum number of attempts (including the initial attempt).
 *                       A value of 1 means no retries.
 * @property backoffStrategy The strategy used to calculate delay between retries.
 * @property baseDelayMs Base delay in milliseconds used by the backoff formula.
 * @property maxDelayMs Maximum delay cap in milliseconds. All computed delays are
 *                      clamped to this value.
 * @property jitter Whether to apply random jitter to the computed delay.
 *                  Only meaningful with EXPONENTIAL strategy.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffStrategy: BackoffStrategy = BackoffStrategy.FIXED,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 300_000L,
    val jitter: Boolean = false
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0, got $baseDelayMs" }
        require(maxDelayMs >= 0) { "maxDelayMs must be >= 0, got $maxDelayMs" }
        require(maxDelayMs >= baseDelayMs) {
            "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)"
        }
    }

    companion object {
        /** No retries — execute once and fail immediately on error. */
        val NO_RETRY = RetryPolicy(maxAttempts = 1)
    }
}

