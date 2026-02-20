package dev.konduit.dsl

/**
 * DSL builder for defining a parallel block within a workflow.
 *
 * Usage:
 * ```kotlin
 * parallel {
 *     step("check-a") {
 *         handler { ctx -> checkA(ctx.input) }
 *     }
 *     step("check-b") {
 *         handler { ctx -> checkB(ctx.input) }
 *     }
 * }
 * ```
 *
 * All steps defined within a parallel block execute concurrently (fan-out).
 * The workflow advances to the next element only when all parallel steps
 * reach a terminal state (fan-in).
 */
@KonduitDsl
class ParallelBuilder(private val name: String) {
    private val steps = mutableListOf<StepDefinition>()

    /**
     * Define a step within this parallel block.
     * All steps in a parallel block execute concurrently.
     */
    fun step(name: String, block: StepBuilder.() -> Unit) {
        steps.add(StepBuilder(name).apply(block).build())
    }

    fun build(): ParallelBlock = ParallelBlock(
        name = name,
        steps = steps.toList()
    )
}

