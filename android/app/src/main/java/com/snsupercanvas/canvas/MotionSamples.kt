package com.snsupercanvas.canvas

import android.view.MotionEvent

/** Reads Android MotionEvents into the canvas's own input: contacts for [TouchArbiter], samples for the pencil. */
internal object MotionSamples {
    /** The contact at pointer [index]: the pen (or its eraser end) or a finger, with its position and pressure. */
    fun contactAt(
        event: MotionEvent,
        index: Int,
    ): Contact {
        val tool = event.getToolType(index)
        val isEraser = tool == MotionEvent.TOOL_TYPE_ERASER
        val isPen = isEraser || tool == MotionEvent.TOOL_TYPE_STYLUS
        return Contact(event.getPointerId(index), isPen, event.getX(index), event.getY(index), event.getPressure(index), isEraser)
    }

    /** Every contact in [event]. */
    fun contacts(event: MotionEvent): List<Contact> = List(event.pointerCount) { contactAt(event, it) }

    /**
     * Pointer [pointerId]'s batched positions since the previous event, oldest
     * first: the pen reports far more positions than moves, and the pencil keeps
     * them all for a smooth line.
     */
    fun history(
        event: MotionEvent,
        pointerId: Int,
        sample: (x: Float, y: Float, pressure: Float) -> Unit,
    ) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        for (h in 0 until event.historySize) {
            sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalPressure(index, h))
        }
    }
}
