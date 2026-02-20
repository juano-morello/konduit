package dev.konduit.retry

/**
 * Backoff strategies for retry delay calculation.
 * See [ADR-003](docs/adr/003-retry-backoff-jitter.md) for rationale on jitter and strategy selection.
 */
enum class BackoffStrategy {
    /** Constant delay between retries: delay = baseDelay */
    FIXED,

    /** Linearly increasing delay: delay = baseDelay × attempt */
    LINEAR,

    /** Exponentially increasing delay: delay = baseDelay × 2^(attempt-1) */
    EXPONENTIAL
}

