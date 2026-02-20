package dev.konduit.dsl

import java.util.UUID

/**
 * Context object passed to step handlers during execution.
 * See PRD §4.4 for specification.
 *
 * @property executionId Unique identifier for the current workflow execution.
 * @property input The input data for this specific step (output of the previous step,
 *                 or the workflow input for the first step).
 * @property previousOutput The output of the immediately preceding step, or null for the first step.
 * @property executionInput The original input provided when the workflow execution was triggered.
 *                          Always available regardless of which step is executing.
 * @property attempt Current attempt number (1-based). 1 = first execution, 2 = first retry, etc.
 * @property stepName Name of the current step being executed.
 * @property workflowName Name of the workflow this step belongs to.
 * @property metadata Mutable map for passing arbitrary data between steps within an execution.
 */
data class StepContext(
    val executionId: UUID,
    val input: Any?,
    val previousOutput: Any?,
    val executionInput: Any?,
    val attempt: Int,
    val stepName: String,
    val workflowName: String,
    val metadata: MutableMap<String, Any?> = mutableMapOf()
) {
    /**
     * Placeholder for Phase 2 parallel execution outputs.
     * Will contain outputs from all parallel branches keyed by step name.
     */
    val parallelOutputs: Map<String, Any?>
        get() = emptyMap()
}

