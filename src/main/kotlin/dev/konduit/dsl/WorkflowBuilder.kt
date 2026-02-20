package dev.konduit.dsl

/**
 * DSL builder for defining a complete workflow.
 *
 * Usage:
 * ```kotlin
 * val definition = workflow("npo-onboarding") {
 *     version(1)
 *     description("Onboard a new nonprofit organization")
 *
 *     step("validate") {
 *         handler { ctx ->
 *             // validation logic
 *             ctx.input
 *         }
 *         retryPolicy {
 *             maxAttempts(3)
 *             backoff(BackoffStrategy.EXPONENTIAL)
 *             baseDelay(1000)
 *         }
 *     }
 *
 *     step("process") {
 *         handler { ctx ->
 *             // processing logic
 *             "done"
 *         }
 *     }
 * }
 * ```
 */
@KonduitDsl
class WorkflowBuilder(private val name: String) {
    private var version: Int = 1
    private var description: String? = null
    private val steps = mutableListOf<StepDefinition>()

    /**
     * Set the version number for this workflow definition.
     */
    fun version(value: Int) {
        version = value
    }

    /**
     * Set an optional description for this workflow.
     */
    fun description(value: String) {
        description = value
    }

    /**
     * Define a step in this workflow. Steps execute in the order they are defined.
     */
    fun step(name: String, block: StepBuilder.() -> Unit) {
        steps.add(StepBuilder(name).apply(block).build())
    }

    fun build(): WorkflowDefinition = WorkflowDefinition(
        name = name,
        version = version,
        description = description,
        steps = steps.toList()
    )
}

/**
 * Top-level DSL entry point for defining a workflow.
 *
 * ```kotlin
 * val myWorkflow = workflow("my-workflow") {
 *     step("step1") { handler { ctx -> "result" } }
 * }
 * ```
 */
fun workflow(name: String, block: WorkflowBuilder.() -> Unit): WorkflowDefinition =
    WorkflowBuilder(name).apply(block).build()

