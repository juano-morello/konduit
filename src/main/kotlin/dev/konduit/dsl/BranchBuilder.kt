package dev.konduit.dsl

/**
 * DSL builder for defining a conditional branch block within a workflow.
 *
 * Usage:
 * ```kotlin
 * branch("evaluate-risk") {
 *     on("LOW") {
 *         step("fast-track") { handler { ctx -> "approved" } }
 *     }
 *     on("HIGH") {
 *         step("deep-review") { handler { ctx -> "reviewed" } }
 *         step("escalate") { handler { ctx -> "escalated" } }
 *     }
 *     otherwise {
 *         step("manual-review") { handler { ctx -> "manual" } }
 *     }
 * }
 * ```
 *
 * The branch evaluator matches the previous step's output (as a string)
 * against the `on()` condition keys. Only the matched branch's tasks
 * are created (lazy evaluation per ADR-005).
 */
@KonduitDsl
class BranchBuilder(private val name: String) {
    private val branches = mutableMapOf<String, List<StepDefinition>>()
    private var otherwiseBranch: List<StepDefinition>? = null

    /**
     * Define a conditional branch. The [condition] is matched against
     * the string representation of the previous step's output.
     */
    fun on(condition: String, block: BranchStepsBuilder.() -> Unit) {
        require(condition.isNotBlank()) { "Branch condition must not be blank" }
        require(condition !in branches) { "Duplicate branch condition '$condition' in branch block '$name'" }
        val steps = BranchStepsBuilder().apply(block).build()
        branches[condition] = steps
    }

    /**
     * Define the fallback branch, used when no `on()` condition matches.
     */
    fun otherwise(block: BranchStepsBuilder.() -> Unit) {
        require(otherwiseBranch == null) { "Only one 'otherwise' block is allowed in branch '$name'" }
        otherwiseBranch = BranchStepsBuilder().apply(block).build()
    }

    fun build(): BranchBlock = BranchBlock(name, branches.toMap(), otherwiseBranch)
}

/**
 * Builder for defining steps within a branch condition.
 * Steps within a branch execute sequentially.
 */
@KonduitDsl
class BranchStepsBuilder {
    private val steps = mutableListOf<StepDefinition>()

    /**
     * Define a step within this branch. Steps execute sequentially
     * within the branch.
     */
    fun step(name: String, block: StepBuilder.() -> Unit) {
        steps.add(StepBuilder(name).apply(block).build())
    }

    fun build(): List<StepDefinition> = steps.toList()
}

