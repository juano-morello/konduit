package dev.konduit.coordination

/**
 * Interface for notifying workers that new tasks are available.
 *
 * Implementations can use Redis pub/sub, polling, or other mechanisms.
 * The notification is best-effort — workers also poll for tasks as a fallback.
 */
interface TaskNotifier {

    /**
     * Notify workers that new tasks are available for processing.
     *
     * This is a fire-and-forget operation. If the notification fails,
     * workers will still discover tasks via their regular polling interval.
     */
    fun notifyTasksAvailable()
}

