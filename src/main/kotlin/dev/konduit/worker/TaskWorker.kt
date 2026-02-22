package dev.konduit.worker

import dev.konduit.KonduitProperties
import dev.konduit.dsl.ParallelBlock
import dev.konduit.dsl.StepContext
import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.engine.ExecutionAdvancer
import dev.konduit.engine.TaskCompletionService
import dev.konduit.observability.CorrelationFilter
import dev.konduit.observability.MetricsService
import dev.konduit.persistence.entity.TaskEntity
import dev.konduit.persistence.entity.TaskStatus
import dev.konduit.persistence.entity.WorkerStatus
import dev.konduit.persistence.repository.ExecutionRepository
import dev.konduit.persistence.repository.TaskRepository
import dev.konduit.queue.TaskQueue
import dev.konduit.retry.RetryPolicy
import jakarta.annotation.PreDestroy
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Main worker component that polls for tasks and executes them.
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
    private val taskCompletionService: TaskCompletionService,
    private val workflowRegistry: WorkflowRegistry,
    private val workerRegistry: WorkerRegistry,
    private val taskWorkerState: TaskWorkerState,
    private val properties: KonduitProperties
) {
    private val log = LoggerFactory.getLogger(TaskWorker::class.java)

    @Autowired(required = false)
    private var metricsService: MetricsService? = null

    /** Redis message listener container for pub/sub subscription. Null when Redis is disabled. */
    @Autowired(required = false)
    private var redisMessageListenerContainer: RedisMessageListenerContainer? = null

    /** Thread pool for executing tasks concurrently. */
    private lateinit var taskExecutor: java.util.concurrent.ExecutorService

    /** Scheduler for the poll loop. */
    private lateinit var pollScheduler: ScheduledExecutorService

    /** Timestamp of the last poll, used for debouncing Redis-triggered polls. */
    private val lastPollTimestamp = AtomicLong(0L)

    /** Minimum interval between Redis-triggered polls (debounce window). */
    private val redisPollDebounceMs = 50L

    /** The Redis channel topic for unsubscription during shutdown. */
    private var redisChannelTopic: ChannelTopic? = null

    /** Executor for async task completion + workflow advancement. Uses virtual threads (JVM 21). */
    private lateinit var advancementExecutor: java.util.concurrent.ExecutorService

    /** Buffer for prefetched tasks — filled asynchronously to eliminate idle windows between poll cycles. */
    private val prefetchBuffer = LinkedBlockingDeque<TaskEntity>()

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

        // Create virtual thread executor for task execution (JVM 21 / Project Loom)
        // Virtual threads are lightweight and scale to thousands without OS thread limits.
        // Concurrency is still bounded by activeTaskCount checks in pollAndExecute().
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor()

        // Create virtual thread executor for async task advancement (JVM 21 / Project Loom)
        advancementExecutor = Executors.newVirtualThreadPerTaskExecutor()

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

        // Subscribe to Redis pub/sub for instant task notifications (if Redis is available)
        subscribeToRedisChannel()

        log.info(
            "TaskWorker started: workerId={}, concurrency={}, pollInterval={}ms, redisPubSub={}",
            workerId, concurrency, pollIntervalMs, redisChannelTopic != null
        )
    }

    /**
     * Subscribe to the Redis pub/sub channel for instant task notifications.
     *
     * When a message is received on the channel, triggers an immediate [pollAndExecute]
     * with debouncing to avoid redundant polls. If Redis is not available (disabled or
     * not configured), this is a no-op and the worker falls back to fixed-interval polling.
     */
    private fun subscribeToRedisChannel() {
        val container = redisMessageListenerContainer ?: return
        val channel = properties.redis.channel
        val topic = ChannelTopic(channel)

        try {
            container.addMessageListener({ _, _ ->
                // Debounce: skip if last poll was less than 50ms ago
                val now = System.currentTimeMillis()
                val lastPoll = lastPollTimestamp.get()
                if (now - lastPoll >= redisPollDebounceMs) {
                    if (lastPollTimestamp.compareAndSet(lastPoll, now)) {
                        log.debug("Redis notification received on channel '{}', triggering immediate poll", channel)
                        pollAndExecute()
                    }
                }
            }, topic)
            redisChannelTopic = topic
            log.info("Subscribed to Redis channel '{}' for instant task notifications", channel)
        } catch (e: Exception) {
            log.warn("Failed to subscribe to Redis channel '{}': {}. Falling back to polling only.", channel, e.message)
        }
    }

    /**
     * Poll for tasks and submit them for execution if capacity is available.
     *
     * Acquires up to min(availableCapacity, batchSize) tasks per poll cycle
     * to maximize throughput and reduce per-task polling overhead.
     */
    private fun pollAndExecute() {
        try {
            if (taskWorkerState.status.get() != WorkerLifecycleStatus.RUNNING) {
                return
            }

            val concurrency = properties.worker.concurrency
            // Worker capacity is based on active tasks only — prefetch buffer is drained in Phase 1
            val availableCapacity = concurrency - taskWorkerState.activeTaskCount
            if (availableCapacity <= 0) {
                return
            }

            val workerId = taskWorkerState.workerId ?: return
            val batchSize = properties.queue.batchSize

            // Phase 1: Drain prefetch buffer first (zero-cost — no DB roundtrip)
            val prefetched = mutableListOf<TaskEntity>()
            prefetchBuffer.drainTo(prefetched, availableCapacity)

            // Phase 2: If capacity remains, synchronous acquire for the rest
            val remaining = minOf(availableCapacity - prefetched.size, batchSize)
            val freshTasks = if (remaining > 0) {
                taskQueue.acquireTasks(workerId, remaining)
            } else {
                emptyList()
            }

            val allTasks = prefetched + freshTasks

            // Dispatch all tasks to executor
            for (task in allTasks) {
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
            }

            // Phase 3: Trigger async prefetch for next poll cycle
            triggerPrefetch()
        } catch (e: Exception) {
            log.error("Error in poll loop: {}", e.message, e)
        }
    }

    /**
     * Asynchronously prefetch the next batch of tasks into the buffer.
     *
     * Runs on a virtual thread. If the buffer already has tasks, skips to avoid
     * over-acquisition. Prefetched tasks are LOCKED in the DB (with lock timeout)
     * so they're safe from other workers. The lock timeout (default 5min) is far
     * larger than the poll interval (200ms), so buffered tasks won't expire.
     */
    private fun triggerPrefetch() {
        // Skip if buffer already has tasks or worker is shutting down
        if (prefetchBuffer.isNotEmpty()) return
        if (taskWorkerState.status.get() != WorkerLifecycleStatus.RUNNING) return

        val workerId = taskWorkerState.workerId ?: return
        val batchSize = properties.queue.batchSize

        advancementExecutor.submit {
            try {
                val tasks = taskQueue.acquireTasks(workerId, batchSize)
                for (task in tasks) {
                    prefetchBuffer.offer(task)
                }
                if (tasks.isNotEmpty()) {
                    log.debug("Prefetched {} tasks into buffer", tasks.size)
                }
            } catch (e: Exception) {
                log.debug("Prefetch failed (will retry next poll): {}", e.message)
            }
        }
    }

    /**
     * Execute a single task: look up the workflow, find the step handler,
     * build a StepContext, invoke the handler, then report the result.
     */
    private fun executeTask(task: TaskEntity) {
        val taskId = requireNotNull(task.id) { "Task ID must not be null after persistence" }
        val workerId = requireNotNull(taskWorkerState.workerId) { "Worker ID must not be null during task execution" }
        val taskStartTime = Instant.now()

        log.info("Executing task {}: step '{}', attempt {}", taskId, task.stepName, task.attempt + 1)

        // Mark task as RUNNING
        task.status = TaskStatus.RUNNING
        task.startedAt = taskStartTime
        val managedTask = taskRepository.save(task)

        // Cache execution reference outside try block so error path can use it
        // without a redundant DB fetch
        var execution: dev.konduit.persistence.entity.ExecutionEntity? = null

        try {
            // Look up the execution (single fetch — passed through to advancer)
            execution = executionRepository.findById(task.executionId).orElseThrow {
                IllegalStateException("Execution ${task.executionId} not found for task $taskId")
            }

            // Set MDC context for structured logging
            CorrelationFilter.setTaskContext(
                executionId = task.executionId,
                taskId = taskId,
                workerId = workerId,
                stepName = task.stepName,
                workflowName = execution.workflowName
            )

            val workflowDef = workflowRegistry.findByNameAndVersion(
                execution.workflowName, execution.workflowVersion
            ) ?: throw IllegalStateException(
                "Workflow '${execution.workflowName}' v${execution.workflowVersion} not found in registry"
            )

            // Find the step handler
            val stepDef = workflowDef.getStep(task.stepName)

            // Determine if the previous element was a ParallelBlock.
            // If so, task.input contains the aggregated parallel outputs map.
            val parallelOutputs = run {
                val elements = workflowDef.elements
                val currentElementIndex = elements.indexOfFirst { element ->
                    when (element) {
                        is dev.konduit.dsl.StepDefinition -> element.name == task.stepName
                        is ParallelBlock -> element.steps.any { it.name == task.stepName }
                        is dev.konduit.dsl.BranchBlock -> element.allSteps().any { it.name == task.stepName }
                    }
                }
                if (currentElementIndex > 0 && elements[currentElementIndex - 1] is ParallelBlock) {
                    @Suppress("UNCHECKED_CAST")
                    (task.input as? Map<String, Any?>) ?: emptyMap()
                } else {
                    emptyMap()
                }
            }

            // Build the StepContext with untyped input (JSONB deserialized data).
            // The type parameter is erased at runtime; compile-time safety is
            // enforced at the DSL level via StepBuilder<I, O>.
            @Suppress("UNCHECKED_CAST")
            val context = StepContext<Any?>(
                executionId = task.executionId,
                input = task.input as Any?,
                previousOutput = task.input,
                executionInput = execution.input,
                attempt = task.attempt + 1,
                stepName = task.stepName,
                workflowName = execution.workflowName,
                metadata = task.metadata?.toMutableMap() ?: mutableMapOf(),
                parallelOutputs = parallelOutputs
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

            // Persist StepContext metadata if non-empty (survives retries)
            if (context.metadata.isNotEmpty()) {
                managedTask.metadata = context.metadata.toMap()
                taskRepository.save(managedTask)
            }

            // Clear MDC on worker thread before offloading to async executor
            CorrelationFilter.clearTaskContext()

            // Offload completion + advancement to async executor to free worker thread immediately.
            // The atomic transaction (completeAndAdvance) runs on a virtual thread while this
            // worker thread becomes available for the next task handler invocation.
            val capturedExecution = execution
            advancementExecutor.submit {
                try {
                    CorrelationFilter.setTaskContext(
                        executionId = task.executionId,
                        taskId = taskId,
                        workerId = workerId,
                        stepName = task.stepName,
                        workflowName = capturedExecution.workflowName
                    )

                    // Atomic complete + advance: single transaction boundary
                    taskCompletionService.completeAndAdvance(managedTask, capturedExecution, outputMap)

                    // Record task completion metrics
                    val duration = Duration.between(taskStartTime, Instant.now())
                    metricsService?.recordTaskCompleted(capturedExecution.workflowName, task.stepName, duration)

                    log.info("Task {} completed successfully: step '{}'", taskId, task.stepName)
                } catch (e: ObjectOptimisticLockingFailureException) {
                    // Another worker already completed or modified this task concurrently — safe to skip
                    log.warn(
                        "Optimistic lock conflict on task {}: step '{}' — task was already processed by another worker",
                        taskId, task.stepName
                    )
                } catch (e: Exception) {
                    log.error(
                        "Async advancement failed for task {}: step '{}', error: {}",
                        taskId, task.stepName, e.message, e
                    )
                    // Task remains in RUNNING status — orphan reclaimer or timeout checker will handle it
                } finally {
                    CorrelationFilter.clearTaskContext()
                }
            }

            // Worker thread returns immediately — available for next task handler
        } catch (e: Exception) {
            log.error("Task {} failed: step '{}', error: {}", taskId, task.stepName, e.message, e)

            // Build retry policy from task entity fields
            val retryPolicy = RetryPolicy(
                maxAttempts = task.maxAttempts,
                backoffStrategy = task.backoffStrategy,
                baseDelayMs = task.backoffBaseMs
            )

            taskQueue.failTask(taskId, e.message ?: "Unknown error", retryPolicy)

            // Record task failure metrics — use cached execution to avoid redundant DB fetch
            val duration = Duration.between(taskStartTime, Instant.now())
            val workflowName = execution?.workflowName ?: "unknown"
            metricsService?.recordTaskFailed(workflowName, task.stepName, duration)

            // If the task was dead-lettered (exhausted retries), notify the engine
            val updatedTask = taskRepository.findById(taskId).orElse(null)
            if (updatedTask != null && updatedTask.status == TaskStatus.DEAD_LETTER) {
                metricsService?.recordTaskRetry(workflowName, task.stepName)
                // Pass entities to avoid redundant lookups in the advancer.
                // If execution was loaded before the error, use it; otherwise fetch once.
                val exec = execution ?: executionRepository.findById(task.executionId).orElseThrow {
                    IllegalStateException("Execution ${task.executionId} not found for dead-lettered task $taskId")
                }
                executionAdvancer.onTaskDeadLettered(updatedTask, exec)
            } else if (updatedTask != null && updatedTask.status == TaskStatus.PENDING) {
                // Task was retried (not dead-lettered)
                metricsService?.recordTaskRetry(workflowName, task.stepName)
            }
        } finally {
            // Clear MDC context after task processing
            CorrelationFilter.clearTaskContext()
        }
    }

    /**
     * Unsubscribe from the Redis pub/sub channel during shutdown.
     * Safe to call even if never subscribed (no-op if [redisChannelTopic] is null).
     */
    private fun unsubscribeFromRedisChannel() {
        val topic = redisChannelTopic ?: return
        val container = redisMessageListenerContainer ?: return
        try {
            container.removeMessageListener(null, topic)
            redisChannelTopic = null
            log.info("Unsubscribed from Redis channel '{}'", topic.topic)
        } catch (e: Exception) {
            log.warn("Failed to unsubscribe from Redis channel '{}': {}", topic.topic, e.message)
        }
    }


    /**
     * Graceful shutdown.
     *
     * 1. Set status to DRAINING (stop accepting new tasks in poll loop)
     * 2. Unsubscribe from Redis pub/sub channel
     * 3. Shut down the poll scheduler
     * 4. Await thread pool termination up to drain timeout
     * 5. Release any remaining locked tasks
     * 6. Deregister via WorkerRegistry
     */
    @PreDestroy
    fun shutdown() {
        val workerId = taskWorkerState.workerId ?: return

        log.info("Initiating graceful shutdown for worker {}", workerId)

        // Step 1: Set status to DRAINING
        taskWorkerState.status.set(WorkerLifecycleStatus.DRAINING)
        log.info("Worker {} status set to DRAINING — no new tasks will be accepted", workerId)

        // Step 2: Unsubscribe from Redis pub/sub channel
        unsubscribeFromRedisChannel()

        // Step 3: Stop the poll scheduler
        if (::pollScheduler.isInitialized) {
            pollScheduler.shutdown()
            log.info("Worker {} poll scheduler stopped", workerId)
        }

        // Step 4: Await thread pool termination
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

        // Step 4b: Await advancement executor termination (in-flight completions)
        if (::advancementExecutor.isInitialized) {
            advancementExecutor.shutdown()
            val advTerminated = advancementExecutor.awaitTermination(drainTimeoutMs / 2, TimeUnit.MILLISECONDS)
            if (advTerminated) {
                log.info("Worker {} advancement executor drained successfully", workerId)
            } else {
                log.warn(
                    "Worker {} advancement executor did not drain within {}ms — forcing shutdown",
                    workerId, drainTimeoutMs / 2
                )
                advancementExecutor.shutdownNow()
            }
        }

        // Step 4c: Drain prefetch buffer and release prefetched tasks back to PENDING
        val bufferedTasks = mutableListOf<TaskEntity>()
        prefetchBuffer.drainTo(bufferedTasks)
        if (bufferedTasks.isNotEmpty()) {
            for (task in bufferedTasks) {
                try {
                    taskQueue.releaseTask(requireNotNull(task.id) { "Task ID must not be null for prefetched task" })
                } catch (e: Exception) {
                    log.error("Failed to release prefetched task {} during shutdown: {}", task.id, e.message)
                }
            }
            log.info("Released {} prefetched task(s) from buffer during shutdown", bufferedTasks.size)
        }

        // Step 5: Release any remaining locked tasks
        try {
            val lockedTasks = taskRepository.findByLockedBy(workerId)
            for (task in lockedTasks) {
                try {
                    taskQueue.releaseTask(requireNotNull(task.id) { "Task ID must not be null for locked task" })
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

        // Step 6: Deregister
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

