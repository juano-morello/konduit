package dev.konduit.queue

import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Scheduled service that reclaims orphaned tasks (PRD §6.3).
 *
 * An orphaned task is one with status=LOCKED whose lock_timeout_at has expired.
 * This happens when a worker crashes or becomes unresponsive while holding a task lock.
 *
 * The reclaimer resets orphaned tasks to PENDING so they can be picked up by
 * another worker. Per PRD §6.3, the attempt counter is NOT incremented because
 * a lock timeout is not a task failure — the task may not have started executing.
 *
 * Note: This currently runs on all instances (idempotent thanks to SKIP LOCKED
 * in the findOrphanedTasks query). In Phase 3 with Redis, this should run only
 * on the leader instance.
 */
@Component
@ConditionalOnProperty(name = ["konduit.worker.auto-start"], havingValue = "true", matchIfMissing = true)
class OrphanReclaimer(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(OrphanReclaimer::class.java)

    /**
     * Find and reclaim orphaned tasks.
     *
     * Runs at a fixed rate configured by `konduit.queue.reaper-interval`.
     * Uses SKIP LOCKED to avoid contention with other instances.
     */
    @Scheduled(fixedRateString = "\${konduit.queue.reaper-interval:PT30S}")
    @Transactional
    fun reclaimOrphanedTasks() {
        try {
            val now = Instant.now()
            val orphanedTasks = taskRepository.findOrphanedTasks(now)

            if (orphanedTasks.isEmpty()) {
                return
            }

            log.warn("Found {} orphaned task(s) to reclaim", orphanedTasks.size)

            for (task in orphanedTasks) {
                val previousLockedBy = task.lockedBy
                val previousLockTimeout = task.lockTimeoutAt

                task.status = TaskStatus.PENDING
                task.lockedBy = null
                task.lockedAt = null
                task.lockTimeoutAt = null
                // Do NOT increment attempt counter — lock timeout is not a failure (PRD §6.3)
                taskRepository.save(task)

                log.info(
                    "Reclaimed orphaned task {}: step '{}', was locked by '{}', lock expired at {}",
                    task.id, task.stepName, previousLockedBy, previousLockTimeout
                )
            }

            log.info("Reclaimed {} orphaned task(s)", orphanedTasks.size)
        } catch (e: Exception) {
            log.error("Error reclaiming orphaned tasks: {}", e.message, e)
        }
    }
}

