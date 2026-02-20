package dev.konduit.dsl

/**
 * Immutable definition of a complete workflow, produced by the DSL builder.
 *
 * @property name Unique name identifying this workflow.
 * @property version Version number for this workflow definition. Defaults to 1.
 * @property description Optional human-readable description.
 * @property steps Ordered list of step definitions that make up this workflow.
 */
data class WorkflowDefinition(
    val name: String,
    val version: Int = 1,
    val description: String? = null,
    val steps: List<StepDefinition>
) {
    init {
        require(name.isNotBlank()) { "Workflow name must not be blank" }
        require(version >= 1) { "Workflow version must be >= 1, got $version" }
        require(steps.isNotEmpty()) { "Workflow must have at least one step" }

        // Validate step name uniqueness within the workflow
        val duplicateNames = steps.groupBy { it.name }
            .filter { it.value.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate step names in workflow '$name': $duplicateNames"
        }
    }

    /**
     * Returns a serializable representation of the step definitions
     * suitable for JSONB storage in the workflows table.
     */
    fun toSerializableSteps(): List<Map<String, Any?>> =
        steps.map { it.toSerializable() }

    /**
     * Look up a step definition by name.
     * @throws IllegalArgumentException if the step is not found.
     */
    fun getStep(stepName: String): StepDefinition =
        steps.find { it.name == stepName }
            ?: throw IllegalArgumentException(
                "Step '$stepName' not found in workflow '$name'. Available steps: ${steps.map { it.name }}"
            )
}

