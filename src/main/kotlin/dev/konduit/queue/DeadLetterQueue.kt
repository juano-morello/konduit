package dev.konduit.queue

import dev.konduit.observability.MetricsService
import dev.konduit.persistence.entity.DeadLetterEntity
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Dead letter queue for tasks that have exhausted all retry attempts.
 *
 * Preserves full error history as JSONB for debugging and supports
 * reprocessing (creating a fresh task from a dead-lettered one).
 *
 * Tasks that exhaust retries are dead-lettered with full error history and can be reprocessed via the API.
 */
@Component
class DeadLetterQueue(
    private val deadLetterRepository: DeadLetterRepository,
    private val taskRepository: TaskRepository,
    private val executionRepository: ExecutionRepository
) {
    private val log = LoggerFactory.getLogger(DeadLetterQueue::class.java)

    @Autowired(required = false)
    private var metricsService: MetricsService? = null

    /**
     * Move a failed task to the dead letter queue with full error history.
     *
     * Creates a [DeadLetterEntity] capturing the task's execution context,
     * input, and complete attempt history for post-mortem analysis.
     *
     * @param task The task entity that exhausted retries (should already have status DEAD_LETTER).
     * @param errorHistory List of [AttemptRecord]s documenting each failed attempt.
     */
    @Transactional
    fun deadLetter(task: TaskEntity, errorHistory: List<AttemptRecord>) {
        val execution = executionRepository.findById(task.executionId).orElse(null)
        val workflowName = execution?.workflowName ?: "unknown"

        val deadLetter = DeadLetterEntity(
            taskId = requireNotNull(task.id) { "Task ID must not be null when dead-lettering" },
            executionId = task.executionId,
            workflowName = workflowName,
            stepName = task.stepName,
            input = task.input,
            errorHistory = errorHistory.map { it.toMap() },
            error = task.error,
            attempts = task.attempt
        )

        deadLetterRepository.save(deadLetter)

        // Record dead letter metric
        metricsService?.recordDeadLetter(workflowName, task.stepName)

        log.warn(
            "Task dead-lettered: taskId={}, stepName={}, workflow={}, attempts={}",
            task.id, task.stepName, workflowName, task.attempt
        )
    }

    /**
     * Reprocess a single dead-lettered task by creating a fresh PENDING task.
     *
     * The new task inherits the original's execution context, step info, and input,
     * but starts with a fresh attempt counter (attempt=0). The dead letter entry
     * is marked as reprocessed.
     *
     * @param deadLetterId The ID of the dead letter entry to reprocess.
     * @return The newly created [TaskEntity].
     * @throws IllegalArgumentException if the dead letter is not found or already reprocessed.
     */
    @Transactional
    fun reprocess(deadLetterId: UUID): TaskEntity {
        val deadLetter = deadLetterRepository.findById(deadLetterId)
            .orElseThrow { IllegalArgumentException("Dead letter not found: $deadLetterId") }

        require(!deadLetter.reprocessed) {
            "Dead letter already reprocessed: $deadLetterId"
        }

        // Look up the original task to copy step metadata
        val originalTask = taskRepository.findById(deadLetter.taskId).orElse(null)

        val newTask = TaskEntity(
            executionId = deadLetter.executionId,
            stepName = deadLetter.stepName,
            stepType = originalTask?.stepType ?: dev.konduit.persistence.entity.StepType.SEQUENTIAL,
            stepOrder = originalTask?.stepOrder ?: 0,
            status = TaskStatus.PENDING,
            input = deadLetter.input,
            attempt = 0,
            maxAttempts = originalTask?.maxAttempts ?: 3,
            backoffStrategy = originalTask?.backoffStrategy
                ?: dev.konduit.retry.BackoffStrategy.EXPONENTIAL,
            backoffBaseMs = originalTask?.backoffBaseMs ?: 1000L,
            parentTaskId = originalTask?.parentTaskId,
            parallelGroup = originalTask?.parallelGroup,
            branchKey = originalTask?.branchKey
        )

        val savedTask = taskRepository.save(newTask)

        // Mark dead letter as reprocessed
        deadLetter.reprocessed = true
        deadLetter.reprocessedAt = Instant.now()
        deadLetterRepository.save(deadLetter)

        log.info(
            "Dead letter reprocessed: deadLetterId={}, newTaskId={}, stepName={}",
            deadLetterId, savedTask.id, savedTask.stepName
        )

        return savedTask
    }

    /**
     * Batch reprocess dead letters matching the given filter criteria.
     *
     * @param filter Criteria to select which dead letters to reprocess.
     * @return List of newly created [TaskEntity] instances.
     */
    @Transactional
    fun reprocessBatch(filter: DeadLetterFilter): List<TaskEntity> {
        val deadLetters = deadLetterRepository.findByFilter(
            workflowName = filter.workflowName,
            executionId = filter.executionId,
            stepName = filter.stepName
        )

        if (deadLetters.isEmpty()) {
            log.info("No dead letters found matching filter: {}", filter)
            return emptyList()
        }

        val reprocessedTasks = deadLetters.map { dl ->
            reprocess(requireNotNull(dl.id) { "Dead letter ID must not be null for reprocessing" })
        }

        log.info(
            "Batch reprocessed {} dead letters matching filter: {}",
            reprocessedTasks.size, filter
        )

        return reprocessedTasks
    }
}

