package dev.konduit.examples

import dev.konduit.dsl.WorkflowDefinition
import dev.konduit.dsl.workflow
import dev.konduit.retry.BackoffStrategy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Example workflow: Data Enrichment Pipeline.
 *
 * Demonstrates sequential + parallel execution:
 * 1. Fetch raw data (sequential)
 * 2. Enrich in parallel: demographics, financials, social
 * 3. Merge all enrichment results (sequential)
 *
 * The parallel block fans out to 3 independent enrichment steps,
 * then fans in — the merge step receives all parallel outputs.
 *
 * Trigger via API:
 * ```
 * curl -X POST http://localhost:8080/api/v1/executions \
 *   -H 'Content-Type: application/json' \
 *   -d '{"workflowName": "data-enrichment", "input": {"entityId": "ORG-42", "entityType": "nonprofit"}}'
 * ```
 */
@Configuration
class DataEnrichmentWorkflow {

    private val log = LoggerFactory.getLogger(DataEnrichmentWorkflow::class.java)

    @Bean
    fun dataEnrichmentDefinition(): WorkflowDefinition = workflow("data-enrichment") {
        version(1)
        description("Fetch raw data, enrich in parallel (demographics, financials, social), then merge results")

        step("fetch-raw-data") {
            handler { ctx ->
                log.info("Fetching raw data for execution {}", ctx.executionId)
                val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()

                // Simulates fetching base data from a data store (e.g., database query latency)
                val fetchStart = System.nanoTime()
                @Suppress("unused") val entityLookup = (1..1000).sumOf { it.toLong() } // lightweight CPU work
                log.debug("Simulated data fetch in {}µs", (System.nanoTime() - fetchStart) / 1000)

                mapOf(
                    "entityId" to (input["entityId"]?.toString() ?: "UNKNOWN"),
                    "entityType" to (input["entityType"]?.toString() ?: "unknown"),
                    "rawData" to mapOf(
                        "name" to "Sample Entity",
                        "region" to "US-WEST",
                        "createdYear" to 2020
                    ),
                    "fetchedAt" to System.currentTimeMillis()
                )
            }
            retryPolicy {
                maxAttempts(3)
                backoff(BackoffStrategy.EXPONENTIAL)
                baseDelay(1000)
                jitter(true)
            }
        }

        parallel {
            step("enrich-demographics") {
                handler { ctx ->
                    log.info("Enriching demographics for execution {}", ctx.executionId)

                    // Simulates demographics API call latency (e.g., REST call to external service)
                    val demoStart = System.nanoTime()
                    @Suppress("unused") val demoHash = "demographics-enrichment".repeat(50).hashCode()
                    log.debug("Simulated demographics API call in {}µs", (System.nanoTime() - demoStart) / 1000)

                    mapOf(
                        "source" to "demographics-api",
                        "employeeCount" to 45,
                        "foundedYear" to 2018,
                        "sector" to "Healthcare",
                        "enrichedAt" to System.currentTimeMillis()
                    )
                }
                retryPolicy {
                    maxAttempts(2)
                    backoff(BackoffStrategy.FIXED)
                    baseDelay(500)
                }
            }

            step("enrich-financials") {
                handler { ctx ->
                    log.info("Enriching financials for execution {}", ctx.executionId)

                    // Simulates financials API call latency (e.g., REST call to financial data provider)
                    val finStart = System.nanoTime()
                    @Suppress("unused") val finHash = "financials-enrichment".repeat(50).hashCode()
                    log.debug("Simulated financials API call in {}µs", (System.nanoTime() - finStart) / 1000)

                    mapOf(
                        "source" to "financials-api",
                        "annualRevenue" to 2_500_000,
                        "fundingRound" to "Series A",
                        "creditScore" to 720,
                        "enrichedAt" to System.currentTimeMillis()
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

            step("enrich-social") {
                handler { ctx ->
                    log.info("Enriching social data for execution {}", ctx.executionId)

                    // Simulates social media API call latency (e.g., REST call to social data aggregator)
                    val socialStart = System.nanoTime()
                    @Suppress("unused") val socialHash = "social-enrichment".repeat(50).hashCode()
                    log.debug("Simulated social API call in {}µs", (System.nanoTime() - socialStart) / 1000)

                    mapOf(
                        "source" to "social-api",
                        "twitterFollowers" to 12_500,
                        "linkedinEmployees" to 52,
                        "glassdoorRating" to 4.2,
                        "enrichedAt" to System.currentTimeMillis()
                    )
                }
                retryPolicy {
                    maxAttempts(2)
                    backoff(BackoffStrategy.FIXED)
                    baseDelay(500)
                }
            }
        }

        step("merge-results") {
            handler { ctx ->
                log.info("Merging enrichment results for execution {}", ctx.executionId)

                // The parallel outputs are available via ctx.parallelOutputs
                val demographics = ctx.parallelOutputs["enrich-demographics"]
                val financials = ctx.parallelOutputs["enrich-financials"]
                val social = ctx.parallelOutputs["enrich-social"]

                // Simulates merge processing (e.g., data normalization and deduplication)
                val mergeStart = System.nanoTime()
                @Suppress("unused") val mergeHash = listOf(demographics, financials, social).hashCode()
                log.debug("Simulated merge processing in {}µs", (System.nanoTime() - mergeStart) / 1000)

                mapOf(
                    "entityId" to ((ctx.executionInput as? Map<*, *>)?.get("entityId") ?: "UNKNOWN"),
                    "enrichmentComplete" to true,
                    "demographics" to demographics,
                    "financials" to financials,
                    "social" to social,
                    "mergedAt" to System.currentTimeMillis(),
                    "sourcesUsed" to 3
                )
            }
        }
    }
}

