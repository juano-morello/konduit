package dev.konduit.dsl

import dev.konduit.retry.RetryPolicy
import java.time.Duration

/**
 * Immutable definition of a single workflow step, produced by the DSL builder.
 *
 * @property name Unique name of this step within its workflow.
 * @property handler The suspend-compatible function that executes the step logic.
 *                   Takes a [StepContext] and returns the step output (or null).
 * @property retryPolicy Retry configuration for this step. Defaults to [RetryPolicy] defaults.
 * @property timeout Maximum duration for a single execution attempt of this step.
 *                   Null means use the workflow-level or global default.
 */
data class StepDefinition(
    override val name: String,
    val handler: (StepContext) -> Any?,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val timeout: Duration? = null
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

