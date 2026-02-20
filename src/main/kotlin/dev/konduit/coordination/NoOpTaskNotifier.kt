package dev.konduit.coordination

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * No-op implementation of [TaskNotifier] used when Redis is unavailable.
 *
 * Workers rely solely on polling to discover new tasks.
 * This bean is activated when `konduit.redis.enabled` is false.
 */
@Component
@ConditionalOnProperty(
    name = ["konduit.redis.enabled"],
    havingValue = "false"
)
class NoOpTaskNotifier : TaskNotifier {

    private val log = LoggerFactory.getLogger(NoOpTaskNotifier::class.java)

    init {
        log.info("Redis unavailable — using NoOpTaskNotifier. Workers will rely on polling.")
    }

    override fun notifyTasksAvailable() {
        // No-op: workers discover tasks via polling
    }
}

