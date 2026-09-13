package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which contact drives the canvas: pen over fingers, pinch over one-finger gestures, cancel never commits. */
class TouchArbiterTest {
    private val finger = Contact(id = 0, isPen = false, x = 10f, y = 20f)
    private val secondFinger = Contact(id = 1, isPen = false, x = 300f, y = 400f)
    private val pen = Contact(id = 2, isPen = true, x = 50f, y = 60f)

    @Test
    fun `a single finger starts, moves and ends a gesture`() {
        val arbiter = TouchArbiter()
        assertEquals(listOf(PointerInput.Start(finger)), arbiter.down(finger))
        assertEquals(PointerInput.Move(15f, 25f), arbiter.move(listOf(finger.copy(x = 15f, y = 25f))))
        assertEquals(PointerInput.End(16f, 26f), arbiter.up(finger.copy(x = 16f, y = 26f), isLastContact = true))
        assertFalse(arbiter.isPenActive)
    }

    @Test
    fun `moves of contacts it does not follow are ignored`() {
        val arbiter = TouchArbiter()
        assertNull(arbiter.move(listOf(finger)))
        arbiter.down(finger)
        assertNull(arbiter.move(listOf(secondFinger)))
    }

    @Test
    fun `a second finger abandons the gesture and pinches until every finger lifts`() {
        val arbiter = TouchArbiter()
        arbiter.down(finger)
        assertEquals(listOf(PointerInput.Abandon), arbiter.down(secondFinger))
        assertNull(arbiter.move(listOf(finger, secondFinger)))
        assertEquals(emptyList<PointerInput>(), arbiter.down(finger.copy(id = 3)))
        assertNull(arbiter.up(secondFinger, isLastContact = false))
        assertNull(arbiter.up(finger, isLastContact = true))
        assertEquals(listOf(PointerInput.Start(finger)), arbiter.down(finger))
    }

    @Test
    fun `the pen takes over from a resting palm`() {
        val arbiter = TouchArbiter()
        arbiter.down(finger)
        assertFalse(arbiter.isPenActive)
        assertEquals(listOf(PointerInput.Abandon, PointerInput.Start(pen)), arbiter.down(pen))
        assertEquals(emptyList<PointerInput>(), arbiter.down(pen.copy(id = 4)))
        assertTrue(arbiter.isPenActive)
        assertEquals(PointerInput.Move(55f, 65f), arbiter.move(listOf(finger, pen.copy(x = 55f, y = 65f))))
    }

    @Test
    fun `fingers are ignored while the pen is down`() {
        val arbiter = TouchArbiter()
        assertEquals(listOf(PointerInput.Start(pen)), arbiter.down(pen))
        assertEquals(emptyList<PointerInput>(), arbiter.down(finger))
        assertNull(arbiter.up(finger, isLastContact = false))
        assertEquals(PointerInput.End(50f, 60f), arbiter.up(pen, isLastContact = true))
        assertFalse(arbiter.isPenActive)
    }

    @Test
    fun `the pen can start while two fingers pinch`() {
        val arbiter = TouchArbiter()
        arbiter.down(finger)
        arbiter.down(secondFinger)
        assertEquals(listOf(PointerInput.Start(pen)), arbiter.down(pen))
    }

    @Test
    fun `a cancelled stream is abandoned, never ended, and clears any pinch`() {
        val arbiter = TouchArbiter()
        assertNull(arbiter.cancel())
        arbiter.down(finger)
        arbiter.down(secondFinger)
        arbiter.cancel()
        arbiter.down(finger)
        assertEquals(PointerInput.Abandon, arbiter.cancel())
        assertNull(arbiter.up(finger, isLastContact = true))
    }
}
