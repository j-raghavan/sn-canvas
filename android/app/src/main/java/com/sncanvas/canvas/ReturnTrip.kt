package com.sncanvas.canvas

/**
 * The last leg of a trip back from a followed link (#34): once the note it
 * goes back to has been asked for, bring Canvas up over it, so Canvas sits over
 * its own note again (Close lands there, and Save to Note puts the thumbnail
 * there).
 *
 * The order matters and can't be left to the host: a note opens as an activity
 * that arrives after the open call returns and lands on top of whatever came up
 * first. So Canvas waits until the host reports the note as the open one, and a
 * moment more for it to settle, before coming up. It runs natively on [schedule]
 * (the main looper on the device), which keeps running while Canvas is covered;
 * JS timers do not.
 */
class ReturnTrip(
    private val host: Host,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
) {
    /** What the trip needs from the plugin host. */
    interface Host {
        /** The note the host has open, or null when none is. */
        fun openNotePath(): String?

        /** Brings Canvas up; false when it could not. */
        fun showCanvas(): Boolean
    }

    /**
     * Brings Canvas up over [note] once it is open; [done] says whether it came up over that note. When it
     * didn't (the note never opened, or Canvas would not come up), Canvas is left for the caller to bring up.
     */
    fun arriveOver(
        note: String,
        done: (backOverNote: Boolean) -> Unit,
    ) = awaitNote(note, CHECKS, done)

    private fun awaitNote(
        note: String,
        checksLeft: Int,
        done: (Boolean) -> Unit,
    ) {
        when {
            host.openNotePath() == note -> schedule(SETTLE_MS) { done(host.showCanvas()) }
            checksLeft == 0 -> done(false)
            else -> schedule(CHECK_MS) { awaitNote(note, checksLeft - 1, done) }
        }
    }

    companion object {
        /** How often, and how many times, the host is asked whether the note is open yet: three seconds in all. */
        const val CHECK_MS = 100L
        const val CHECKS = 30

        /** From the host naming the note to its page being on screen, measured on device at well under this. */
        const val SETTLE_MS = 400L
    }
}
