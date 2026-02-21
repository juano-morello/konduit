package dev.konduit.queue

import dev.konduit.KonduitProperties
import dev.konduit.observability.MetricsService
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.retry.RetryCalculator
import dev.konduit.retry.RetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed task queue using SKIP LOCKED for conflict-free concurrent task acquisition.
 *
 * This component manages the task lifecycle: acquire → run → complete/fail/release.
 * All state transitions are transactional and safe for concurrent access.
 *
 * See [ADR-001](docs/adr/001-postgres-skip-locked.md) for the SKIP LOCKED design rationale.
 */
@Component
class TaskQueue(
    private val taskRepository: TaskRepository,
    private val deadLetterQueue: DeadLetterQueue,
    private val properties: KonduitProperties
) {
    private val log = LoggerFactory.getLogger(TaskQueue::class.java)

    @Autowired(required = false)
    private var metricsService: MetricsService? = null

    /**
     * Acquire a single PENDING task for the given worker.
     *
     * Uses SELECT FOR UPDATE SKIP LOCKED to ensure concurrent workers
     * never receive the same task. The acquired task is atomically
     * transitioned to LOCKED status.
     *
     * @param workerId Unique identifier of the worker acquiring the task.
     * @return The acquired [TaskEntity], or null if no tasks are available.
     */
    @Transactional
    fun acquireTask(workerId: String): TaskEntity? {
        return acquireTasks(workerId, 1).firstOrNull()
    }

    /**
     * Acquire up to [limit] PENDING tasks for the given worker in a single transaction.
     *
     * Uses SELECT FOR UPDATE SKIP LOCKED to ensure concurrent workers
     * never receive the same tasks. All acquired tasks are atomically
     * transitioned to LOCKED status.
     *
     * @param workerId Unique identifier of the worker acquiring the tasks.
     * @param limit Maximum number of tasks to acquire.
     * @return List of acquired [TaskEntity] instances (may be empty).
     */
    @Transactional
    fun acquireTasks(workerId: String, limit: Int): List<TaskEntity> {
        val acquireStart = Instant.now()
        val tasks = taskRepository.acquireTasks(limit)
        if (tasks.isEmpty()) {
            return emptyList()
        }

        val now = Instant.now()
        val lockTimeout = properties.queue.lockTimeout

        tasks.forEach { task ->
            task.status = TaskStatus.LOCKED
            task.lockedBy = workerId
            task.lockedAt = now
            task.lockTimeoutAt = now.plus(lockTimeout)

            log.debug(
                "Task acquired: taskId={}, stepName={}, workerId={}, lockTimeout={}",
                task.id, task.stepName, workerId, lockTimeout
            )
        }

        val lockedTasks = taskRepository.saveAll(tasks)

        log.info("Acquired {} tasks for worker {}", lockedTasks.size, workerId)

        // Record acquisition duration
        metricsService?.recordTaskAcquisitionDuration(Duration.between(acquireStart, Instant.now()))

        return lockedTasks
    }

    /**
     * Mark a task as successfully completed.
     *
     * Stores the output and clears all lock fields. The caller passes the task
     * entity directly to avoid a redundant findById lookup.
     *
     * **Status guard:** Tasks already in a terminal status (COMPLETED, DEAD_LETTER, FAILED)
     * cannot be completed again. This prevents double-processing if a slow worker
     * and orphan reclaimer race.
     *
     * @param task The task entity to complete (caller already has it loaded).
     * @param output The task output to store (as a JSON-compatible map).
     * @throws IllegalStateException if the task is already in a terminal status.
     */
    @Transactional
    fun completeTask(task: TaskEntity, output: Map<String, Any>?) {
        if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.DEAD_LETTER || task.status == TaskStatus.FAILED) {
            throw IllegalStateException(
                "Cannot complete task ${task.id}: task is already in terminal status ${task.status}"
            )
        }

        task.status = TaskStatus.COMPLETED
        task.output = output
        task.completedAt = Instant.now()
        task.lockedBy = null
        task.lockedAt = null
        task.lockTimeoutAt = null

        taskRepository.save(task)

        log.debug("Task completed: taskId={}, stepName={}", task.id, task.stepName)
    }

    /**
     * Handle a task failure with retry logic.
     *
     * Increments the attempt counter and consults [RetryCalculator]:
     * - If retries remain: sets status to PENDING with a computed next_retry_at delay.
     * - If exhausted: delegates to [DeadLetterQueue] for dead-lettering.
     *
     * @param taskId The ID of the failed task.
     * @param error The error message describing the failure.
     * @param retryPolicy The retry policy governing this task's retry behavior.
     * @throws IllegalArgumentException if the task is not found.
     */
    @Transactional
    fun failTask(taskId: UUID, error: String, retryPolicy: RetryPolicy) {
        val task = taskRepository.findById(taskId)
            .orElseThrow { IllegalArgumentException("Task not found: $taskId") }

        task.attempt = task.attempt + 1
        task.error = error
        task.lockedBy = null
        task.lockedAt = null
        task.lockTimeoutAt = null

        if (RetryCalculator.shouldRetry(retryPolicy, task.attempt)) {
            // Retry: compute delay and reschedule
            val delayMs = RetryCalculator.computeDelay(retryPolicy, task.attempt)
            task.status = TaskStatus.PENDING
            task.nextRetryAt = Instant.now().plusMillis(delayMs)

            taskRepository.save(task)

            log.info(
                "Task scheduled for retry: taskId={}, attempt={}/{}, nextRetryAt={}",
                taskId, task.attempt, retryPolicy.maxAttempts, task.nextRetryAt
            )
        } else {
            // Exhausted: dead-letter the task
            task.status = TaskStatus.DEAD_LETTER
            taskRepository.save(task)

            val attemptRecord = AttemptRecord(
                attempt = task.attempt,
                error = error
            )
            deadLetterQueue.deadLetter(task, listOf(attemptRecord))

            log.warn(
                "Task dead-lettered: taskId={}, stepName={}, attempts={}",
                taskId, task.stepName, task.attempt
            )
        }
    }

    /**
     * Release a locked task back to PENDING status.
     *
     * Used during graceful shutdown to return tasks to the queue
     * so other workers can pick them up.
     *
     * @param taskId The ID of the task to release.
     * @throws IllegalArgumentException if the task is not found.
     */
    @Transactional
    fun releaseTask(taskId: UUID) {
        val task = taskRepository.findById(taskId)
            .orElseThrow { IllegalArgumentException("Task not found: $taskId") }

        task.status = TaskStatus.PENDING
        task.lockedBy = null
        task.lockedAt = null
        task.lockTimeoutAt = null

        taskRepository.save(task)

        log.info("Task released: taskId={}, stepName={}", taskId, task.stepName)
    }
}

