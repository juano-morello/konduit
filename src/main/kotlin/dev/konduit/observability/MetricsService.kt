package dev.konduit.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Type-safe wrapper around Micrometer's MeterRegistry for Konduit metrics.
 *
 * Provides convenient methods for recording metrics at lifecycle points.
 * When metrics are disabled (no MeterRegistry available), all methods are no-ops.
 *
 * Metric instances are cached by name+tags to avoid repeated registry lookups
 * on every call. Since tags are dynamic (workflow name, step name), a
 * ConcurrentHashMap keyed by the full tag combination is used.
 */
@Service
class MetricsService(
    private val meterRegistry: MeterRegistry? = null
) {
    private val counters = ConcurrentHashMap<String, Counter>()
    private val timers = ConcurrentHashMap<String, Timer>()

    // --- Execution metrics ---

    fun recordExecutionStarted(workflow: String) {
        counter("konduit_executions_total", "workflow", workflow, "status", "started")
            ?.increment()
    }

    fun recordExecutionCompleted(workflow: String, duration: Duration) {
        counter("konduit_executions_total", "workflow", workflow, "status", "completed")
            ?.increment()
        timer("konduit_executions_duration_seconds", "workflow", workflow, "status", "completed")
            ?.record(duration)
    }

    fun recordExecutionFailed(workflow: String, duration: Duration?) {
        counter("konduit_executions_total", "workflow", workflow, "status", "failed")
            ?.increment()
        if (duration != null) {
            timer("konduit_executions_duration_seconds", "workflow", workflow, "status", "failed")
                ?.record(duration)
        }
    }

    fun recordExecutionCancelled(workflow: String) {
        counter("konduit_executions_total", "workflow", workflow, "status", "cancelled")
            ?.increment()
    }

    // --- Task metrics ---

    fun recordTaskCompleted(workflow: String, step: String, duration: Duration?) {
        counter("konduit_tasks_total", "workflow", workflow, "step", step, "status", "completed")
            ?.increment()
        if (duration != null) {
            timer("konduit_tasks_duration_seconds", "workflow", workflow, "step", step, "status", "completed")
                ?.record(duration)
        }
    }

    fun recordTaskFailed(workflow: String, step: String, duration: Duration?) {
        counter("konduit_tasks_total", "workflow", workflow, "step", step, "status", "failed")
            ?.increment()
        if (duration != null) {
            timer("konduit_tasks_duration_seconds", "workflow", workflow, "step", step, "status", "failed")
                ?.record(duration)
        }
    }

    fun recordTaskRetry(workflow: String, step: String) {
        counter("konduit_tasks_retry_total", "workflow", workflow, "step", step)
            ?.increment()
    }

    fun recordDeadLetter(workflow: String, step: String) {
        counter("konduit_dead_letters_total", "workflow", workflow, "step", step)
            ?.increment()
    }

    // --- Queue metrics ---

    fun recordTaskAcquisitionDuration(duration: Duration) {
        timer("konduit_task_acquisition_duration_seconds")
            ?.record(duration)
    }

    // --- Timer helpers ---

    fun startTimer(): Timer.Sample? {
        return meterRegistry?.let { Timer.start(it) }
    }

    fun stopTimer(sample: Timer.Sample?, timerName: String, vararg tags: String) {
        if (sample != null && meterRegistry != null) {
            sample.stop(requireNotNull(timer(timerName, *tags)) { "Timer '$timerName' must not be null when meterRegistry is available" })
        }
    }

    // --- Internal cached lookups ---

    private fun counter(name: String, vararg tags: String): Counter? {
        val registry = meterRegistry ?: return null
        val key = cacheKey(name, *tags)
        return counters.computeIfAbsent(key) { registry.counter(name, *tags) }
    }

    private fun timer(name: String, vararg tags: String): Timer? {
        val registry = meterRegistry ?: return null
        val key = cacheKey(name, *tags)
        return timers.computeIfAbsent(key) { registry.timer(name, *tags) }
    }

    private fun cacheKey(name: String, vararg tags: String): String {
        return if (tags.isEmpty()) name else "$name:${tags.joinToString(",")}"
    }
}

