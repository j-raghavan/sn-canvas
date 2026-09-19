package com.sncanvas.canvas

/**
 * Whether the back badge (#34) stays up, asked each time the open note is
 * checked. The badge goes up as the linked note is still opening, so it waits
 * for that note to be the open one (for up to [patience] checks) before it
 * judges; after that, any other note, or none (the file list, another app), is
 * the user going somewhere else, and it goes.
 */
class BackBadgeWatch(
    private val linkedNote: String,
    private var patience: Int,
) {
    private var hasArrived = false

    fun isStillWanted(openNote: String?): Boolean {
        if (openNote == linkedNote) {
            hasArrived = true
            return true
        }
        patience -= 1
        return !hasArrived && patience > 0
    }
}
