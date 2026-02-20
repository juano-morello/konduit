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
}

