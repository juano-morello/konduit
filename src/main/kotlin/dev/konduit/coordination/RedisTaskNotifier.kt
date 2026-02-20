package dev.konduit.coordination

import dev.konduit.KonduitProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis-backed implementation of [TaskNotifier].
 *
 * Publishes a "tasks-available" message to the configured Redis pub/sub channel
 * when new tasks are created. Workers subscribe to this channel for instant
 * notification, reducing latency compared to polling alone.
 *
 * Graceful degradation: if Redis publish fails, logs a warning but does not throw.
 * Workers will still acquire tasks via their regular polling interval.
 */
@Component
@ConditionalOnProperty(
    name = ["konduit.redis.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@ConditionalOnBean(RedisTemplate::class)
class RedisTaskNotifier(
    private val redisTemplate: RedisTemplate<String, String>,
    private val properties: KonduitProperties
) : TaskNotifier {

    private val log = LoggerFactory.getLogger(RedisTaskNotifier::class.java)

    override fun notifyTasksAvailable() {
        try {
            val channel = properties.redis.channel
            redisTemplate.convertAndSend(channel, "tasks-available")
            log.debug("Published tasks-available notification to channel '{}'", channel)
        } catch (e: Exception) {
            log.warn(
                "Failed to publish tasks-available notification to Redis: {}. " +
                    "Workers will still acquire tasks via polling.",
                e.message
            )
        }
    }
}

