package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.DeadLetterEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DeadLetterRepository : JpaRepository<DeadLetterEntity, UUID> {

    fun findByExecutionId(executionId: UUID, pageable: Pageable): Page<DeadLetterEntity>

    fun findByWorkflowName(workflowName: String, pageable: Pageable): Page<DeadLetterEntity>

    fun findByReprocessedFalseOrderByCreatedAtDesc(pageable: Pageable): Page<DeadLetterEntity>

    fun findByTaskId(taskId: UUID): DeadLetterEntity?

    /**
     * Find unprocessed dead letters matching optional filter criteria.
     * Used by batch reprocessing.
     */
    @Query(
        """
        SELECT d FROM DeadLetterEntity d
        WHERE d.reprocessed = false
          AND (:workflowName IS NULL OR d.workflowName = :workflowName)
          AND (:executionId IS NULL OR d.executionId = :executionId)
          AND (:stepName IS NULL OR d.stepName = :stepName)
        ORDER BY d.createdAt ASC
        """
    )
    fun findByFilter(
        @Param("workflowName") workflowName: String?,
        @Param("executionId") executionId: UUID?,
        @Param("stepName") stepName: String?
    ): List<DeadLetterEntity>

    /**
     * Delete all dead letters belonging to the given execution IDs.
     * Used by RetentionService to cascade-delete dead letters before removing executions.
     */
    @Modifying
    @Query("DELETE FROM DeadLetterEntity d WHERE d.executionId IN :executionIds")
    fun deleteByExecutionIds(@Param("executionIds") executionIds: List<UUID>): Int
}

