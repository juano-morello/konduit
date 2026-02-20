package dev.konduit.persistence.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity mapping to the 'dead_letters' table (PRD §3.4.4).
 * Tasks that exhausted all retry attempts are moved here with full error history.
 */
@Entity
@Table(name = "dead_letters")
class DeadLetterEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    var id: UUID? = null,

    @Column(name = "task_id", nullable = false, unique = true)
    var taskId: UUID = UUID.randomUUID(),

    @Column(name = "execution_id", nullable = false)
    var executionId: UUID = UUID.randomUUID(),

    @Column(name = "workflow_name", nullable = false, length = 255)
    var workflowName: String = "",

    @Column(name = "step_name", nullable = false, length = 255)
    var stepName: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var input: Map<String, Any>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_history", nullable = false, columnDefinition = "jsonb")
    var errorHistory: List<Map<String, Any>> = emptyList(),

    @Column(nullable = false)
    var reprocessed: Boolean = false,

    @Column(name = "reprocessed_at")
    var reprocessedAt: Instant? = null,

    // V6 additions
    @Column(columnDefinition = "TEXT")
    var error: String? = null,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
) {
    @PrePersist
    fun prePersist() {
        createdAt = Instant.now()
    }
}

