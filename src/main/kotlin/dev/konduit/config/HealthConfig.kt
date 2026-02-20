package dev.konduit.config

import dev.konduit.coordination.LeaderElectionService
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.persistence.repository.WorkerRepository
import dev.konduit.worker.TaskWorkerState
import dev.konduit.worker.WorkerLifecycleStatus
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Custom health indicators for Konduit operational monitoring.
 */
@Configuration
class HealthConfig {

    /**
     * Worker health indicator — reports worker status, active task count, and concurrency.
     */
    @Bean
    fun workerHealthIndicator(taskWorkerState: TaskWorkerState): HealthIndicator {
        return HealthIndicator {
            val status = taskWorkerState.status.get()
            val workerId = taskWorkerState.workerId
            val activeTasks = taskWorkerState.activeTaskCount

            val builder = when (status) {
                WorkerLifecycleStatus.RUNNING -> Health.up()
                WorkerLifecycleStatus.DRAINING -> Health.outOfService()
                WorkerLifecycleStatus.STOPPED -> Health.down()
                WorkerLifecycleStatus.STARTING -> Health.unknown()
            }

            builder
                .withDetail("workerId", workerId ?: "not-registered")
                .withDetail("status", status.name)
                .withDetail("activeTasks", activeTasks)
                .build()
        }
    }

    /**
     * Leader health indicator — reports whether this instance is the leader.
     */
    @Bean
    fun leaderHealthIndicator(leaderElectionService: LeaderElectionService): HealthIndicator {
        return HealthIndicator {
            val isLeader = leaderElectionService.isLeader()
            val leaderId = leaderElectionService.getLeaderId()

            Health.up()
                .withDetail("isLeader", isLeader)
                .withDetail("leaderId", leaderId ?: "unknown")
                .build()
        }
    }

    /**
     * Queue health indicator — reports queue depth and oldest pending task age.
     */
    @Bean
    fun queueHealthIndicator(taskRepository: TaskRepository): HealthIndicator {
        return HealthIndicator {
            val pendingCount = taskRepository.countByStatus(TaskStatus.PENDING)
            val lockedCount = taskRepository.countByStatus(TaskStatus.LOCKED)
            val runningCount = taskRepository.countByStatus(TaskStatus.RUNNING)

            Health.up()
                .withDetail("pendingTasks", pendingCount)
                .withDetail("lockedTasks", lockedCount)
                .withDetail("runningTasks", runningCount)
                .build()
        }
    }
}

