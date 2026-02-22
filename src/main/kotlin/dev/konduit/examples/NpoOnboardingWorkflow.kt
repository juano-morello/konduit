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

                // Simulates validation latency (e.g., schema validation + business rules check)
                val valStart = System.nanoTime()
                @Suppress("unused") val validationHash = orgName.hashCode() + (input["ein"]?.hashCode() ?: 0)
                log.debug("Simulated validation in {}µs", (System.nanoTime() - valStart) / 1000)

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

                // Simulates external KYC service call latency (e.g., REST call to compliance provider)
                val kycStart = System.nanoTime()
                @Suppress("unused") val kycHash = input.hashCode()
                log.debug("Simulated KYC check in {}µs", (System.nanoTime() - kycStart) / 1000)

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

                // Simulates account creation latency (e.g., provisioning in external system)
                val provStart = System.nanoTime()
                @Suppress("unused") val provHash = input.hashCode()
                log.debug("Simulated account provisioning in {}µs", (System.nanoTime() - provStart) / 1000)

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

                // Simulates sending notification latency (e.g., email/SMS API call)
                val notifStart = System.nanoTime()
                @Suppress("unused") val notifHash = input.hashCode()
                log.debug("Simulated notification send in {}µs", (System.nanoTime() - notifStart) / 1000)

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

