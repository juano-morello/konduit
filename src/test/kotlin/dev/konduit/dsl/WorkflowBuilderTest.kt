package dev.konduit.dsl

import dev.konduit.retry.BackoffStrategy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkflowBuilderTest {

    // ── DSL produces correct metadata ──────────────────────────────────

    @Test
    fun `workflow has correct name, version, and description`() {
        val wf = workflow("test-workflow") {
            version(2)
            description("A test workflow")
            step("s1") { handler { mapOf("ok" to true) } }
        }
        assertEquals("test-workflow", wf.name)
        assertEquals(2, wf.version)
        assertEquals("A test workflow", wf.description)
    }

    @Test
    fun `default version is 1 and description is null`() {
        val wf = workflow("minimal") {
            step("s1") { handler { null } }
        }
        assertEquals(1, wf.version)
        assertNull(wf.description)
    }

    // ── Steps are ordered correctly ────────────────────────────────────

    @Test
    fun `steps are in definition order`() {
        val wf = workflow("ordered") {
            step("first") { handler { "1" } }
            step("second") { handler { "2" } }
            step("third") { handler { "3" } }
        }
        assertEquals(listOf("first", "second", "third"), wf.steps.map { it.name })
    }

    // ── Retry policies bind to steps ───────────────────────────────────

    @Test
    fun `retry policy is bound to the correct step`() {
        val wf = workflow("retry-test") {
            step("no-retry") { handler { null } }
            step("with-retry") {
                handler { null }
                retryPolicy {
                    maxAttempts(5)
                    backoff(BackoffStrategy.EXPONENTIAL)
                    baseDelay(2000)
                    maxDelay(60_000)
                    jitter(true)
                }
            }
        }
        val noRetry = wf.steps[0].retryPolicy
        assertEquals(3, noRetry.maxAttempts) // default
        assertEquals(BackoffStrategy.FIXED, noRetry.backoffStrategy) // default

        val withRetry = wf.steps[1].retryPolicy
        assertEquals(5, withRetry.maxAttempts)
        assertEquals(BackoffStrategy.EXPONENTIAL, withRetry.backoffStrategy)
        assertEquals(2000L, withRetry.baseDelayMs)
        assertEquals(60_000L, withRetry.maxDelayMs)
        assertTrue(withRetry.jitter)
    }

    // ── Handler closures are captured ──────────────────────────────────

    @Test
    fun `handler closures are captured and callable`() {
        val wf = workflow("handler-test") {
            step("echo") {
                handler { ctx -> ctx.input }
            }
        }
        val handler = wf.steps[0].handler
        val ctx = StepContext(
            executionId = java.util.UUID.randomUUID(),
            input = mapOf("key" to "value"),
            previousOutput = null,
            executionInput = null,
            attempt = 1,
            stepName = "echo",
            workflowName = "handler-test"
        )
        val result = handler(ctx)
        assertEquals(mapOf("key" to "value"), result)
    }

    @Test
    fun `handler returning null is valid`() {
        val wf = workflow("null-handler") {
            step("noop") { handler { null } }
        }
        val ctx = StepContext(
            executionId = java.util.UUID.randomUUID(),
            input = null,
            previousOutput = null,
            executionInput = null,
            attempt = 1,
            stepName = "noop",
            workflowName = "null-handler"
        )
        assertNull(wf.steps[0].handler(ctx))
    }

    // ── Empty workflow validation ───────────────────────────────────────

    @Test
    fun `empty workflow throws on build`() {
        assertThrows<IllegalArgumentException> {
            workflow("empty") {}
        }
    }

    // ── Duplicate step names ───────────────────────────────────────────

    @Test
    fun `duplicate step names throw`() {
        assertThrows<IllegalArgumentException> {
            workflow("dup") {
                step("same") { handler { null } }
                step("same") { handler { null } }
            }
        }
    }

    // ── Blank name validation ──────────────────────────────────────────

    @Test
    fun `blank workflow name throws`() {
        assertThrows<IllegalArgumentException> {
            workflow("  ") {
                step("s1") { handler { null } }
            }
        }
    }
}

