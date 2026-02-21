package dev.konduit.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class MetricsServiceTest {

    // ── Null registry (metrics disabled) ────────────────────────────────

    @Nested
    inner class NullRegistry {

        private val service = MetricsService(meterRegistry = null)

        @Test
        fun `recordExecutionStarted is no-op with null registry`() {
            assertDoesNotThrow { service.recordExecutionStarted("wf") }
        }

        @Test
        fun `recordExecutionCompleted is no-op with null registry`() {
            assertDoesNotThrow { service.recordExecutionCompleted("wf", Duration.ofSeconds(1)) }
        }

        @Test
        fun `startTimer returns null with null registry`() {
            assertNull(service.startTimer())
        }

        @Test
        fun `stopTimer with null sample is no-op`() {
            assertDoesNotThrow { service.stopTimer(null, "some.timer", "tag", "value") }
        }

        @Test
        fun `recordTaskAcquisitionDuration is no-op with null registry`() {
            assertDoesNotThrow { service.recordTaskAcquisitionDuration(Duration.ofMillis(50)) }
        }
    }

    // ── Non-null registry (metrics enabled) ─────────────────────────────

    @Nested
    inner class WithRegistry {

        private val registry = SimpleMeterRegistry()
        private val service = MetricsService(meterRegistry = registry)

        @Test
        fun `recordExecutionStarted increments counter`() {
            service.recordExecutionStarted("test-wf")
            service.recordExecutionStarted("test-wf")

            val counter = registry.find("konduit_executions_total")
                .tags("workflow", "test-wf", "status", "started")
                .counter()

            assertNotNull(counter)
            assertEquals(2.0, counter!!.count())
        }

        @Test
        fun `recordExecutionCompleted increments counter and records timer`() {
            service.recordExecutionCompleted("test-wf", Duration.ofSeconds(5))

            val counter = registry.find("konduit_executions_total")
                .tags("workflow", "test-wf", "status", "completed")
                .counter()
            assertNotNull(counter)
            assertEquals(1.0, counter!!.count())

            val timer = registry.find("konduit_executions_duration_seconds")
                .tags("workflow", "test-wf", "status", "completed")
                .timer()
            assertNotNull(timer)
            assertEquals(1, timer!!.count())
        }

        @Test
        fun `recordExecutionFailed with null duration skips timer`() {
            service.recordExecutionFailed("test-wf", null)

            val counter = registry.find("konduit_executions_total")
                .tags("workflow", "test-wf", "status", "failed")
                .counter()
            assertNotNull(counter)
            assertEquals(1.0, counter!!.count())

            val timer = registry.find("konduit_executions_duration_seconds")
                .tags("workflow", "test-wf", "status", "failed")
                .timer()
            assertNull(timer)
        }

        @Test
        fun `counter caching returns same counter for same name and tags`() {
            service.recordExecutionStarted("wf-a")
            service.recordExecutionStarted("wf-a")

            // Only one counter should exist for this tag combination
            val counters = registry.find("konduit_executions_total")
                .tags("workflow", "wf-a", "status", "started")
                .counters()
            assertEquals(1, counters.size)
            assertEquals(2.0, counters.first().count())
        }

        @Test
        fun `different tags create different counters`() {
            service.recordExecutionStarted("wf-a")
            service.recordExecutionStarted("wf-b")

            val counterA = registry.find("konduit_executions_total")
                .tags("workflow", "wf-a", "status", "started")
                .counter()
            val counterB = registry.find("konduit_executions_total")
                .tags("workflow", "wf-b", "status", "started")
                .counter()

            assertNotNull(counterA)
            assertNotNull(counterB)
            assertEquals(1.0, counterA!!.count())
            assertEquals(1.0, counterB!!.count())
        }

        @Test
        fun `recordTaskAcquisitionDuration records timer`() {
            service.recordTaskAcquisitionDuration(Duration.ofMillis(150))

            val timer = registry.find("konduit_task_acquisition_duration_seconds")
                .timer()
            assertNotNull(timer)
            assertEquals(1, timer!!.count())
        }

        @Test
        fun `recordTaskCompleted increments counter and records timer`() {
            service.recordTaskCompleted("wf", "step1", Duration.ofMillis(200))

            val counter = registry.find("konduit_tasks_total")
                .tags("workflow", "wf", "step", "step1", "status", "completed")
                .counter()
            assertNotNull(counter)
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `recordDeadLetter increments counter`() {
            service.recordDeadLetter("wf", "step1")

            val counter = registry.find("konduit_dead_letters_total")
                .tags("workflow", "wf", "step", "step1")
                .counter()
            assertNotNull(counter)
            assertEquals(1.0, counter!!.count())
        }
    }
}

