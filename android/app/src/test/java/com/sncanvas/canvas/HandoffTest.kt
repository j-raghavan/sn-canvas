package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
    @Test
    fun `work is done once, and giving up after it was done says it happened`() {
        val handoff = Handoff()
        var runs = 0
        assertTrue(handoff.deliver { runs += 1 })
        assertFalse(handoff.deliver { runs += 1 })
        assertFalse(handoff.abandon())
        assertEquals(1, runs)
    }

    @Test
    fun `work given up on is never done, however late the view arrives`() {
        val handoff = Handoff()
        var runs = 0
        assertTrue(handoff.abandon())
        assertFalse(handoff.deliver { runs += 1 })
        assertEquals(0, runs)
    }
}
