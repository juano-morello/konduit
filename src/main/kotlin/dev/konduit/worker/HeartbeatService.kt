package dev.konduit.worker

import dev.konduit.KonduitProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled service that periodically sends heartbeat updates for this worker.
 *
 * Updates the worker's last_heartbeat timestamp and active_tasks count in the database.
 * Also detects stale workers whose heartbeats have expired and reclaims their tasks.
 *
 * The heartbeat interval is configurable via `konduit.worker.heartbeat-interval`.
 */
@Component
@ConditionalOnProperty(name = ["konduit.worker.auto-start"], havingValue = "true", matchIfMissing = true)
class HeartbeatService(
    private val workerRegistry: WorkerRegistry,
    private val taskWorkerState: TaskWorkerState,
    private val properties: KonduitProperties
) {
    private val log = LoggerFactory.getLogger(HeartbeatService::class.java)

    /**
     * Send a heartbeat update for this worker instance.
     *
     * Updates the worker's last_heartbeat and active_tasks in the database.
     * Runs at a fixed rate configured by `konduit.worker.heartbeat-interval`.
     */
    @Scheduled(fixedRateString = "\${konduit.worker.heartbeat-interval:PT10S}")
    fun sendHeartbeat() {
        val workerId = taskWorkerState.workerId ?: return
        val activeTasks = taskWorkerState.activeTaskCount

        try {
            workerRegistry.updateHeartbeat(workerId, activeTasks)
        } catch (e: Exception) {
            log.error("Failed to send heartbeat for worker {}: {}", workerId, e.message)
        }
    }

    /**
     * Detect and clean up stale workers.
     *
     * Runs at the same interval as heartbeat. Finds workers whose heartbeat
     * has expired beyond the stale threshold and reclaims their locked tasks.
     */
    @Scheduled(fixedRateString = "\${konduit.worker.heartbeat-interval:PT10S}")
    fun detectStaleWorkers() {
        try {
            val staleThreshold = properties.worker.staleThreshold
            val count = workerRegistry.detectStaleWorkers(staleThreshold)
            if (count > 0) {
                log.warn("Cleaned up {} stale worker(s)", count)
            }
        } catch (e: Exception) {
            log.error("Failed to detect stale workers: {}", e.message)
        }
    }
}

