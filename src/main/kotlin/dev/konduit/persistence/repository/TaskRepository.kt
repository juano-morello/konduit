package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface TaskRepository : JpaRepository<TaskEntity, UUID> {

    /**
     * CRITICAL: SKIP LOCKED task acquisition (see [ADR-001](docs/adr/001-postgres-skip-locked.md)).
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
     * Find orphaned tasks: LOCKED tasks whose lock has timed out (see [ADR-007](docs/adr/007-orphan-reclamation.md)).
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
     * Atomically reclaim orphaned tasks: reset LOCKED tasks with expired lock_timeout_at
     * back to PENDING in a single UPDATE statement (see [ADR-007](docs/adr/007-orphan-reclamation.md)).
     *
     * The WHERE status='LOCKED' guard ensures that if a slow worker completes a task
     * (transitioning it to COMPLETED/RUNNING) between the reclaimer's scheduling and
     * this UPDATE, the row is simply skipped (0 rows affected for that task).
     * This eliminates the read-modify-write race condition.
     *
     * @return the number of tasks reclaimed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE tasks
            SET status = 'PENDING',
                locked_by = NULL,
                locked_at = NULL,
                lock_timeout_at = NULL,
                updated_at = now(),
                version = version + 1
            WHERE status = 'LOCKED'
              AND lock_timeout_at IS NOT NULL
              AND lock_timeout_at <= :now
        """,
        nativeQuery = true
    )
    fun reclaimOrphanedTasks(@Param("now") now: Instant): Int

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

    fun countByStatusAndCompletedAtAfter(status: TaskStatus, after: Instant): Long

    /**
     * Find tasks currently locked by a specific worker.
     * Used during stale worker detection and graceful shutdown to reclaim tasks.
     */
    fun findByLockedBy(workerId: String): List<TaskEntity>

    /**
     * Get the oldest created_at timestamp for tasks with the given status.
     * Used by MetricsConfig to compute oldest pending task age without loading all tasks.
     */
    @Query("SELECT MIN(t.createdAt) FROM TaskEntity t WHERE t.status = :status")
    fun findOldestCreatedAtByStatus(@Param("status") status: TaskStatus): Instant?

    /**
     * Count tasks grouped by status. Returns pairs of (status, count).
     * Used by StatsController to get all status counts in a single query.
     */
    @Query("SELECT t.status, COUNT(t) FROM TaskEntity t GROUP BY t.status")
    fun countGroupByStatus(): List<Array<Any>>
}

