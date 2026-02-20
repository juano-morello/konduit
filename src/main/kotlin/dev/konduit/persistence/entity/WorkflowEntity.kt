package dev.konduit.persistence.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity mapping to the 'workflows' table (PRD §3.4.1).
 * Stores workflow definitions with their step graphs as JSONB.
 */
@Entity
@Table(
    name = "workflows",
    uniqueConstraints = [UniqueConstraint(name = "uq_workflows_name_version", columnNames = ["name", "version"])]
)
class WorkflowEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    var id: UUID? = null,

    @Column(nullable = false, length = 255)
    var name: String = "",

    @Column(nullable = false)
    var version: Int = 1,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_definitions", nullable = false, columnDefinition = "jsonb")
    var stepDefinitions: Map<String, Any> = emptyMap(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

