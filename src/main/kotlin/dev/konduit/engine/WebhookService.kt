package dev.konduit.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.konduit.api.dto.ExecutionResponse
import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.repository.ExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Asynchronous webhook delivery service.
 *
 * When an execution reaches a terminal state (COMPLETED, FAILED, CANCELLED, TIMED_OUT),
 * this service delivers a POST request to the configured callback URL with the full
 * execution response as the payload.
 *
 * Features:
 * - Async delivery via virtual threads (does not block workflow execution)
 * - 3-attempt retry with exponential backoff (0s, 1s, 5s, 25s delays)
 * - 10-second timeout per HTTP attempt
 * - SSRF protection: rejects localhost and private IP callback URLs
 * - Tracks delivery status: NONE → PENDING → DELIVERED or FAILED
 */
@Service
class WebhookService(
    private val executionRepository: ExecutionRepository,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(WebhookService::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Deliver a webhook notification for an execution that reached a terminal state.
     * Returns immediately — delivery happens asynchronously.
     */
    fun deliverWebhook(execution: ExecutionEntity) {
        val callbackUrl = execution.callbackUrl ?: return

        // Validate URL before scheduling delivery
        if (!isUrlSafe(callbackUrl)) {
            log.warn(
                "Webhook delivery skipped for execution {}: callback URL '{}' targets a private/loopback address",
                execution.id, callbackUrl
            )
            updateCallbackStatus(execution.id!!, "FAILED")
            return
        }

        // Mark as PENDING before async delivery
        updateCallbackStatus(execution.id!!, "PENDING")

        executor.submit {
            deliverWithRetry(execution, callbackUrl)
        }
    }

    private fun deliverWithRetry(execution: ExecutionEntity, callbackUrl: String) {
        val delays = listOf(0L, 1000L, 5000L, 25000L)
        val eventType = "execution.${execution.status.name.lowercase()}"

        val payload = buildPayload(execution, eventType)

        for ((attempt, delay) in delays.withIndex()) {
            if (delay > 0) {
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("Webhook delivery interrupted for execution {}", execution.id)
                    updateCallbackStatus(execution.id!!, "FAILED")
                    return
                }
            }

            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(callbackUrl))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Konduit-Webhook/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() in 200..299) {
                    updateCallbackStatus(execution.id!!, "DELIVERED")
                    log.info(
                        "Webhook delivered for execution {} (attempt {}, status {})",
                        execution.id, attempt + 1, response.statusCode()
                    )
                    return
                }

                log.warn(
                    "Webhook delivery attempt {} for execution {} returned status {}",
                    attempt + 1, execution.id, response.statusCode()
                )
            } catch (e: Exception) {
                log.warn(
                    "Webhook delivery attempt {} for execution {} failed: {}",
                    attempt + 1, execution.id, e.message
                )
            }
        }

        // All attempts exhausted
        updateCallbackStatus(execution.id!!, "FAILED")
        log.error(
            "Webhook delivery failed for execution {} after {} attempts to {}",
            execution.id, delays.size, callbackUrl
        )
    }

    private fun buildPayload(execution: ExecutionEntity, eventType: String): String {
        val executionResponse = ExecutionResponse.from(execution)
        val payload = mapOf(
            "event" to eventType,
            "timestamp" to Instant.now().toString(),
            "execution" to executionResponse
        )
        return objectMapper.writeValueAsString(payload)
    }

    /**
     * SSRF protection: validates that the callback URL does not target
     * localhost, loopback, or private network addresses.
     */
    internal fun isUrlSafe(url: String): Boolean {
        return try {
            val uri = URI.create(url)
            val host = uri.host ?: return false

            // Reject common loopback/private hostnames
            if (host.equals("localhost", ignoreCase = true) ||
                host.equals("127.0.0.1") ||
                host.equals("::1") ||
                host.equals("0.0.0.0")
            ) {
                return false
            }

            // Resolve and check IP address
            val address = InetAddress.getByName(host)
            !address.isLoopbackAddress &&
                !address.isSiteLocalAddress &&
                !address.isLinkLocalAddress &&
                !address.isAnyLocalAddress
        } catch (e: Exception) {
            log.warn("Failed to validate callback URL '{}': {}", url, e.message)
            false
        }
    }

    private fun updateCallbackStatus(executionId: java.util.UUID, status: String) {
        try {
            executionRepository.findById(executionId).ifPresent { entity ->
                entity.callbackStatus = status
                executionRepository.save(entity)
            }
        } catch (e: Exception) {
            log.error(
                "Failed to update callback status to '{}' for execution {}: {}",
                status, executionId, e.message
            )
        }
    }


}
