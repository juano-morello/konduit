package dev.konduit.persistence.entity

import dev.konduit.retry.BackoffStrategy
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity mapping to the 'tasks' table.
 * The task queue: each row is a unit of work. Workers acquire tasks via SKIP LOCKED.
 */
@Entity
@Table(name = "tasks")
class TaskEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    var id: UUID? = null,

    @Column(name = "execution_id", nullable = false)
    var executionId: UUID = UUID.randomUUID(),

    @Column(name = "step_name", nullable = false, length = 255)
    var stepName: String = "",

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "step_type", nullable = false, columnDefinition = "step_type")
    var stepType: StepType = StepType.SEQUENTIAL,

    @Column(name = "step_order", nullable = false)
    var stepOrder: Int = 0,

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "task_status")
    var status: TaskStatus = TaskStatus.PENDING,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var input: Map<String, Any>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var output: Map<String, Any>? = null,

    @Column(columnDefinition = "TEXT")
    var error: String? = null,

    @Column(nullable = false)
    var attempt: Int = 0,

    @Column(name = "max_attempts", nullable = false)
    var maxAttempts: Int = 3,

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null,

    @Column(name = "locked_by", length = 255)
    var lockedBy: String? = null,

    @Column(name = "locked_at")
    var lockedAt: Instant? = null,

    @Column(name = "lock_timeout_at")
    var lockTimeoutAt: Instant? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    // Parallel fan-out/fan-in tracking (Phase 2)
    @Column(name = "parallel_group", length = 255)
    var parallelGroup: String? = null,

    @Column(name = "branch_key", length = 255)
    var branchKey: String? = null,

    // V6 additions
    @Column(name = "parent_task_id")
    var parentTaskId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "backoff_strategy", nullable = false, length = 50)
    var backoffStrategy: BackoffStrategy = BackoffStrategy.EXPONENTIAL,

    @Column(name = "backoff_base_ms", nullable = false)
    var backoffBaseMs: Long = 1000,

    @Column(name = "timeout_at")
    var timeoutAt: Instant? = null,

    @Column(nullable = false)
    var priority: Int = 0,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
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

