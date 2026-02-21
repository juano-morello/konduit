package dev.konduit.engine

import dev.konduit.KonduitProperties
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.repository.DeadLetterRepository
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Retention cleanup service for terminal-state executions.
 *
 * Finds executions in terminal states (COMPLETED, FAILED, CANCELLED, TIMED_OUT)
 * that are older than the configured TTL, then deletes associated tasks and
 * dead letters before removing the executions themselves.
 *
 * Operates in configurable batches to limit database load per run.
 */
@Service
class RetentionService(
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository,
    private val deadLetterRepository: DeadLetterRepository,
    private val properties: KonduitProperties
) {

    private val log = LoggerFactory.getLogger(RetentionService::class.java)

    companion object {
        val TERMINAL_STATUSES = listOf(
            ExecutionStatus.COMPLETED,
            ExecutionStatus.FAILED,
            ExecutionStatus.CANCELLED,
            ExecutionStatus.TIMED_OUT
        )
    }

    /**
     * Run a single retention cleanup pass.
     *
     * Finds up to [KonduitProperties.RetentionProperties.batchSize] terminal executions
     * older than the configured TTL, deletes their associated tasks and dead letters,
     * then deletes the executions.
     *
     * @return the number of executions deleted in this pass
     */
    @Transactional
    fun cleanupExpiredExecutions(): Int {
        if (!properties.retention.enabled) {
            return 0
        }

        val ttl = properties.retention.completedExecutionTtl
        val batchSize = properties.retention.batchSize
        val cutoff = Instant.now().minus(ttl)

        val expiredExecutions = executionRepository.findByStatusInAndCompletedAtBefore(
            TERMINAL_STATUSES,
            cutoff,
            PageRequest.of(0, batchSize)
        )

        if (expiredExecutions.isEmpty()) {
            return 0
        }

        val executionIds = expiredExecutions.mapNotNull { it.id }

        // Delete in FK-safe order: dead letters → tasks → executions
        // (dead_letters.task_id references tasks.id, tasks.execution_id references executions.id)
        val deletedDeadLetters = deadLetterRepository.deleteByExecutionIds(executionIds)
        val deletedTasks = taskRepository.deleteByExecutionIds(executionIds)
        val deletedExecutions = executionRepository.deleteByIdIn(executionIds)

        log.info(
            "Retention cleanup: deleted {} execution(s), {} task(s), {} dead letter(s) (cutoff={})",
            deletedExecutions, deletedTasks, deletedDeadLetters, cutoff
        )

        return deletedExecutions
    }
}

