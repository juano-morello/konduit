package dev.konduit.observability

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Servlet filter that logs HTTP request/response details for every request.
 *
 * Logs: HTTP method, URI, response status code, and request duration in milliseconds.
 * Uses SLF4J with MDC so that the correlation ID (set by [CorrelationFilter]) is included
 * in every log line.
 *
 * Runs after [CorrelationFilter] (HIGHEST_PRECEDENCE) so that the MDC correlation_id
 * is already available when this filter logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingFilter : Filter {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    companion object {
        private const val MDC_HTTP_METHOD = "http_method"
        private const val MDC_REQUEST_URI = "request_uri"
        private const val MDC_HTTP_STATUS = "http_status"
        private const val MDC_DURATION_MS = "duration_ms"
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as? HttpServletRequest
        val httpResponse = response as? HttpServletResponse

        if (httpRequest == null || httpResponse == null) {
            chain.doFilter(request, response)
            return
        }

        val method = httpRequest.method
        val uri = httpRequest.requestURI
        val startTime = System.nanoTime()

        MDC.put(MDC_HTTP_METHOD, method)
        MDC.put(MDC_REQUEST_URI, uri)

        try {
            chain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            val status = httpResponse.status

            MDC.put(MDC_HTTP_STATUS, status.toString())
            MDC.put(MDC_DURATION_MS, durationMs.toString())

            log.info(
                "HTTP {} {} — status={}, duration={}ms",
                method, uri, status, durationMs
            )

            MDC.remove(MDC_HTTP_METHOD)
            MDC.remove(MDC_REQUEST_URI)
            MDC.remove(MDC_HTTP_STATUS)
            MDC.remove(MDC_DURATION_MS)
        }
    }
}

