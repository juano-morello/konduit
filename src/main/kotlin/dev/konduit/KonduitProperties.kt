package dev.konduit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "konduit")
data class KonduitProperties(
    val worker: WorkerProperties = WorkerProperties(),
    val queue: QueueProperties = QueueProperties(),
    val leader: LeaderProperties = LeaderProperties(),
    val execution: ExecutionProperties = ExecutionProperties(),
    val retry: RetryDefaults = RetryDefaults(),
    val redis: RedisProperties = RedisProperties(),
    val metrics: MetricsProperties = MetricsProperties(),
    val logging: LoggingProperties = LoggingProperties()
) {
    data class WorkerProperties(
        /** Number of concurrent tasks a single worker can execute */
        val concurrency: Int = 5,
        /** Interval between heartbeat updates */
        val heartbeatInterval: Duration = Duration.ofSeconds(10),
        /** Time to wait for in-progress tasks during graceful shutdown */
        val drainTimeout: Duration = Duration.ofSeconds(30),
        /** Threshold after which a worker is considered stale */
        val staleThreshold: Duration = Duration.ofSeconds(60),
        /** Interval between polling attempts when no tasks are available */
        val pollInterval: Duration = Duration.ofMillis(200)
    )

    data class QueueProperties(
        /** Duration after which a locked task is considered orphaned */
        val lockTimeout: Duration = Duration.ofMinutes(5),
        /** Interval for the orphan reclaimer to check for stuck tasks */
        val reaperInterval: Duration = Duration.ofSeconds(30),
        /** Maximum number of tasks to acquire in a single poll */
        val batchSize: Int = 5
    )

    data class LeaderProperties(
        /** TTL for the leader election lock in Redis */
        val lockTtl: Duration = Duration.ofSeconds(30),
        /** Interval for renewing the leader lock (should be < lockTtl / 2) */
        val renewInterval: Duration = Duration.ofSeconds(10),
        /** Redis key used for leader election lock */
        val lockKey: String = "konduit:leader"
    )

    data class ExecutionProperties(
        /** Default timeout for workflow executions */
        val defaultTimeout: Duration = Duration.ofMinutes(30),
        /** Interval for checking timed-out executions */
        val timeoutCheckInterval: Duration = Duration.ofSeconds(30)
    )

    data class RedisProperties(
        /** Whether Redis coordination is enabled */
        val enabled: Boolean = true,
        /** Redis pub/sub channel for task notifications */
        val channel: String = "konduit:tasks"
    )

    data class RetryDefaults(
        /** Default maximum retry attempts */
        val maxAttempts: Int = 3,
        /** Default initial delay for retry backoff */
        val initialDelay: Duration = Duration.ofSeconds(1),
        /** Default maximum delay for retry backoff */
        val maxDelay: Duration = Duration.ofMinutes(5),
        /** Default backoff multiplier */
        val multiplier: Double = 2.0
    )

    data class MetricsProperties(
        /** Whether Prometheus metrics collection is enabled */
        val enabled: Boolean = true
    )

    data class LoggingProperties(
        /** Whether to include request/response payloads in log output */
        val includePayload: Boolean = false
    )
}

