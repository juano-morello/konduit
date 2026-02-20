package dev.konduit.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Type-safe wrapper around Micrometer's MeterRegistry for Konduit metrics.
 *
 * Provides convenient methods for recording metrics at lifecycle points.
 * When metrics are disabled (no MeterRegistry available), all methods are no-ops.
 */
@Service
class MetricsService(
    private val meterRegistry: MeterRegistry? = null
) {

    // --- Execution metrics ---

    fun recordExecutionStarted(workflow: String) {
        meterRegistry?.counter("konduit_executions_total", "workflow", workflow, "status", "started")
            ?.increment()
    }

    fun recordExecutionCompleted(workflow: String, duration: Duration) {
        meterRegistry?.counter("konduit_executions_total", "workflow", workflow, "status", "completed")
            ?.increment()
        meterRegistry?.timer("konduit_executions_duration_seconds", "workflow", workflow, "status", "completed")
            ?.record(duration)
    }

    fun recordExecutionFailed(workflow: String, duration: Duration?) {
        meterRegistry?.counter("konduit_executions_total", "workflow", workflow, "status", "failed")
            ?.increment()
        if (duration != null) {
            meterRegistry?.timer("konduit_executions_duration_seconds", "workflow", workflow, "status", "failed")
                ?.record(duration)
        }
    }

    fun recordExecutionCancelled(workflow: String) {
        meterRegistry?.counter("konduit_executions_total", "workflow", workflow, "status", "cancelled")
            ?.increment()
    }

    // --- Task metrics ---

    fun recordTaskCompleted(workflow: String, step: String, duration: Duration?) {
        meterRegistry?.counter("konduit_tasks_total", "workflow", workflow, "step", step, "status", "completed")
            ?.increment()
        if (duration != null) {
            meterRegistry?.timer("konduit_tasks_duration_seconds", "workflow", workflow, "step", step, "status", "completed")
                ?.record(duration)
        }
    }

    fun recordTaskFailed(workflow: String, step: String, duration: Duration?) {
        meterRegistry?.counter("konduit_tasks_total", "workflow", workflow, "step", step, "status", "failed")
            ?.increment()
        if (duration != null) {
            meterRegistry?.timer("konduit_tasks_duration_seconds", "workflow", workflow, "step", step, "status", "failed")
                ?.record(duration)
        }
    }

    fun recordTaskRetry(workflow: String, step: String) {
        meterRegistry?.counter("konduit_tasks_retry_total", "workflow", workflow, "step", step)
            ?.increment()
    }

    fun recordDeadLetter(workflow: String, step: String) {
        meterRegistry?.counter("konduit_dead_letters_total", "workflow", workflow, "step", step)
            ?.increment()
    }

    // --- Queue metrics ---

    fun recordTaskAcquisitionDuration(duration: Duration) {
        meterRegistry?.timer("konduit_task_acquisition_duration_seconds")
            ?.record(duration)
    }

    // --- Timer helpers ---

    fun startTimer(): Timer.Sample? {
        return meterRegistry?.let { Timer.start(it) }
    }

    fun stopTimer(sample: Timer.Sample?, timerName: String, vararg tags: String) {
        if (sample != null && meterRegistry != null) {
            sample.stop(meterRegistry.timer(timerName, *tags))
        }
    }
}

