package com.snsupercanvas.canvas

/** One contact on the screen: a finger (or a resting palm) on the touch panel, or the pen on the digitizer. */
data class Contact(
    val id: Int,
    val isPen: Boolean,
    val x: Float,
    val y: Float,
)

/** What the canvas should do with the one contact it follows. */
sealed interface PointerInput {
    data class Start(
        val contact: Contact,
    ) : PointerInput

    data class Move(
        val x: Float,
        val y: Float,
    ) : PointerInput

    data class End(
        val x: Float,
        val y: Float,
    ) : PointerInput

    /** Drop the gesture in progress without committing it. */
    data object Abandon : PointerInput
}

/**
 * Decides which of several simultaneous contacts drives the canvas, so a
 * resting palm never draws. The pen always wins over fingers: a pen landing
 * while a finger is down takes over, and fingers arriving while the pen is
 * down are ignored. A second finger turns a one-finger gesture into a pinch
 * (zoomed by the view's ScaleGestureDetector), abandoning whatever the first
 * finger had started. A cancelled stream is abandoned, never committed.
 *
 * Pure Kotlin: [SuperCanvasView] maps MotionEvents onto it.
 */
class TouchArbiter {
    private var active: Contact? = null

    // Two fingers are down: nothing starts again until every finger has lifted (or the pen lands).
    private var pinching = false

    /** True while the pen drives the canvas; the view then keeps fingers away from pinch-zoom too. */
    val isPenActive: Boolean get() = active?.isPen == true

    /** A new contact touched down. */
    fun down(contact: Contact): List<PointerInput> {
        val current = active
        return when {
            contact.isPen && current?.isPen != true -> {
                pinching = false
                active = contact
                listOfNotNull(PointerInput.Abandon.takeIf { current != null }, PointerInput.Start(contact))
            }
            current == null && !pinching -> {
                active = contact
                listOf(PointerInput.Start(contact))
            }
            current != null && !current.isPen -> {
                active = null
                pinching = true
                listOf(PointerInput.Abandon)
            }
            else -> emptyList()
        }
    }

    /** Contacts moved; only the followed one matters. */
    fun move(contacts: List<Contact>): PointerInput? {
        val followed = contacts.find { it.id == active?.id }
        return followed?.let { PointerInput.Move(it.x, it.y) }
    }

    /** A contact lifted; [isLastContact] is true when it was the last one on the screen. */
    fun up(
        contact: Contact,
        isLastContact: Boolean,
    ): PointerInput? {
        if (isLastContact) pinching = false
        if (contact.id != active?.id) return null
        active = null
        return PointerInput.End(contact.x, contact.y)
    }

    /** The system took the touch stream away (for instance the pen landing on another input device). */
    fun cancel(): PointerInput? {
        val hadActive = active != null
        active = null
        pinching = false
        return if (hadActive) PointerInput.Abandon else null
    }
}
