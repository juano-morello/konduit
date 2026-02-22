package dev.konduit.dsl

import dev.konduit.retry.RetryPolicy
import java.time.Duration

/**
 * DSL builder for defining a single workflow step.
 *
 * Type parameters:
 * - [I] The input type for the step context. Defaults to [Any?] for untyped usage.
 * - [O] The output type returned by the handler. Defaults to [Any?] for untyped usage.
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
class StepBuilder<I, O>(private val name: String) {
    private var handler: ((StepContext<I>) -> O)? = null
    private var retryPolicy: RetryPolicy = RetryPolicy()
    private var timeout: Duration? = null
    private var priority: Int = 0

    /**
     * Define the handler function for this step.
     * The handler receives a [StepContext] and returns the step output.
     */
    fun handler(block: (StepContext<I>) -> O) {
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

    /**
     * Set the priority for this step's tasks. Higher values = higher priority.
     * Valid range: 0-100. Default is 0 (normal priority).
     */
    fun priority(value: Int) {
        priority = value
    }

    fun build(): StepDefinition {
        val resolvedHandler = requireNotNull(handler) {
            "Step '$name' must have a handler defined"
        }
        @Suppress("UNCHECKED_CAST")
        val untypedHandler: (StepContext<Any?>) -> Any? = { ctx ->
            resolvedHandler(ctx as StepContext<I>)
        }
        return StepDefinition(
            name = name,
            handler = untypedHandler,
            retryPolicy = retryPolicy,
            timeout = timeout,
            priority = priority
        )
    }
}

