package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.DeadLetterEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DeadLetterRepository : JpaRepository<DeadLetterEntity, UUID> {

    fun findByExecutionId(executionId: UUID, pageable: Pageable): Page<DeadLetterEntity>

    fun findByWorkflowName(workflowName: String, pageable: Pageable): Page<DeadLetterEntity>

    fun findByReprocessedFalseOrderByCreatedAtDesc(pageable: Pageable): Page<DeadLetterEntity>

    fun findByTaskId(taskId: UUID): DeadLetterEntity?
}

