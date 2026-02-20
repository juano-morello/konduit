package dev.konduit.worker

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared state holder for the TaskWorker.
 *
 * Extracted as a separate component to allow HeartbeatService to read
 * worker state without a circular dependency on TaskWorker.
 */
@Component
class TaskWorkerState {

    /** The unique worker ID, set during startup. Null before registration. */
    @Volatile
    var workerId: String? = null

    /** Current number of tasks being executed by this worker. */
    private val _activeTasks = AtomicInteger(0)

    /** Current worker status for controlling the poll loop. */
    val status = AtomicReference(WorkerLifecycleStatus.STARTING)

    /** Get the current active task count. */
    val activeTaskCount: Int
        get() = _activeTasks.get()

    fun incrementActiveTasks(): Int = _activeTasks.incrementAndGet()

    fun decrementActiveTasks(): Int = _activeTasks.decrementAndGet()
}

/**
 * Internal lifecycle status for the worker poll loop.
 * Distinct from WorkerStatus (the DB enum) — this tracks the in-memory state.
 */
enum class WorkerLifecycleStatus {
    STARTING,
    RUNNING,
    DRAINING,
    STOPPED
}

