package dev.konduit.observability

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Servlet filter that establishes correlation context for every HTTP request (PRD §8.2).
 *
 * Sets MDC fields:
 * - `correlation_id`: Unique ID per request (from X-Correlation-ID header or generated)
 *
 * Additional MDC fields (`execution_id`, `task_id`, `worker_id`, `step_name`, `workflow_name`)
 * are set by the TaskWorker during task processing, not by this filter.
 *
 * MDC is cleared after each request to prevent leaking context to other threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationFilter : Filter {

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
        const val MDC_CORRELATION_ID = "correlation_id"
        const val MDC_EXECUTION_ID = "execution_id"
        const val MDC_TASK_ID = "task_id"
        const val MDC_WORKER_ID = "worker_id"
        const val MDC_STEP_NAME = "step_name"
        const val MDC_WORKFLOW_NAME = "workflow_name"

        /**
         * Set task-processing MDC context. Called by TaskWorker before executing a task.
         */
        fun setTaskContext(
            executionId: UUID,
            taskId: UUID,
            workerId: String,
            stepName: String,
            workflowName: String
        ) {
            MDC.put(MDC_EXECUTION_ID, executionId.toString())
            MDC.put(MDC_TASK_ID, taskId.toString())
            MDC.put(MDC_WORKER_ID, workerId)
            MDC.put(MDC_STEP_NAME, stepName)
            MDC.put(MDC_WORKFLOW_NAME, workflowName)
        }

        /**
         * Clear all Konduit MDC fields. Called after task processing completes.
         */
        fun clearTaskContext() {
            MDC.remove(MDC_EXECUTION_ID)
            MDC.remove(MDC_TASK_ID)
            MDC.remove(MDC_WORKER_ID)
            MDC.remove(MDC_STEP_NAME)
            MDC.remove(MDC_WORKFLOW_NAME)
        }

        /**
         * Clear all MDC fields including correlation_id.
         */
        fun clearAll() {
            MDC.clear()
        }
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        try {
            val httpRequest = request as? HttpServletRequest
            val correlationId = httpRequest?.getHeader(CORRELATION_ID_HEADER)
                ?: UUID.randomUUID().toString()

            MDC.put(MDC_CORRELATION_ID, correlationId)

            chain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }
}

