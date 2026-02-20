package dev.konduit.worker

import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerEntity
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Manages worker lifecycle in the database (PRD §7.1).
 *
 * Handles registration, deregistration, heartbeat updates,
 * and stale worker detection with task reclamation.
 */
@Component
class WorkerRegistry(
    private val workerRepository: WorkerRepository,
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(WorkerRegistry::class.java)

    /**
     * Register a new worker in the database.
     *
     * Creates a WorkerEntity with ACTIVE status and the given concurrency.
     *
     * @param workerId Unique identifier for the worker (hostname-uuid format).
     * @param hostname The hostname of the machine running this worker.
     * @param concurrency Maximum number of concurrent tasks this worker can handle.
     * @return The persisted WorkerEntity.
     */
    @Transactional
    fun register(workerId: String, hostname: String, concurrency: Int): WorkerEntity {
        val worker = WorkerEntity(
            workerId = workerId,
            hostname = hostname,
            status = WorkerStatus.ACTIVE,
            concurrency = concurrency,
            activeTasks = 0
        )

        val saved = workerRepository.save(worker)
        log.info(
            "Worker registered: workerId={}, hostname={}, concurrency={}",
            workerId, hostname, concurrency
        )
        return saved
    }

    /**
     * Deregister a worker by setting its status to STOPPED.
     *
     * @param workerId The unique identifier of the worker to deregister.
     */
    @Transactional
    fun deregister(workerId: String) {
        val worker = workerRepository.findByWorkerId(workerId)
        if (worker == null) {
            log.warn("Cannot deregister unknown worker: {}", workerId)
            return
        }

        worker.status = WorkerStatus.STOPPED
        worker.stoppedAt = Instant.now()
        worker.activeTasks = 0
        workerRepository.save(worker)

        log.info("Worker deregistered: workerId={}", workerId)
    }

    /**
     * Update the heartbeat timestamp and active task count for a worker.
     *
     * @param workerId The unique identifier of the worker.
     * @param activeTasks The current number of active tasks on this worker.
     */
    @Transactional
    fun updateHeartbeat(workerId: String, activeTasks: Int) {
        val worker = workerRepository.findByWorkerId(workerId)
        if (worker == null) {
            log.warn("Cannot update heartbeat for unknown worker: {}", workerId)
            return
        }

        worker.lastHeartbeat = Instant.now()
        worker.activeTasks = activeTasks
        workerRepository.save(worker)

        log.debug("Heartbeat updated: workerId={}, activeTasks={}", workerId, activeTasks)
    }

    /**
     * Detect stale workers and reclaim their locked tasks (PRD §7.1).
     *
     * A worker is considered stale if its last heartbeat is older than the
     * given threshold and it is still in ACTIVE status.
     *
     * For each stale worker:
     * 1. Mark the worker as STOPPED
     * 2. Find all tasks locked by that worker
     * 3. Reset those tasks to PENDING (without incrementing attempt counter)
     *
     * @param staleThreshold Duration after which a worker is considered stale.
     * @return The number of stale workers detected and cleaned up.
     */
    @Transactional
    fun detectStaleWorkers(staleThreshold: Duration): Int {
        val threshold = Instant.now().minus(staleThreshold)
        val staleWorkers = workerRepository.findStaleWorkers(threshold)

        if (staleWorkers.isEmpty()) {
            return 0
        }

        log.warn("Detected {} stale worker(s)", staleWorkers.size)

        for (worker in staleWorkers) {
            worker.status = WorkerStatus.STOPPED
            worker.stoppedAt = Instant.now()
            worker.activeTasks = 0
            workerRepository.save(worker)

            // Reclaim tasks locked by this stale worker
            val lockedTasks = taskRepository.findByLockedBy(worker.workerId)
            for (task in lockedTasks) {
                task.status = TaskStatus.PENDING
                task.lockedBy = null
                task.lockedAt = null
                task.lockTimeoutAt = null
                taskRepository.save(task)

                log.info(
                    "Reclaimed task {} (step '{}') from stale worker {}",
                    task.id, task.stepName, worker.workerId
                )
            }

            log.warn(
                "Stale worker {} cleaned up: {} task(s) reclaimed",
                worker.workerId, lockedTasks.size
            )
        }

        return staleWorkers.size
    }
}

