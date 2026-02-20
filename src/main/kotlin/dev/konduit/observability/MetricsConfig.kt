package dev.konduit.observability

import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.time.Instant

/**
 * Registers custom Micrometer gauges for Konduit observability.
 *
 * Counters and timers are recorded inline by [MetricsService].
 * Gauges are registered here because they poll current state from the database.
 *
 * Disabled when `konduit.metrics.enabled=false`.
 */
@Configuration
@ConditionalOnProperty(name = ["konduit.metrics.enabled"], havingValue = "true", matchIfMissing = true)
class MetricsConfig(
    private val meterRegistry: MeterRegistry,
    private val executionRepository: ExecutionRepository,
    private val taskRepository: TaskRepository,
    private val workerRepository: WorkerRepository
) {
    private val log = LoggerFactory.getLogger(MetricsConfig::class.java)

    @PostConstruct
    fun registerGauges() {
        // Active executions (RUNNING status)
        Gauge.builder("konduit_executions_active") {
            executionRepository.countByStatus(ExecutionStatus.RUNNING).toDouble()
        }.description("Number of currently running executions")
            .register(meterRegistry)

        // Queue depth (PENDING tasks)
        Gauge.builder("konduit_queue_depth") {
            taskRepository.countByStatus(TaskStatus.PENDING).toDouble()
        }.description("Number of pending tasks in the queue")
            .register(meterRegistry)

        // Oldest pending task age in seconds
        Gauge.builder("konduit_queue_oldest_seconds") {
            computeOldestPendingTaskAge()
        }.description("Age of the oldest pending task in seconds")
            .register(meterRegistry)

        // Active workers
        Gauge.builder("konduit_workers_active") {
            workerRepository.countByStatus(WorkerStatus.ACTIVE).toDouble()
        }.description("Number of active workers")
            .register(meterRegistry)

        log.info("Konduit Prometheus gauges registered")
    }

    private fun computeOldestPendingTaskAge(): Double {
        return try {
            val oldestCreatedAt = taskRepository.findOldestCreatedAtByStatus(TaskStatus.PENDING)
                ?: return 0.0
            Duration.between(oldestCreatedAt, Instant.now()).seconds.toDouble()
        } catch (e: Exception) {
            log.debug("Error computing oldest pending task age: {}", e.message)
            0.0
        }
    }
}

