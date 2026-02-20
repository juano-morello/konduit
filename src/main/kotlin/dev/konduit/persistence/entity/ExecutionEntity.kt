package dev.konduit.persistence.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity mapping to the 'executions' table (PRD §3.4.2).
 * Tracks workflow execution instances with their state, input/output, and timing.
 */
@Entity
@Table(name = "executions")
class ExecutionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    var id: UUID? = null,

    @Column(name = "workflow_id", nullable = false)
    var workflowId: UUID = UUID.randomUUID(),

    @Column(name = "workflow_name", nullable = false, length = 255)
    var workflowName: String = "",

    @Column(name = "workflow_version", nullable = false)
    var workflowVersion: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "execution_status")
    var status: ExecutionStatus = ExecutionStatus.PENDING,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var input: Map<String, Any>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var output: Map<String, Any>? = null,

    @Column(name = "current_step", length = 255)
    var currentStep: String? = null,

    @Column(name = "idempotency_key", length = 255)
    var idempotencyKey: String? = null,

    @Column(columnDefinition = "TEXT")
    var error: String? = null,

    @Column(name = "timeout_at")
    var timeoutAt: Instant? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

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

