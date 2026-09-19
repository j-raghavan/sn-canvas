package com.sncanvas.canvas

/**
 * When the back badge (#34) stays up: while the note it was shown over is still
 * the one open. Any other note, or none (the file list, another app), is the
 * user going somewhere else, so it goes.
 */
object BackBadgeRule {
    fun isStillWanted(
        linkedNote: String,
        openNote: String?,
    ): Boolean = openNote == linkedNote
}
