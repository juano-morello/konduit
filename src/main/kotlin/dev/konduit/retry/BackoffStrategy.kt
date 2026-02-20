package dev.konduit.retry

/**
 * Backoff strategies for retry delay calculation.
 * See PRD §4.5 for specification.
 */
enum class BackoffStrategy {
    /** Constant delay between retries: delay = baseDelay */
    FIXED,

    /** Linearly increasing delay: delay = baseDelay × attempt */
    LINEAR,

    /** Exponentially increasing delay: delay = baseDelay × 2^(attempt-1) */
    EXPONENTIAL
}

