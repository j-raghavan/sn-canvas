package com.sncanvas.canvas

import android.graphics.Canvas
import android.view.View

/**
 * The minimap over the canvas (FR15): when it shows, where it sits, and what
 * it draws. It comes up on every pan or zoom step and lingers
 * [MINIMAP_LINGER_MS] after the gesture ends, which on e-ink means one repaint
 * rather than a fade. [MinimapRenderer] paints it; this decides the rest.
 */
internal class Minimap(
    private val view: View,
    private val state: () -> CanvasState,
) {
    private val renderer = MinimapRenderer()
    private var isVisible = false
    private val hide =
        Runnable {
            isVisible = false
            view.invalidate()
        }

    /** Whether it is on screen: a touch reaches it only while it shows. */
    fun isShowing(): Boolean = isVisible

    /** Where the minimap sits over a view this size, or null when the canvas is too small to need one. */
    fun layout(): MinimapLayout? = ViewTransforms.minimapLayout(state(), view.width.toDouble(), view.height.toDouble())

    /** Shows it and cancels any pending hide; called on every pan/zoom step. */
    fun show() {
        view.removeCallbacks(hide)
        isVisible = true
    }

    /** Hides it once the gesture has been over for [MINIMAP_LINGER_MS]. */
    fun hideSoon() {
        view.removeCallbacks(hide)
        view.postDelayed(hide, MINIMAP_LINGER_MS)
    }

    /** Drops the pending hide, as the view leaves the window. */
    fun forget() = view.removeCallbacks(hide)

    /**
     * Draws it, while it shows. A drag by the minimap passes the [dragging]
     * layout it was grabbed at, so only its viewport rectangle moves under the
     * finger.
     */
    fun draw(
        canvas: Canvas,
        dragging: MinimapLayout?,
    ) {
        if (!isVisible) return
        val layout = dragging ?: layout() ?: return
        val shown = state()
        renderer.draw(canvas, shown.elements, shown.transform.visibleRect(view.width.toDouble(), view.height.toDouble()), layout)
    }

    private companion object {
        // Long enough after a pan to reach the minimap and take hold of it, since that is the only way it comes up.
        const val MINIMAP_LINGER_MS = 3000L
    }
}
