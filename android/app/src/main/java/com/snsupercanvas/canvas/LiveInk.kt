package com.snsupercanvas.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View

/**
 * How a pencil stroke shows while it is being drawn (FR5/NFR2). With the
 * pencil selected, Supernote's firmware inks the stroke itself ([FirmwareInk]),
 * as fast as its own notes, and the canvas never draws it live. On pen-up the
 * canvas commits the stroke without redrawing: the firmware's ink already
 * shows it, and a redraw would repaint the panel over the ink of the next
 * stroke, still being written. The ink stays until the canvas changes some
 * other way; then it no longer matches, so it is wiped and the canvas redraws,
 * its own rendering taking over. Without the firmware service, the canvas
 * draws the stroke itself, over a snapshot taken when the pen lands.
 */
internal class LiveInk(
    private val view: View,
    private val renderer: CanvasRenderer,
) {
    private val firmware = FirmwareInk(view.context, APP_NAME)
    private var claimed = false
    private var pencil = false

    // Firmware ink is on the panel, over what the canvas draws.
    private var wet = false

    // A stroke the firmware inked is being committed: its ink stays, and the canvas doesn't redraw.
    private var keeping = false
    private var backdrop: Bitmap? = null

    private val firmwareInks: Boolean get() = claimed && pencil

    /** Whether the canvas must draw the live stroke itself. */
    val drawsLiveStroke: Boolean get() = !firmwareInks

    /** Whether a stroke the firmware inked is being committed; the canvas skips its redraw meanwhile. */
    val isKeepingInk: Boolean get() = keeping

    /**
     * Firmware ink on while the [pencil] is the tool, off for every other tool.
     * The pen is claimed when the pencil is first chosen and kept until
     * [release], since only a claimed pen's ink can be wiped. The firmware drops
     * the claim when another window takes focus, so the view calls this again
     * on every return, as on every tool change.
     */
    fun update(pencil: Boolean) {
        this.pencil = pencil
        if (!view.isAttachedToWindow || (!pencil && !claimed)) return
        claimed = firmware.setup()
        firmware.setWritable(pencil)
        // Whatever the pen inked outside the canvas (a tap on the toolbar, say) goes too.
        clear()
        Log.i(TAG, "pencil ink: ${if (firmwareInks) "firmware" else "canvas"} (firmware ${if (claimed) "claimed" else "unavailable"})")
    }

    /** The canvas leaves the screen: give the pen back to the note, writing again. */
    fun release() {
        if (claimed) {
            firmware.teardown()
            Log.i(TAG, "firmware ink released: pen back to the note")
        }
        claimed = false
        wet = false
    }

    /** The pen touches the canvas: while the firmware inks, whatever it does leaves ink. */
    fun penDown() {
        if (firmwareInks) wet = true
    }

    /** A pencil stroke begins: unless the firmware inks it, snapshot the canvas so each move redraws just the stroke over it. */
    fun beginStroke(
        elements: List<Element>,
        transform: ViewTransform,
    ) {
        if (firmwareInks || view.width == 0 || view.height == 0) return
        backdrop = renderer.renderSnapshot(elements, transform, view.width, view.height, backdrop)
    }

    /** Runs [commit], which adds the stroke the pen just drew, keeping the firmware's ink of it and skipping the redraw. */
    fun keepInk(commit: () -> Unit) {
        keeping = true
        try {
            commit()
        } finally {
            keeping = false
        }
    }

    /** The canvas changed, or the pen did something that isn't a stroke: its firmware ink goes, and the canvas redraws. */
    fun wipe() {
        if (wet && !keeping) clear()
    }

    /** Draws [stroke] over the snapshot when the canvas draws the live stroke itself; false when it doesn't. */
    fun drawLive(
        canvas: Canvas,
        stroke: Element?,
        transform: ViewTransform,
    ): Boolean {
        val snapshot = backdrop
        if (firmwareInks || snapshot == null) return false
        canvas.drawBitmap(snapshot, 0f, 0f, null)
        if (stroke != null) renderer.drawElements(canvas, listOf(stroke), transform, StylePalette.EINK)
        return true
    }

    /** Wipes the firmware ink and redraws the canvas, whose own rendering then shows every committed stroke. */
    private fun clear() {
        firmware.clearAll()
        wet = false
        view.invalidate()
    }

    private companion object {
        const val TAG = "SuperCanvasInk"
        const val APP_NAME = "SnSuperCanvas"
    }
}
