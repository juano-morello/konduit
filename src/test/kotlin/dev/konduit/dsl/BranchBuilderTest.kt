package dev.konduit.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BranchBuilderTest {

    // ── Branch block produces correct structure ─────────────────────────

    @Test
    fun `branch block with on and otherwise produces correct BranchBlock`() {
        val builder = BranchBuilder("b")
        builder.on("LOW") {
            step("x") { handler { "low" } }
        }
        builder.on("HIGH") {
            step("y") { handler { "high" } }
        }
        builder.otherwise {
            step("z") { handler { "other" } }
        }
        val block = builder.build()

        assertInstanceOf(BranchBlock::class.java, block)
        assertEquals("b", block.name)
        assertEquals(2, block.branches.size)
        assertTrue(block.branches.containsKey("LOW"))
        assertTrue(block.branches.containsKey("HIGH"))
        assertEquals(1, block.branches["LOW"]!!.size)
        assertEquals("x", block.branches["LOW"]!![0].name)
        assertEquals(1, block.branches["HIGH"]!!.size)
        assertEquals("y", block.branches["HIGH"]!![0].name)
        assertNotNull(block.otherwise)
        assertEquals(1, block.otherwise!!.size)
        assertEquals("z", block.otherwise!![0].name)
    }

    // ── Branch in workflow ───────────────────────────────────────────────

    @Test
    fun `branch in workflow produces correct element structure`() {
        val wf = workflow("branch-test") {
            step("evaluate") { handler { mapOf("result" to "LOW") } }
            branch("risk") {
                on("LOW") { step("fast-track") { handler { "fast" } } }
                on("HIGH") { step("deep-review") { handler { "deep" } } }
                otherwise { step("manual") { handler { "manual" } } }
            }
            step("finalize") { handler { "done" } }
        }

        assertEquals(3, wf.elements.size)
        assertInstanceOf(StepDefinition::class.java, wf.elements[0])
        assertEquals("evaluate", wf.elements[0].name)
        assertInstanceOf(BranchBlock::class.java, wf.elements[1])
        val branchBlock = wf.elements[1] as BranchBlock
        assertEquals("risk", branchBlock.name)
        assertEquals(2, branchBlock.branches.size)
        assertNotNull(branchBlock.otherwise)
        assertInstanceOf(StepDefinition::class.java, wf.elements[2])
        assertEquals("finalize", wf.elements[2].name)
    }

    // ── Empty branch is rejected ────────────────────────────────────────

    @Test
    fun `empty branch is rejected`() {
        assertThrows<IllegalArgumentException> {
            BranchBuilder("empty").build()
        }
    }

    // ── Duplicate branch condition is rejected ──────────────────────────

    @Test
    fun `duplicate branch condition is rejected`() {
        assertThrows<IllegalArgumentException> {
            val builder = BranchBuilder("dup")
            builder.on("LOW") { step("a") { handler { "a" } } }
            builder.on("LOW") { step("b") { handler { "b" } } }
        }
    }

    // ── Multi-step branch ───────────────────────────────────────────────

    @Test
    fun `multi-step branch produces list with 2 steps`() {
        val builder = BranchBuilder("multi")
        builder.on("HIGH") {
            step("a") { handler { "first" } }
            step("b") { handler { "second" } }
        }
        val block = builder.build()

        val highSteps = block.branches["HIGH"]!!
        assertEquals(2, highSteps.size)
        assertEquals("a", highSteps[0].name)
        assertEquals("b", highSteps[1].name)
    }
}

