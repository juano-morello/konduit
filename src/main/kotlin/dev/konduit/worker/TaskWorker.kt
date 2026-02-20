package dev.konduit.worker

import dev.konduit.KonduitProperties
import dev.konduit.dsl.StepContext
import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.engine.ExecutionAdvancer
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.queue.TaskQueue
import dev.konduit.retry.RetryPolicy
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Main worker component that polls for tasks and executes them (PRD §7).
 *
 * On startup:
 * 1. Generates a unique worker ID (hostname-uuid)
 * 2. Registers in the workers table via WorkerRegistry
 * 3. Starts a poll loop that acquires and executes tasks
 *
 * On shutdown (SIGTERM / @PreDestroy):
 * 1. Sets status to DRAINING (stops accepting new tasks)
 * 2. Awaits thread pool termination up to drain timeout
 * 3. Releases any remaining locked tasks
 * 4. Deregisters via WorkerRegistry
 */
@Component
@ConditionalOnProperty(name = ["konduit.worker.auto-start"], havingValue = "true", matchIfMissing = true)
class TaskWorker(
    private val taskQueue: TaskQueue,
    private val taskRepository: TaskRepository,
    private val executionRepository: ExecutionRepository,
    private val executionAdvancer: ExecutionAdvancer,
    private val workflowRegistry: WorkflowRegistry,
    private val workerRegistry: WorkerRegistry,
    private val taskWorkerState: TaskWorkerState,
    private val properties: KonduitProperties
) {
    private val log = LoggerFactory.getLogger(TaskWorker::class.java)

    /** Thread pool for executing tasks concurrently. */
    private lateinit var taskExecutor: java.util.concurrent.ExecutorService

    /** Scheduler for the poll loop. */
    private lateinit var pollScheduler: ScheduledExecutorService

    /**
     * Initialize and start the worker on application ready.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
        val workerId = "$hostname-${UUID.randomUUID().toString().substring(0, 8)}"
        val concurrency = properties.worker.concurrency

        taskWorkerState.workerId = workerId
        taskWorkerState.status.set(WorkerLifecycleStatus.RUNNING)

        // Register in the database
        workerRegistry.register(workerId, hostname, concurrency)

        // Create thread pool for task execution
        taskExecutor = Executors.newFixedThreadPool(concurrency)

        // Start poll loop
        pollScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "konduit-poll").apply { isDaemon = true }
        }
        val pollIntervalMs = properties.worker.pollInterval.toMillis()
        pollScheduler.scheduleWithFixedDelay(
            { pollAndExecute() },
            pollIntervalMs,
            pollIntervalMs,
            TimeUnit.MILLISECONDS
        )

        log.info(
            "TaskWorker started: workerId={}, concurrency={}, pollInterval={}ms",
            workerId, concurrency, pollIntervalMs
        )
    }

    /**
     * Poll for a task and submit it for execution if capacity is available.
     */
    private fun pollAndExecute() {
        try {
            if (taskWorkerState.status.get() != WorkerLifecycleStatus.RUNNING) {
                return
            }

            val concurrency = properties.worker.concurrency
            if (taskWorkerState.activeTaskCount >= concurrency) {
                return
            }

            val workerId = taskWorkerState.workerId ?: return
            val task = taskQueue.acquireTask(workerId) ?: return

            taskWorkerState.incrementActiveTasks()

            taskExecutor.submit {
                try {
                    executeTask(task)
                } catch (e: Exception) {
                    log.error("Unexpected error executing task {}: {}", task.id, e.message, e)
                } finally {
                    taskWorkerState.decrementActiveTasks()
                }
            }
        } catch (e: Exception) {
            log.error("Error in poll loop: {}", e.message, e)
        }
    }

    /**
     * Execute a single task: look up the workflow, find the step handler,
     * build a StepContext, invoke the handler, then report the result.
     */
    private fun executeTask(task: TaskEntity) {
        val taskId = task.id!!
        val workerId = taskWorkerState.workerId!!

        log.info("Executing task {}: step '{}', attempt {}", taskId, task.stepName, task.attempt + 1)

        // Mark task as RUNNING
        task.status = TaskStatus.RUNNING
        task.startedAt = Instant.now()
        taskRepository.save(task)

        try {
            // Look up the workflow definition
            val execution = executionRepository.findById(task.executionId).orElseThrow {
                IllegalStateException("Execution ${task.executionId} not found for task $taskId")
            }

            val workflowDef = workflowRegistry.findByNameAndVersion(
                execution.workflowName, execution.workflowVersion
            ) ?: throw IllegalStateException(
                "Workflow '${execution.workflowName}' v${execution.workflowVersion} not found in registry"
            )

            // Find the step handler
            val stepDef = workflowDef.getStep(task.stepName)

            // Build the StepContext
            val context = StepContext(
                executionId = task.executionId,
                input = task.input,
                previousOutput = task.input,
                executionInput = execution.input,
                attempt = task.attempt + 1,
                stepName = task.stepName,
                workflowName = execution.workflowName
            )

            // Invoke the handler
            val output = stepDef.handler(context)

            // Convert output to Map<String, Any> if needed
            @Suppress("UNCHECKED_CAST")
            val outputMap = when (output) {
                null -> null
                is Map<*, *> -> output as Map<String, Any>
                else -> mapOf("result" to output)
            }

            // Report success
            taskQueue.completeTask(taskId, outputMap)
            executionAdvancer.onTaskCompleted(taskId)

            log.info("Task {} completed successfully: step '{}'", taskId, task.stepName)
        } catch (e: Exception) {
            log.error("Task {} failed: step '{}', error: {}", taskId, task.stepName, e.message, e)

            // Build retry policy from task entity fields
            val retryPolicy = RetryPolicy(
                maxAttempts = task.maxAttempts,
                backoffStrategy = mapBackoffStrategy(task.backoffStrategy),
                baseDelayMs = task.backoffBaseMs
            )

            taskQueue.failTask(taskId, e.message ?: "Unknown error", retryPolicy)

            // If the task was dead-lettered (exhausted retries), notify the engine
            val updatedTask = taskRepository.findById(taskId).orElse(null)
            if (updatedTask != null && updatedTask.status == TaskStatus.DEAD_LETTER) {
                executionAdvancer.onTaskDeadLettered(taskId)
            }
        }
    }

    /**
     * Map from entity BackoffStrategy to retry module BackoffStrategy.
     */
    private fun mapBackoffStrategy(
        strategy: dev.konduit.persistence.entity.BackoffStrategy
    ): dev.konduit.retry.BackoffStrategy {
        return when (strategy) {
            dev.konduit.persistence.entity.BackoffStrategy.FIXED -> dev.konduit.retry.BackoffStrategy.FIXED
            dev.konduit.persistence.entity.BackoffStrategy.LINEAR -> dev.konduit.retry.BackoffStrategy.LINEAR
            dev.konduit.persistence.entity.BackoffStrategy.EXPONENTIAL -> dev.konduit.retry.BackoffStrategy.EXPONENTIAL
        }
    }

    /**
     * Graceful shutdown (PRD §7.3).
     *
     * 1. Set status to DRAINING (stop accepting new tasks in poll loop)
     * 2. Shut down the poll scheduler
     * 3. Await thread pool termination up to drain timeout
     * 4. Release any remaining locked tasks
     * 5. Deregister via WorkerRegistry
     */
    @PreDestroy
    fun shutdown() {
        val workerId = taskWorkerState.workerId ?: return

        log.info("Initiating graceful shutdown for worker {}", workerId)

        // Step 1: Set status to DRAINING
        taskWorkerState.status.set(WorkerLifecycleStatus.DRAINING)
        log.info("Worker {} status set to DRAINING — no new tasks will be accepted", workerId)

        // Step 2: Stop the poll scheduler
        if (::pollScheduler.isInitialized) {
            pollScheduler.shutdown()
            log.info("Worker {} poll scheduler stopped", workerId)
        }

        // Step 3: Await thread pool termination
        val drainTimeoutMs = properties.worker.drainTimeout.toMillis()
        if (::taskExecutor.isInitialized) {
            taskExecutor.shutdown()
            val terminated = taskExecutor.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS)
            if (terminated) {
                log.info("Worker {} task executor drained successfully", workerId)
            } else {
                log.warn(
                    "Worker {} task executor did not drain within {}ms — forcing shutdown",
                    workerId, drainTimeoutMs
                )
                taskExecutor.shutdownNow()
            }
        }

        // Step 4: Release any remaining locked tasks
        try {
            val lockedTasks = taskRepository.findByLockedBy(workerId)
            for (task in lockedTasks) {
                try {
                    taskQueue.releaseTask(task.id!!)
                    log.info("Released task {} (step '{}') during shutdown", task.id, task.stepName)
                } catch (e: Exception) {
                    log.error("Failed to release task {} during shutdown: {}", task.id, e.message)
                }
            }
            if (lockedTasks.isNotEmpty()) {
                log.info("Released {} locked task(s) during shutdown", lockedTasks.size)
            }
        } catch (e: Exception) {
            log.error("Failed to release locked tasks during shutdown: {}", e.message)
        }

        // Step 5: Deregister
        try {
            workerRegistry.deregister(workerId)
            log.info("Worker {} deregistered successfully", workerId)
        } catch (e: Exception) {
            log.error("Failed to deregister worker {}: {}", workerId, e.message)
        }

        taskWorkerState.status.set(WorkerLifecycleStatus.STOPPED)
        log.info("Worker {} shutdown complete", workerId)
    }
}

