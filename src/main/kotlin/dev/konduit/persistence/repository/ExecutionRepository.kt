package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ExecutionRepository : JpaRepository<ExecutionEntity, UUID> {

    fun findByWorkflowName(workflowName: String, pageable: Pageable): Page<ExecutionEntity>

    fun findByStatus(status: ExecutionStatus, pageable: Pageable): Page<ExecutionEntity>

    fun findByIdempotencyKey(idempotencyKey: String): ExecutionEntity?

    fun findByWorkflowId(workflowId: UUID): List<ExecutionEntity>

    @Query("SELECT e FROM ExecutionEntity e WHERE e.status IN :statuses ORDER BY e.createdAt DESC")
    fun findByStatusIn(@Param("statuses") statuses: List<ExecutionStatus>, pageable: Pageable): Page<ExecutionEntity>

    fun findByStatusAndWorkflowName(
        status: ExecutionStatus,
        workflowName: String,
        pageable: Pageable
    ): Page<ExecutionEntity>

    fun countByStatus(status: ExecutionStatus): Long

    fun countByStatusAndCompletedAtAfter(status: ExecutionStatus, after: Instant): Long

    @Query(
        value = """
            SELECT * FROM executions
            WHERE status = 'RUNNING'
              AND timeout_at IS NOT NULL
              AND timeout_at <= :now
        """,
        nativeQuery = true
    )
    fun findTimedOutExecutions(@Param("now") now: Instant): List<ExecutionEntity>

    /**
     * Count executions grouped by status. Returns pairs of (status, count).
     * Used by StatsController to get all status counts in a single query.
     */
    @Query("SELECT e.status, COUNT(e) FROM ExecutionEntity e GROUP BY e.status")
    fun countGroupByStatus(): List<Array<Any>>

    /**
     * Find execution by ID with pessimistic write lock (SELECT FOR UPDATE).
     * Used to serialize parallel fan-in checks — prevents race condition where
     * concurrent task completions each see incomplete parallel groups.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExecutionEntity e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ExecutionEntity?

    /**
     * Find terminal-state executions completed before a cutoff time.
     * Used by RetentionService to identify executions eligible for cleanup.
     */
    @Query(
        """
        SELECT e FROM ExecutionEntity e
        WHERE e.status IN :statuses
          AND e.completedAt IS NOT NULL
          AND e.completedAt < :cutoff
        """
    )
    fun findByStatusInAndCompletedAtBefore(
        @Param("statuses") statuses: List<ExecutionStatus>,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable
    ): List<ExecutionEntity>

    /**
     * Delete executions by their IDs. Used by RetentionService for batch cleanup.
     */
    @Modifying
    @Query("DELETE FROM ExecutionEntity e WHERE e.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: List<UUID>): Int
}

