package dev.konduit.dsl

/**
 * Immutable definition of a complete workflow, produced by the DSL builder.
 *
 * @property name Unique name identifying this workflow.
 * @property version Version number for this workflow definition. Defaults to 1.
 * @property description Optional human-readable description.
 * @property elements Ordered list of workflow elements (steps and parallel blocks).
 */
data class WorkflowDefinition(
    val name: String,
    val version: Int = 1,
    val description: String? = null,
    val elements: List<WorkflowElement>
) {
    /**
     * Backward-compatible flattened list of all step definitions.
     * Sequential steps are included directly; parallel block steps are flattened in.
     */
    val steps: List<StepDefinition>
        get() = elements.flatMap { element ->
            when (element) {
                is StepDefinition -> listOf(element)
                is ParallelBlock -> element.steps
                is BranchBlock -> element.allSteps()
            }
        }

    init {
        require(name.isNotBlank()) { "Workflow name must not be blank" }
        require(version >= 1) { "Workflow version must be >= 1, got $version" }
        require(elements.isNotEmpty()) { "Workflow must have at least one step" }

        // Validate step name uniqueness across all elements (including inside parallel blocks)
        val allStepNames = steps.map { it.name }
        val duplicateNames = allStepNames.groupBy { it }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate step names in workflow '$name': $duplicateNames"
        }

        // Validate element name uniqueness (parallel block names + top-level step names)
        val elementNames = elements.map { it.name }
        val duplicateElementNames = elementNames.groupBy { it }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateElementNames.isEmpty()) {
            "Duplicate element names in workflow '$name': $duplicateElementNames"
        }
    }

    /**
     * Returns a serializable representation of the workflow elements
     * suitable for JSONB storage in the workflows table.
     */
    fun toSerializableSteps(): List<Map<String, Any?>> =
        elements.map { element ->
            when (element) {
                is StepDefinition -> element.toSerializable()
                is ParallelBlock -> element.toSerializable()
                is BranchBlock -> element.toSerializable()
            }
        }

    /**
     * Look up a step definition by name (searches all elements including parallel blocks).
     * @throws IllegalArgumentException if the step is not found.
     */
    fun getStep(stepName: String): StepDefinition =
        steps.find { it.name == stepName }
            ?: throw IllegalArgumentException(
                "Step '$stepName' not found in workflow '$name'. Available steps: ${steps.map { it.name }}"
            )
}

