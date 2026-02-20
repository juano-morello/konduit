package dev.konduit.coordination

import dev.konduit.KonduitProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Redis-backed leader election using the SET NX EX pattern.
 *
 * On startup, attempts to acquire the leader lock. If acquired, this instance
 * becomes the leader and starts a renewal loop. If not, it periodically
 * attempts to acquire the lock.
 *
 * Lock key: configurable via `konduit.leader.lock-key` (default: `konduit:leader`)
 * Lock value: unique worker ID generated at startup
 * Lock TTL: configurable via `konduit.leader.lock-ttl` (default: 30s)
 *
 * If renewal fails (lock expired, someone else acquired it), leadership is
 * relinquished. Non-leaders periodically try to acquire the lock.
 */
@Component
@ConditionalOnProperty(
    name = ["konduit.redis.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@ConditionalOnBean(RedisTemplate::class)
class RedisLeaderElection(
    @Qualifier("konduitRedisTemplate") private val redisTemplate: RedisTemplate<String, String>,
    private val properties: KonduitProperties
) : LeaderElectionService {

    companion object {
        /**
         * Lua script for atomic lock renewal.
         * Checks that the current holder matches the expected value before renewing the TTL.
         * Returns 1 if renewed, 0 if the lock is held by someone else or expired.
         */
        private val RENEW_LOCK_SCRIPT = DefaultRedisScript<Long>(
            """
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("PEXPIRE", KEYS[1], ARGV[2])
            else
                return 0
            end
            """.trimIndent(),
            Long::class.javaObjectType
        )
    }

    private val log = LoggerFactory.getLogger(RedisLeaderElection::class.java)

    private val workerId: String = "konduit-${UUID.randomUUID()}"
    private val leader = AtomicBoolean(false)
    private val currentLeaderId = AtomicReference<String?>(null)

    init {
        log.info("RedisLeaderElection initialized with workerId={}", workerId)
        tryAcquire()
    }

    override fun isLeader(): Boolean = leader.get()

    override fun getLeaderId(): String? = currentLeaderId.get()

    /**
     * Periodic task that renews the leader lock or attempts to acquire it.
     * Runs at the configured renew interval.
     */
    @Scheduled(fixedRateString = "\${konduit.leader.renew-interval:10000}")
    fun leaderHeartbeat() {
        if (leader.get()) {
            renewLock()
        } else {
            tryAcquire()
        }
    }

    private fun tryAcquire() {
        try {
            val lockKey = properties.leader.lockKey
            val ttlMs = properties.leader.lockTtl.toMillis()

            val acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, workerId, ttlMs, TimeUnit.MILLISECONDS)
                ?: false

            if (acquired) {
                leader.set(true)
                currentLeaderId.set(workerId)
                log.info("Leader lock acquired: workerId={}, ttl={}ms", workerId, ttlMs)
            } else {
                // Check who the current leader is
                val currentHolder = redisTemplate.opsForValue().get(lockKey)
                currentLeaderId.set(currentHolder)
                log.debug("Leader lock held by '{}', this instance is follower", currentHolder)
            }
        } catch (e: Exception) {
            log.warn("Failed to acquire leader lock: {}. Assuming follower role.", e.message)
            leader.set(false)
        }
    }

    private fun renewLock() {
        try {
            val lockKey = properties.leader.lockKey
            val ttlMs = properties.leader.lockTtl.toMillis()

            // Atomically verify ownership and renew TTL via Lua script
            val result = redisTemplate.execute(
                RENEW_LOCK_SCRIPT,
                listOf(lockKey),
                workerId,
                ttlMs.toString()
            )

            if (result != null && result == 1L) {
                log.debug("Leader lock renewed: workerId={}, ttl={}ms", workerId, ttlMs)
            } else {
                // Someone else has the lock or it expired
                leader.set(false)
                val currentHolder = redisTemplate.opsForValue().get(lockKey)
                currentLeaderId.set(currentHolder)
                log.warn(
                    "Leader lock lost: expected workerId={}, found={}. Relinquishing leadership.",
                    workerId, currentHolder
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to renew leader lock: {}. Relinquishing leadership.", e.message)
            leader.set(false)
        }
    }
}

