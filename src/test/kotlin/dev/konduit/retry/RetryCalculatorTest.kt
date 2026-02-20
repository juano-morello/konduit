package dev.konduit.retry

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class RetryCalculatorTest {

    // ── FIXED backoff ──────────────────────────────────────────────────

    @Test
    fun `FIXED backoff returns baseDelayMs for all attempts`() {
        val policy = RetryPolicy(maxAttempts = 5, backoffStrategy = BackoffStrategy.FIXED, baseDelayMs = 500)
        for (attempt in 1..5) {
            assertEquals(500L, RetryCalculator.computeDelay(policy, attempt))
        }
    }

    // ── LINEAR backoff ─────────────────────────────────────────────────

    @Test
    fun `LINEAR backoff returns baseDelayMs times attempt`() {
        val policy = RetryPolicy(maxAttempts = 5, backoffStrategy = BackoffStrategy.LINEAR, baseDelayMs = 1000)
        assertEquals(1000L, RetryCalculator.computeDelay(policy, 1))
        assertEquals(2000L, RetryCalculator.computeDelay(policy, 2))
        assertEquals(3000L, RetryCalculator.computeDelay(policy, 3))
    }

    // ── EXPONENTIAL backoff ────────────────────────────────────────────

    @Test
    fun `EXPONENTIAL backoff returns baseDelayMs times 2 to the power of attempt minus 1`() {
        val policy = RetryPolicy(
            maxAttempts = 10, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 1000, maxDelayMs = 1_000_000
        )
        assertEquals(1000L, RetryCalculator.computeDelay(policy, 1))   // 1000 * 2^0
        assertEquals(2000L, RetryCalculator.computeDelay(policy, 2))   // 1000 * 2^1
        assertEquals(4000L, RetryCalculator.computeDelay(policy, 3))   // 1000 * 2^2
        assertEquals(8000L, RetryCalculator.computeDelay(policy, 4))   // 1000 * 2^3
    }

    // ── Jitter ─────────────────────────────────────────────────────────

    @Test
    fun `EXPONENTIAL with jitter returns value in range 0 to computed delay`() {
        val policy = RetryPolicy(
            maxAttempts = 5, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 1000, maxDelayMs = 1_000_000, jitter = true
        )
        val maxExpected = 1000L // 1000 * 2^0 for attempt 1
        repeat(100) {
            val delay = RetryCalculator.computeDelay(policy, 1)
            assertTrue(delay in 0..maxExpected, "Delay $delay should be in [0, $maxExpected]")
        }
    }

    @Test
    fun `EXPONENTIAL with jitter produces varying delays`() {
        val policy = RetryPolicy(
            maxAttempts = 5, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 10_000, maxDelayMs = 1_000_000, jitter = true
        )
        val delays = (1..50).map { RetryCalculator.computeDelay(policy, 3) }.toSet()
        assertTrue(delays.size > 1, "Jitter should produce varying delays, got: $delays")
    }

    @Test
    fun `jitter with seeded random produces deterministic result`() {
        val policy = RetryPolicy(
            maxAttempts = 5, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 1000, maxDelayMs = 1_000_000, jitter = true
        )
        val r1 = Random(42)
        val r2 = Random(42)
        assertEquals(
            RetryCalculator.computeDelay(policy, 2, r1),
            RetryCalculator.computeDelay(policy, 2, r2)
        )
    }

    // ── Max delay cap ──────────────────────────────────────────────────

    @Test
    fun `computed delay never exceeds maxDelayMs`() {
        val policy = RetryPolicy(
            maxAttempts = 20, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 1000, maxDelayMs = 5000
        )
        // attempt 10 → 1000 * 2^9 = 512_000, should be capped at 5000
        assertEquals(5000L, RetryCalculator.computeDelay(policy, 10))
    }

    @Test
    fun `LINEAR delay capped at maxDelayMs`() {
        val policy = RetryPolicy(
            maxAttempts = 100, backoffStrategy = BackoffStrategy.LINEAR,
            baseDelayMs = 1000, maxDelayMs = 5000
        )
        assertEquals(5000L, RetryCalculator.computeDelay(policy, 10)) // 1000*10 = 10000 → capped 5000
    }

    // ── shouldRetry ────────────────────────────────────────────────────

    @Test
    fun `shouldRetry returns true when attempt less than maxAttempts`() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertTrue(RetryCalculator.shouldRetry(policy, 1))
        assertTrue(RetryCalculator.shouldRetry(policy, 2))
    }

    @Test
    fun `shouldRetry returns false when attempt equals or exceeds maxAttempts`() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertFalse(RetryCalculator.shouldRetry(policy, 3))
        assertFalse(RetryCalculator.shouldRetry(policy, 4))
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `attempt 0 throws IllegalArgumentException`() {
        val policy = RetryPolicy(maxAttempts = 3, backoffStrategy = BackoffStrategy.FIXED, baseDelayMs = 1000)
        assertThrows<IllegalArgumentException> {
            RetryCalculator.computeDelay(policy, 0)
        }
    }

    @Test
    fun `very large attempt number does not overflow`() {
        val policy = RetryPolicy(
            maxAttempts = 1000, backoffStrategy = BackoffStrategy.EXPONENTIAL,
            baseDelayMs = 1000, maxDelayMs = 300_000
        )
        // Should not throw and should be capped at maxDelayMs
        val delay = RetryCalculator.computeDelay(policy, 100)
        assertTrue(delay <= 300_000L, "Delay $delay should be <= maxDelayMs")
        assertTrue(delay >= 0, "Delay should not be negative (overflow)")
    }
}

