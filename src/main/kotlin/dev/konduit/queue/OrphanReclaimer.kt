package dev.konduit.queue

import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Scheduled service that reclaims orphaned tasks (see [ADR-007](docs/adr/007-orphan-reclamation.md)).
 *
 * An orphaned task is one with status=LOCKED whose lock_timeout_at has expired.
 * This happens when a worker crashes or becomes unresponsive while holding a task lock.
 *
 * The reclaimer resets orphaned tasks to PENDING so they can be picked up by
 * another worker. The attempt counter is NOT incremented because a lock timeout
 * is not a task failure — the task may not have started executing.
 *
 * Uses an atomic UPDATE with a WHERE status='LOCKED' guard so that if a slow worker
 * completes a task between scheduling and execution, the UPDATE is a no-op for that
 * row — eliminating the read-modify-write race condition.
 */
@Component
@ConditionalOnProperty(name = ["konduit.worker.auto-start"], havingValue = "true", matchIfMissing = true)
class OrphanReclaimer(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(OrphanReclaimer::class.java)

    /**
     * Atomically reclaim orphaned tasks.
     *
     * Runs at a fixed rate configured by `konduit.queue.reaper-interval`.
     * Uses a single atomic UPDATE WHERE status='LOCKED' AND lock_timeout_at <= now
     * to reset orphaned tasks to PENDING. The status guard ensures tasks that have
     * transitioned away from LOCKED (e.g., completed by a slow worker) are not affected.
     */
    @Scheduled(fixedRateString = "\${konduit.queue.reaper-interval:PT30S}")
    @Transactional
    fun reclaimOrphanedTasks() {
        try {
            val now = Instant.now()
            val reclaimedCount = taskRepository.reclaimOrphanedTasks(now)

            if (reclaimedCount > 0) {
                log.warn("Reclaimed {} orphaned task(s)", reclaimedCount)
            }
        } catch (e: Exception) {
            log.error("Error reclaiming orphaned tasks: {}", e.message, e)
        }
    }
}

