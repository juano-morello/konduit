package dev.konduit.dsl

/**
 * Sealed interface representing an element in a workflow definition.
 *
 * A workflow is composed of an ordered list of elements, which can be:
 * - [StepDefinition]: A single sequential step
 * - [ParallelBlock]: A group of steps that execute concurrently (fan-out/fan-in)
 * - [BranchBlock]: A conditional branch point with lazy evaluation (ADR-005)
 *
 * This design allows interleaving sequential steps, parallel blocks, and branch points.
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


/**
 * A conditional branch block that evaluates the previous step's output
 * to select one of several execution paths (ADR-005).
 *
 * When the execution engine encounters a BranchBlock, it:
 * 1. Converts the previous step's output to a string for matching
 * 2. Finds the first matching branch condition from [branches]
 * 3. If no match, uses [otherwise] as fallback
 * 4. If no match and no otherwise, fails the execution
 * 5. Creates tasks only for the matched branch (lazy creation)
 *
 * Multi-step branches execute sequentially within the branch.
 * After the last step of the matched branch completes, the workflow
 * continues to the next element after the BranchBlock.
 *
 * @property name Unique name for this branch block.
 * @property branches Map of condition string → steps to execute when matched.
 * @property otherwise Optional fallback steps when no branch condition matches.
 */
data class BranchBlock(
    override val name: String,
    val branches: Map<String, List<StepDefinition>>,
    val otherwise: List<StepDefinition>? = null
) : WorkflowElement {
    init {
        require(branches.isNotEmpty()) { "Branch block '$name' must have at least one branch" }
        // Validate no empty branches
        branches.forEach { (condition, steps) ->
            require(steps.isNotEmpty()) { "Branch '$condition' in block '$name' must have at least one step" }
        }
        otherwise?.let {
            require(it.isNotEmpty()) { "Otherwise branch in block '$name' must have at least one step if defined" }
        }
        // Validate step name uniqueness across all branches + otherwise
        val allStepNames = branches.values.flatten().map { it.name } +
            (otherwise?.map { it.name } ?: emptyList())
        val duplicateNames = allStepNames.groupBy { it }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate step names in branch block '$name': $duplicateNames"
        }
    }

    /**
     * Returns all step definitions from all branches and otherwise,
     * for backward-compatible flattening.
     */
    fun allSteps(): List<StepDefinition> =
        branches.values.flatten() + (otherwise ?: emptyList())

    /**
     * Returns a serializable representation of this branch block
     * suitable for JSONB storage.
     */
    fun toSerializable(): Map<String, Any?> = buildMap {
        put("type", "branch")
        put("name", name)
        put("branches", branches.mapValues { (_, steps) -> steps.map { it.toSerializable() } })
        otherwise?.let { put("otherwise", it.map { s -> s.toSerializable() }) }
    }
}
