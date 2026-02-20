package dev.konduit.engine

import dev.konduit.persistence.entity.ExecutionEntity
import dev.konduit.persistence.entity.ExecutionStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ExecutionStateMachineTest {

    private val sm = ExecutionStateMachine()

    // ── Valid transitions ──────────────────────────────────────────────

    @Test
    fun `PENDING to RUNNING is valid`() {
        val exec = executionWith(ExecutionStatus.PENDING)
        sm.transition(exec, ExecutionStatus.RUNNING)
        assertEquals(ExecutionStatus.RUNNING, exec.status)
    }

    @Test
    fun `PENDING to CANCELLED is valid`() {
        val exec = executionWith(ExecutionStatus.PENDING)
        sm.transition(exec, ExecutionStatus.CANCELLED)
        assertEquals(ExecutionStatus.CANCELLED, exec.status)
    }

    @Test
    fun `RUNNING to COMPLETED is valid`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.COMPLETED)
        assertEquals(ExecutionStatus.COMPLETED, exec.status)
    }

    @Test
    fun `RUNNING to FAILED is valid`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.FAILED)
        assertEquals(ExecutionStatus.FAILED, exec.status)
    }

    @Test
    fun `RUNNING to TIMED_OUT is valid`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.TIMED_OUT)
        assertEquals(ExecutionStatus.TIMED_OUT, exec.status)
    }

    @Test
    fun `RUNNING to CANCELLED is valid`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.CANCELLED)
        assertEquals(ExecutionStatus.CANCELLED, exec.status)
    }

    // ── Invalid transitions ────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(ExecutionStatus::class)
    fun `COMPLETED to any status throws`(target: ExecutionStatus) {
        val exec = executionWith(ExecutionStatus.COMPLETED)
        assertThrows<IllegalStateException> { sm.transition(exec, target) }
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus::class)
    fun `FAILED to any status throws`(target: ExecutionStatus) {
        val exec = executionWith(ExecutionStatus.FAILED)
        assertThrows<IllegalStateException> { sm.transition(exec, target) }
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus::class)
    fun `TIMED_OUT to any status throws`(target: ExecutionStatus) {
        val exec = executionWith(ExecutionStatus.TIMED_OUT)
        assertThrows<IllegalStateException> { sm.transition(exec, target) }
    }

    @ParameterizedTest
    @EnumSource(ExecutionStatus::class)
    fun `CANCELLED to any status throws`(target: ExecutionStatus) {
        val exec = executionWith(ExecutionStatus.CANCELLED)
        assertThrows<IllegalStateException> { sm.transition(exec, target) }
    }

    @Test
    fun `RUNNING to PENDING throws`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        assertThrows<IllegalStateException> { sm.transition(exec, ExecutionStatus.PENDING) }
    }

    @Test
    fun `PENDING to COMPLETED throws`() {
        val exec = executionWith(ExecutionStatus.PENDING)
        assertThrows<IllegalStateException> { sm.transition(exec, ExecutionStatus.COMPLETED) }
    }

    // ── Timestamps ─────────────────────────────────────────────────────

    @Test
    fun `transition to RUNNING sets startedAt`() {
        val exec = executionWith(ExecutionStatus.PENDING)
        assertNull(exec.startedAt)
        sm.transition(exec, ExecutionStatus.RUNNING)
        assertNotNull(exec.startedAt)
    }

    @Test
    fun `transition to COMPLETED sets completedAt`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        assertNull(exec.completedAt)
        sm.transition(exec, ExecutionStatus.COMPLETED)
        assertNotNull(exec.completedAt)
    }

    @Test
    fun `transition to FAILED sets completedAt`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.FAILED)
        assertNotNull(exec.completedAt)
    }

    @Test
    fun `transition to TIMED_OUT sets completedAt`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.TIMED_OUT)
        assertNotNull(exec.completedAt)
    }

    @Test
    fun `transition to CANCELLED sets completedAt`() {
        val exec = executionWith(ExecutionStatus.RUNNING)
        sm.transition(exec, ExecutionStatus.CANCELLED)
        assertNotNull(exec.completedAt)
    }

    // ── isTerminal ─────────────────────────────────────────────────────

    @Test
    fun `COMPLETED, FAILED, TIMED_OUT, CANCELLED are terminal`() {
        assertTrue(sm.isTerminal(ExecutionStatus.COMPLETED))
        assertTrue(sm.isTerminal(ExecutionStatus.FAILED))
        assertTrue(sm.isTerminal(ExecutionStatus.TIMED_OUT))
        assertTrue(sm.isTerminal(ExecutionStatus.CANCELLED))
    }

    @Test
    fun `PENDING and RUNNING are not terminal`() {
        assertFalse(sm.isTerminal(ExecutionStatus.PENDING))
        assertFalse(sm.isTerminal(ExecutionStatus.RUNNING))
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private fun executionWith(status: ExecutionStatus) = ExecutionEntity(status = status)
}

