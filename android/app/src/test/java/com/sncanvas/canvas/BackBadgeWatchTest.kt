package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class BackBadgeWatchTest {
    private val linked = "/storage/emulated/0/Note/b.note"
    private val other = "/storage/emulated/0/Note/a.note"

    private fun verdicts(vararg open: String?): List<Boolean> {
        val watch = BackBadgeWatch(linked, patience = 3)
        return open.map(watch::isStillWanted)
    }

    @Test
    fun `waits for a slow note to open, then stays while it is the open one`() {
        assertEquals(listOf(true, true, true, true), verdicts(other, other, linked, linked))
    }

    @Test
    fun `goes once the user leaves the linked note, for another or for none`() {
        assertEquals(listOf(true, false), verdicts(linked, other))
        assertEquals(listOf(true, false), verdicts(linked, null))
    }

    @Test
    fun `goes when the linked note never opens`() {
        assertEquals(listOf(true, true, false), verdicts(other, other, other))
    }
}
