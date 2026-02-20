package dev.konduit.coordination

/**
 * Interface for leader election among Konduit instances.
 *
 * Only one instance should be the leader at any time. The leader runs
 * background maintenance tasks like orphan reclamation, stale worker
 * detection, and execution timeout checking.
 */
interface LeaderElectionService {

    /**
     * Check if this instance is currently the leader.
     *
     * @return true if this instance holds the leader lock.
     */
    fun isLeader(): Boolean

    /**
     * Get the ID of the current leader instance.
     *
     * @return The worker/instance ID of the current leader, or null if unknown.
     */
    fun getLeaderId(): String?
}

