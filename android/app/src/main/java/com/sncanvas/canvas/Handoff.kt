package com.sncanvas.canvas

import java.util.concurrent.atomic.AtomicInteger

/**
 * Work handed to a view that may not be there yet, done at most once, and
 * never after the one waiting on it gave up. A canvas load that timed out
 * waiting for the view must not land later, over whatever canvas the session
 * has moved on to (#30).
 */
class Handoff {
    private val state = AtomicInteger(PENDING)

    /** Runs [work] and returns true, unless it already ran or was [abandon]ed. */
    fun deliver(work: () -> Unit): Boolean {
        if (!state.compareAndSet(PENDING, DELIVERED)) return false
        work()
        return true
    }

    /** Gives up on the work; false when it was delivered first, and so did happen. */
    fun abandon(): Boolean = state.compareAndSet(PENDING, ABANDONED)

    private companion object {
        const val PENDING = 0
        const val DELIVERED = 1
        const val ABANDONED = 2
    }
}
