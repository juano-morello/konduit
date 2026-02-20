package dev.konduit.coordination

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * No-op implementation of [LeaderElectionService] used when Redis is unavailable.
 *
 * All instances consider themselves the leader. This is safe because
 * leader-only background jobs (orphan reclamation, stale worker detection,
 * timeout checking) are idempotent and can safely run on multiple instances.
 */
@Component
@ConditionalOnProperty(
    name = ["konduit.redis.enabled"],
    havingValue = "false"
)
class NoOpLeaderElection : LeaderElectionService {

    private val log = LoggerFactory.getLogger(NoOpLeaderElection::class.java)

    init {
        log.info(
            "Redis unavailable — using NoOpLeaderElection. " +
                "All instances will act as leader (idempotent jobs are safe to run in parallel)."
        )
    }

    override fun isLeader(): Boolean = true

    override fun getLeaderId(): String? = "local"
}

