package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Snapshot undo/redo semantics (FR10). */
class EditHistoryTest {
    private val one = listOf(Element(id = "1", type = "rectangle", width = 1.0, height = 1.0))
    private val two = one + Element(id = "2", type = "ellipse", width = 1.0, height = 1.0)
    private val three = one + Element(id = "3", type = "rectangle", width = 2.0, height = 2.0)

    @Test
    fun `undo and redo return null when there is nothing to step to`() {
        val history = EditHistory()
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun `undo and redo step through committed snapshots`() {
        val history = EditHistory()
        history.commit(one)
        history.commit(two)
        assertEquals(one, history.undo())
        assertEquals(emptyList<Element>(), history.undo())
        assertNull(history.undo())
        assertEquals(one, history.redo())
        assertEquals(two, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun `committing after an undo discards the redo branch`() {
        val history = EditHistory()
        history.commit(one)
        history.commit(two)
        history.undo()
        history.commit(three)
        assertNull(history.redo())
        assertEquals(one, history.undo())
    }

    @Test
    fun `reset starts a fresh history at the given elements`() {
        val history = EditHistory(initial = one)
        history.commit(two)
        history.reset(three)
        assertNull(history.undo())
        assertNull(history.redo())
        history.commit(one)
        assertEquals(three, history.undo())
    }
}
