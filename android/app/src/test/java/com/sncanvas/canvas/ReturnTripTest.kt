package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnTripTest {
    private val note = "/storage/emulated/0/Note/a.note"

    /** A host whose note becomes the open one after [checksUntilOpen] looks, and a clock that runs tasks when told. */
    private class FakeHost(
        private var checksUntilOpen: Int,
        private val note: String,
    ) : ReturnTrip.Host {
        var shown = 0
        var waited = 0L

        override fun openNotePath(): String? = if (checksUntilOpen-- <= 0) note else "/other.note"

        override fun showCanvas() {
            shown += 1
        }
    }

    private fun trip(host: FakeHost): ReturnTrip =
        ReturnTrip(host) { delay, task ->
            host.waited += delay
            task()
        }

    @Test
    fun `waits for the note and a moment more, then brings Canvas over it`() {
        val host = FakeHost(checksUntilOpen = 3, note = note)
        var result: Boolean? = null
        trip(host).arriveOver(note) { result = it }
        assertEquals(1, host.shown)
        assertEquals(3 * ReturnTrip.CHECK_MS + ReturnTrip.SETTLE_MS, host.waited)
        assertEquals(true, result)
    }

    @Test
    fun `a note that never comes up still brings Canvas back, and says so`() {
        val host = FakeHost(checksUntilOpen = Int.MAX_VALUE, note = note)
        var result: Boolean? = null
        trip(host).arriveOver(note) { result = it }
        assertEquals(1, host.shown)
        assertEquals(ReturnTrip.CHECKS * ReturnTrip.CHECK_MS, host.waited)
        assertEquals(false, result)
    }
}
