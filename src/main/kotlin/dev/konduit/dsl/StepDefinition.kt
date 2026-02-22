package dev.konduit.dsl

import dev.konduit.retry.RetryPolicy
import java.time.Duration

/**
 * Immutable definition of a single workflow step, produced by the DSL builder.
 *
 * The handler uses [StepContext]<[Any?]> (untyped) at the definition level because
 * type parameters are erased at runtime. Type safety is enforced at the DSL level
 * via [StepBuilder]<I, O>.
 *
 * @property name Unique name of this step within its workflow.
 * @property handler The function that executes the step logic.
 *                   Takes an [UntypedStepContext] and returns the step output (or null).
 * @property retryPolicy Retry configuration for this step. Defaults to [RetryPolicy] defaults.
 * @property timeout Maximum duration for a single execution attempt of this step.
 *                   Null means use the workflow-level or global default.
 */
data class StepDefinition(
    override val name: String,
    val handler: (StepContext<Any?>) -> Any?,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val timeout: Duration? = null,
    val priority: Int = 0
) : WorkflowElement {
    /**
     * Returns a serializable representation of this step definition (without the handler)
     * suitable for JSONB storage in the workflows table.
     */
    fun toSerializable(): Map<String, Any?> = buildMap {
        put("name", name)
        put("retryPolicy", retryPolicy.toMap())
        timeout?.let { put("timeoutMs", it.toMillis()) }
    }
}

