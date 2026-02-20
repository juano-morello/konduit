package dev.konduit.persistence.repository

import dev.konduit.persistence.entity.WorkerEntity
import dev.konduit.persistence.entity.WorkerStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface WorkerRepository : JpaRepository<WorkerEntity, UUID> {

    fun findByWorkerId(workerId: String): WorkerEntity?

    fun findByStatus(status: WorkerStatus): List<WorkerEntity>

    fun countByStatus(status: WorkerStatus): Long

    /**
     * Find stale workers: ACTIVE workers whose heartbeat is older than the threshold.
     */
    @Query(
        value = """
            SELECT * FROM workers
            WHERE status = 'ACTIVE'
              AND last_heartbeat < :threshold
        """,
        nativeQuery = true
    )
    fun findStaleWorkers(@Param("threshold") threshold: Instant): List<WorkerEntity>

    /**
     * Get aggregate stats for active workers in a single query.
     * Returns [count, sumConcurrency, sumActiveTasks] or null if no active workers.
     * Used by StatsController to avoid loading all active worker entities.
     */
    @Query(
        "SELECT COUNT(w), COALESCE(SUM(w.concurrency), 0), COALESCE(SUM(w.activeTasks), 0) " +
        "FROM WorkerEntity w WHERE w.status = :status"
    )
    fun getAggregateStatsByStatus(@Param("status") status: WorkerStatus): Array<Any>
}

