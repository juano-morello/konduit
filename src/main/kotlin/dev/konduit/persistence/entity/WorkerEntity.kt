package dev.konduit.persistence.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity mapping to the 'workers' table (PRD §3.4.5).
 * Tracks registered worker instances, their health via heartbeat, and capacity.
 */
@Entity
@Table(name = "workers")
class WorkerEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    var id: UUID? = null,

    @Column(name = "worker_id", nullable = false, unique = true, length = 255)
    var workerId: String = "",

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "worker_status")
    var status: WorkerStatus = WorkerStatus.ACTIVE,

    @Column(length = 255)
    var hostname: String? = null,

    @Column(nullable = false)
    var concurrency: Int = 5,

    @Column(name = "active_tasks", nullable = false)
    var activeTasks: Int = 0,

    @Column(name = "last_heartbeat", nullable = false)
    var lastHeartbeat: Instant = Instant.now(),

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "stopped_at")
    var stoppedAt: Instant? = null,

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
        lastHeartbeat = now
        startedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

