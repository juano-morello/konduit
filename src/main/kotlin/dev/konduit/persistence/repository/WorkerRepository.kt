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
}

