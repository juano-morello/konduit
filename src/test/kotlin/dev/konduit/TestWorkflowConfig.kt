package dev.konduit

import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.workflow
import dev.konduit.retry.BackoffStrategy
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Test configuration providing workflow definition beans for integration tests.
 */
@TestConfiguration
class TestWorkflowConfig {

    @Bean
    fun threeStepWorkflow(): WorkflowDefinition = workflow("three-step-test") {
        version(1)
        description("A 3-step test workflow")

        step("step-1") {
            handler { ctx ->
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()
                mapOf("step1_result" to "processed", "original" to input)
            }
            retryPolicy {
                maxAttempts(3)
                backoff(BackoffStrategy.FIXED)
                baseDelay(100)
                maxDelay(1000)
            }
        }

        step("step-2") {
            handler { ctx ->
                val prev = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()
                mapOf("step2_result" to "enriched", "from_step1" to prev)
            }
            retryPolicy {
                maxAttempts(2)
                backoff(BackoffStrategy.FIXED)
                baseDelay(100)
                maxDelay(1000)
            }
        }

        step("step-3") {
            handler { ctx ->
                val prev = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()
                mapOf("final_result" to "completed", "from_step2" to prev)
            }
        }
    }

    @Bean
    fun singleStepWorkflow(): WorkflowDefinition = workflow("single-step-test") {
        version(1)
        description("A single-step test workflow for simple tests")

        step("only-step") {
            handler { ctx -> ctx.input }
            retryPolicy {
                maxAttempts(2)
                backoff(BackoffStrategy.FIXED)
                baseDelay(100)
                maxDelay(1000)
            }
        }
    }

    @Bean
    fun parallelWorkflow(): WorkflowDefinition = workflow("parallel-test") {
        version(1)
        description("Workflow with parallel fan-out/fan-in")

        step("prepare") {
            handler { ctx -> ctx.input }
        }

        parallel {
            step("check-a") {
                handler { ctx -> mapOf("check" to "a-done") }
            }
            step("check-b") {
                handler { ctx -> mapOf("check" to "b-done") }
                retryPolicy {
                    maxAttempts(1)
                    backoff(BackoffStrategy.FIXED)
                    baseDelay(100)
                }
            }
            step("check-c") {
                handler { ctx -> mapOf("check" to "c-done") }
            }
        }

        step("combine") {
            handler { ctx -> mapOf("combined" to true) }
        }
    }

    @Bean
    fun branchWorkflow(): WorkflowDefinition = workflow("branch-test") {
        version(1)
        description("Workflow with conditional branching")

        step("evaluate") {
            handler { ctx -> ctx.input }
        }

        branch("risk") {
            on("LOW") {
                step("fast-track") {
                    handler { ctx -> mapOf("result" to "fast-tracked") }
                }
            }
            on("HIGH") {
                step("deep-review") {
                    handler { ctx -> mapOf("result" to "reviewed") }
                }
                step("escalate") {
                    handler { ctx -> mapOf("result" to "escalated") }
                }
            }
            otherwise {
                step("manual") {
                    handler { ctx -> mapOf("result" to "manual-review") }
                }
            }
        }

        step("finalize") {
            handler { ctx -> mapOf("finalized" to true) }
        }
    }
}

