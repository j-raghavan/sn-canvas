package com.sncanvas.canvas

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * The fast pass while a pan or zoom drags (NFR1/NFR3): the frame as it looked
 * when the gesture began, blitted under a matrix instead of repainting every
 * element on each move sample. Fewer full-surface e-ink repaints while panning.
 *
 * The bitmap is reused across gestures, as LiveInk's stroke backdrop is, and
 * self-heals on a size change (the renderer takes the old one and gives back
 * one the right size).
 */
internal class PanPreview(
    private val renderer: CanvasRenderer,
) {
    private var snapshot: Bitmap? = null
    private var takenAt: ViewTransform? = null

    /** Caches the frame [state] draws at [width] by [height], for [drawUnder] to blit while the gesture runs. */
    fun take(
        state: CanvasState,
        width: Int,
        height: Int,
    ) {
        if (width == 0 || height == 0) return
        takenAt = state.transform
        snapshot = renderer.renderSnapshot(state.elements, state.transform, width, height, snapshot)
    }

    /**
     * Blits the cached frame onto [canvas], mapped from the transform it was
     * taken at onto [transform]. False when there is nothing cached, so the
     * caller falls back to a full repaint.
     */
    fun drawUnder(
        canvas: Canvas,
        transform: ViewTransform,
    ): Boolean {
        val base = takenAt
        val cached = snapshot
        if (base == null || cached == null) return false
        val scale = (transform.zoom / base.zoom).toFloat()
        val dx = ((base.viewportX - transform.viewportX) * transform.zoom).toFloat()
        val dy = ((base.viewportY - transform.viewportY) * transform.zoom).toFloat()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(cached, 0f, 0f, null)
        canvas.restore()
        return true
    }
}
