package com.sncanvas.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackBadgeRuleTest {
    private val linked = "/storage/emulated/0/Note/b.note"

    @Test
    fun `stays up while the linked note is the one open`() {
        assertTrue(BackBadgeRule.isStillWanted(linked, linked))
    }

    @Test
    fun `goes once another note, or none, is open`() {
        assertFalse(BackBadgeRule.isStillWanted(linked, "/storage/emulated/0/Note/c.note"))
        assertFalse(BackBadgeRule.isStillWanted(linked, null))
    }
}
