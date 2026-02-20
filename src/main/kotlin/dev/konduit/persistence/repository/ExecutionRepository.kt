package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
}

