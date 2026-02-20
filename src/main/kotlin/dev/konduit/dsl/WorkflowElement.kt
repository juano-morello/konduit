package dev.konduit.dsl

/**
 * Sealed interface representing an element in a workflow definition.
 *
 * A workflow is composed of an ordered list of elements, which can be:
 * - [StepDefinition]: A single sequential step
 * - [ParallelBlock]: A group of steps that execute concurrently (fan-out/fan-in)
 *
 * This design allows interleaving sequential steps and parallel blocks,
 * and is extensible for future element types (e.g., BranchBlock for conditional branching).
 */
sealed interface WorkflowElement {
    /** Name identifying this element within the workflow. */
    val name: String
}

/**
 * A parallel block containing multiple steps that execute concurrently.
 *
 * When the execution engine encounters a ParallelBlock, it performs fan-out:
 * all contained steps are dispatched simultaneously. The engine waits for
 * all steps to reach a terminal state (fan-in) before advancing to the
 * next workflow element.
 *
 * @property name Unique name for this parallel block (e.g., "parallel-0").
 * @property steps The steps to execute in parallel.
 */
data class ParallelBlock(
    override val name: String,
    val steps: List<StepDefinition>
) : WorkflowElement {
    init {
        require(steps.isNotEmpty()) { "Parallel block '$name' must have at least one step" }
        val duplicateNames = steps.groupBy { it.name }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate step names in parallel block '$name': $duplicateNames"
        }
    }

    /**
     * Returns a serializable representation of this parallel block
     * suitable for JSONB storage.
     */
    fun toSerializable(): Map<String, Any?> = buildMap {
        put("type", "parallel")
        put("name", name)
        put("steps", steps.map { it.toSerializable() })
    }
}

