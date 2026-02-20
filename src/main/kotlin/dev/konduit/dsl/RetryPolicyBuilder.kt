package dev.konduit.dsl

import dev.konduit.retry.BackoffStrategy
import dev.konduit.retry.RetryPolicy

/**
 * DSL builder for configuring retry policies on steps.
 *
 * Usage:
 * ```kotlin
 * retryPolicy {
 *     maxAttempts(5)
 *     backoff(BackoffStrategy.EXPONENTIAL)
 *     baseDelay(1000)
 *     maxDelay(60_000)
 *     jitter(true)
 * }
 * ```
 */
@KonduitDsl
class RetryPolicyBuilder {
    private var maxAttempts: Int = 3
    private var backoffStrategy: BackoffStrategy = BackoffStrategy.FIXED
    private var baseDelayMs: Long = 1000L
    private var maxDelayMs: Long = 300_000L
    private var jitter: Boolean = false

    fun maxAttempts(value: Int) {
        maxAttempts = value
    }

    fun backoff(strategy: BackoffStrategy) {
        backoffStrategy = strategy
    }

    fun baseDelay(ms: Long) {
        baseDelayMs = ms
    }

    fun maxDelay(ms: Long) {
        maxDelayMs = ms
    }

    fun jitter(enabled: Boolean) {
        jitter = enabled
    }

    fun build(): RetryPolicy = RetryPolicy(
        maxAttempts = maxAttempts,
        backoffStrategy = backoffStrategy,
        baseDelayMs = baseDelayMs,
        maxDelayMs = maxDelayMs,
        jitter = jitter
    )
}

