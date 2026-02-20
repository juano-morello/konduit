package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.WorkflowEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Spring Data JPA repository for [WorkflowEntity].
 * Provides persistence operations for workflow definitions.
 */
@Repository
interface WorkflowRepository : JpaRepository<WorkflowEntity, UUID> {

    /**
     * Find a workflow by its unique name and version combination.
     */
    fun findByNameAndVersion(name: String, version: Int): WorkflowEntity?

    /**
     * Find all versions of a workflow by name.
     */
    fun findByName(name: String): List<WorkflowEntity>

    /**
     * Find the latest version of a workflow by name.
     */
    fun findTopByNameOrderByVersionDesc(name: String): WorkflowEntity?
}

