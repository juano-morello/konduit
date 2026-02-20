package dev.konduit.dsl

import dev.konduit.retry.RetryPolicy
import java.time.Duration

/**
 * DSL builder for defining a single workflow step.
 *
 * Usage:
 * ```kotlin
 * step("validate") {
 *     handler { ctx ->
 *         // step logic using ctx.input
 *         "validated"
 *     }
 *     retryPolicy {
 *         maxAttempts(3)
 *         backoff(BackoffStrategy.EXPONENTIAL)
 *         baseDelay(1000)
 *     }
 *     timeout(Duration.ofMinutes(5))
 * }
 * ```
 */
@KonduitDsl
class StepBuilder(private val name: String) {
    private var handler: ((StepContext) -> Any?)? = null
    private var retryPolicy: RetryPolicy = RetryPolicy()
    private var timeout: Duration? = null

    /**
     * Define the handler function for this step.
     * The handler receives a [StepContext] and returns the step output.
     */
    fun handler(block: (StepContext) -> Any?) {
        handler = block
    }

    /**
     * Configure the retry policy for this step using the DSL builder.
     */
    fun retryPolicy(block: RetryPolicyBuilder.() -> Unit) {
        retryPolicy = RetryPolicyBuilder().apply(block).build()
    }

    /**
     * Set the timeout for a single execution attempt of this step.
     */
    fun timeout(duration: Duration) {
        timeout = duration
    }

    fun build(): StepDefinition {
        val resolvedHandler = requireNotNull(handler) {
            "Step '$name' must have a handler defined"
        }
        return StepDefinition(
            name = name,
            handler = resolvedHandler,
            retryPolicy = retryPolicy,
            timeout = timeout
        )
    }
}

