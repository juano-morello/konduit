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
    private val elements = mutableListOf<WorkflowElement>()
    private var parallelCounter = 0

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
     * Define a sequential step in this workflow. Steps execute in the order they are defined.
     */
    fun step(name: String, block: StepBuilder.() -> Unit) {
        elements.add(StepBuilder(name).apply(block).build())
    }

    /**
     * Define a parallel block. All steps within the block execute concurrently (fan-out).
     * The workflow advances to the next element only when all parallel steps complete (fan-in).
     *
     * ```kotlin
     * parallel {
     *     step("check-a") { handler { ctx -> checkA(ctx.input) } }
     *     step("check-b") { handler { ctx -> checkB(ctx.input) } }
     * }
     * ```
     */
    fun parallel(block: ParallelBuilder.() -> Unit) {
        val builder = ParallelBuilder("parallel-${parallelCounter++}")
        builder.apply(block)
        elements.add(builder.build())
    }

    /**
     * Define a conditional branch block. The previous step's output is matched
     * against `on()` conditions to select which branch to execute.
     *
     * ```kotlin
     * branch("evaluate-risk") {
     *     on("LOW") { step("fast-track") { handler { ctx -> "done" } } }
     *     on("HIGH") { step("deep-review") { handler { ctx -> "reviewed" } } }
     *     otherwise { step("manual") { handler { ctx -> "manual" } } }
     * }
     * ```
     */
    fun branch(name: String, block: BranchBuilder.() -> Unit) {
        elements.add(BranchBuilder(name).apply(block).build())
    }

    fun build(): WorkflowDefinition = WorkflowDefinition(
        name = name,
        version = version,
        description = description,
        elements = elements.toList()
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

