package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface TaskRepository : JpaRepository<TaskEntity, UUID> {

    /**
     * CRITICAL: SKIP LOCKED task acquisition (PRD §6.1).
     * Acquires up to [limit] PENDING tasks that are ready to execute,
     * locking them to prevent other workers from picking them up.
     */
    @Query(
        value = """
            SELECT * FROM tasks 
            WHERE status = 'PENDING' 
              AND (next_retry_at IS NULL OR next_retry_at <= now())
            ORDER BY created_at ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun acquireTasks(@Param("limit") limit: Int): List<TaskEntity>

    fun findByExecutionId(executionId: UUID): List<TaskEntity>

    fun findByExecutionIdOrderByStepOrderAsc(executionId: UUID): List<TaskEntity>

    fun findByExecutionIdAndStatus(executionId: UUID, status: TaskStatus): List<TaskEntity>

    fun findByExecutionIdAndParallelGroup(executionId: UUID, parallelGroup: String): List<TaskEntity>

    /**
     * Find orphaned tasks: LOCKED tasks whose lock has timed out (PRD §6.3).
     */
    @Query(
        value = """
            SELECT * FROM tasks 
            WHERE status = 'LOCKED' 
              AND lock_timeout_at IS NOT NULL 
              AND lock_timeout_at <= :now
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findOrphanedTasks(@Param("now") now: Instant): List<TaskEntity>

    /**
     * Find tasks that have timed out while RUNNING.
     */
    @Query(
        value = """
            SELECT * FROM tasks 
            WHERE status = 'RUNNING' 
              AND timeout_at IS NOT NULL 
              AND timeout_at <= :now
        """,
        nativeQuery = true
    )
    fun findTimedOutTasks(@Param("now") now: Instant): List<TaskEntity>

    /**
     * Count tasks by execution and status — useful for fan-in completion checks.
     */
    fun countByExecutionIdAndParallelGroupAndStatus(
        executionId: UUID,
        parallelGroup: String,
        status: TaskStatus
    ): Long

    fun countByExecutionIdAndParallelGroup(executionId: UUID, parallelGroup: String): Long

    fun countByStatus(status: TaskStatus): Long
}

