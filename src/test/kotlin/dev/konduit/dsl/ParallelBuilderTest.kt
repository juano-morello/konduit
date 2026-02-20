package dev.konduit.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParallelBuilderTest {

    // ── Parallel block produces correct structure ────────────────────────

    @Test
    fun `parallel block with 2 steps produces ParallelBlock with 2 steps`() {
        val builder = ParallelBuilder("test-parallel")
        builder.step("a") { handler { mapOf("a" to true) } }
        builder.step("b") { handler { mapOf("b" to true) } }
        val block = builder.build()

        assertInstanceOf(ParallelBlock::class.java, block)
        assertEquals("test-parallel", block.name)
        assertEquals(2, block.steps.size)
        assertEquals("a", block.steps[0].name)
        assertEquals("b", block.steps[1].name)
    }

    // ── Parallel block inside workflow ───────────────────────────────────

    @Test
    fun `parallel block inside workflow produces correct element list`() {
        val wf = workflow("test") {
            step("s1") { handler { "before" } }
            parallel {
                step("p1") { handler { "parallel-1" } }
                step("p2") { handler { "parallel-2" } }
            }
            step("s2") { handler { "after" } }
        }

        assertEquals(3, wf.elements.size)

        // First element: sequential step
        assertInstanceOf(StepDefinition::class.java, wf.elements[0])
        assertEquals("s1", wf.elements[0].name)

        // Second element: parallel block
        assertInstanceOf(ParallelBlock::class.java, wf.elements[1])
        val parallelBlock = wf.elements[1] as ParallelBlock
        assertEquals(2, parallelBlock.steps.size)
        assertEquals("p1", parallelBlock.steps[0].name)
        assertEquals("p2", parallelBlock.steps[1].name)

        // Third element: sequential step
        assertInstanceOf(StepDefinition::class.java, wf.elements[2])
        assertEquals("s2", wf.elements[2].name)
    }

    // ── Empty parallel block is rejected ─────────────────────────────────

    @Test
    fun `empty parallel block is rejected`() {
        assertThrows<IllegalArgumentException> {
            ParallelBuilder("empty").build()
        }
    }
}

