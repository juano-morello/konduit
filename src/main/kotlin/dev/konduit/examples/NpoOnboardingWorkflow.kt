package dev.konduit.examples

import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.workflow
import dev.konduit.retry.BackoffStrategy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Example workflow: NPO (Non-Profit Organization) Onboarding Pipeline.
 *
 * A sequential 4-step workflow that simulates onboarding a new nonprofit:
 * 1. Validate the organization data
 * 2. Run a KYC (Know Your Customer) check
 * 3. Provision the account
 * 4. Send a welcome notification
 *
 * All steps use mock/stub logic with simulated latency for demonstration.
 *
 * Trigger via API:
 * ```
 * curl -X POST http://localhost:8080/api/v1/executions \
 *   -H 'Content-Type: application/json' \
 *   -d '{"workflowName": "npo-onboarding", "input": {"orgName": "Helping Hands Foundation", "ein": "12-3456789"}}'
 * ```
 */
@Configuration
class NpoOnboardingWorkflow {

    private val log = LoggerFactory.getLogger(NpoOnboardingWorkflow::class.java)

    @Bean
    fun npoOnboardingDefinition(): WorkflowDefinition = workflow("npo-onboarding") {
        version(1)
        description("Onboard a new non-profit organization through validation, KYC, provisioning, and notification")

        step("validate") {
            handler { ctx ->
                log.info("Validating organization data for execution {}", ctx.executionId)
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()
                val orgName = input["orgName"]?.toString() ?: "Unknown Org"

                // Simulate validation latency
                Thread.sleep(100)

                mapOf(
                    "orgName" to orgName,
                    "ein" to (input["ein"]?.toString() ?: "00-0000000"),
                    "validated" to true,
                    "validatedAt" to System.currentTimeMillis()
                )
            }
            retryPolicy {
                maxAttempts(2)
                backoff(BackoffStrategy.FIXED)
                baseDelay(500)
            }
        }

        step("kyc-check") {
            handler { ctx ->
                log.info("Running KYC check for execution {}", ctx.executionId)
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()

                // Simulate external KYC service call
                Thread.sleep(200)

                mapOf(
                    "orgName" to (input["orgName"] ?: "Unknown"),
                    "kycStatus" to "PASSED",
                    "riskScore" to 15,
                    "checkedAt" to System.currentTimeMillis()
                )
            }
            retryPolicy {
                maxAttempts(3)
                backoff(BackoffStrategy.EXPONENTIAL)
                baseDelay(1000)
                maxDelay(10_000)
                jitter(true)
            }
        }

        step("provision-account") {
            handler { ctx ->
                log.info("Provisioning account for execution {}", ctx.executionId)
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()

                // Simulate account creation
                Thread.sleep(150)

                val accountId = "NPO-${System.currentTimeMillis()}"
                mapOf(
                    "accountId" to accountId,
                    "orgName" to (input["orgName"] ?: "Unknown"),
                    "tier" to "standard",
                    "provisionedAt" to System.currentTimeMillis()
                )
            }
            retryPolicy {
                maxAttempts(3)
                backoff(BackoffStrategy.EXPONENTIAL)
                baseDelay(2000)
                jitter(true)
            }
        }

        step("send-welcome") {
            handler { ctx ->
                log.info("Sending welcome notification for execution {}", ctx.executionId)
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()

                // Simulate sending notification
                Thread.sleep(50)

                mapOf(
                    "accountId" to (input["accountId"] ?: "unknown"),
                    "orgName" to (input["orgName"] ?: "Unknown"),
                    "notificationType" to "email",
                    "sentAt" to System.currentTimeMillis(),
                    "onboardingComplete" to true
                )
            }
        }
    }
}

