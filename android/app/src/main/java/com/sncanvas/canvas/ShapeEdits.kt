package com.sncanvas.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * What a drag on a selected shape's handle does to it: resize from a corner,
 * rotate about its center, or move a connector's endpoint. Pure, like
 * [CanvasCore], which holds the geometry these read (corners, centers) and the
 * rest of the model's edits; a table's row edge is [TableEdits].
 */
object ShapeEdits {
    /**
     * Resizes the bbox element with the given [id] by dragging [corner] to a new
     * world-space position, keeping the opposite corner fixed; an image keeps
     * its proportions too ([ImageElements.resize]). A no-op if no element has that id.
     */
    fun resizeElement(
        state: CanvasState,
        id: String,
        corner: Corner,
        newWorldX: Double,
        newWorldY: Double,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    when {
                        element.id != id -> element
                        element.image != null -> ImageElements.resize(element, element.image, corner, Point(newWorldX, newWorldY))
                        else -> resizeCorner(element, corner, newWorldX, newWorldY)
                    }
                },
        )

    private fun resizeCorner(
        element: Element,
        corner: Corner,
        newX: Double,
        newY: Double,
    ): Element {
        if (element.rotation != 0.0) return resizeRotatedCorner(element, corner, Point(newX, newY))
        val (fixedX, fixedY) =
            when (corner) {
                Corner.TOP_LEFT -> (element.x + element.width) to (element.y + element.height)
                Corner.TOP_RIGHT -> element.x to (element.y + element.height)
                Corner.BOTTOM_LEFT -> (element.x + element.width) to element.y
                Corner.BOTTOM_RIGHT -> element.x to element.y
            }
        val left = minOf(fixedX, newX)
        val top = minOf(fixedY, newY)
        val right = maxOf(fixedX, newX)
        val bottom = maxOf(fixedY, newY)
        return element.copy(x = left, y = top, width = right - left, height = bottom - top)
    }

    /**
     * Resize for a rotated shape: the opposite corner stays fixed *in world
     * space*. The fixed-corner-to-pointer diagonal, expressed in the shape's own
     * rotated frame, gives the new width/height; its midpoint is the new center.
     */
    private fun resizeRotatedCorner(
        element: Element,
        corner: Corner,
        pointer: Point,
    ): Element {
        val opposite =
            when (corner) {
                Corner.TOP_LEFT -> Corner.BOTTOM_RIGHT
                Corner.TOP_RIGHT -> Corner.BOTTOM_LEFT
                Corner.BOTTOM_LEFT -> Corner.TOP_RIGHT
                Corner.BOTTOM_RIGHT -> Corner.TOP_LEFT
            }
        val fixed = CanvasCore.cornerPoints(element)[CanvasCore.CORNER_ORDER.indexOf(opposite)]
        val dx = pointer.x - fixed.x
        val dy = pointer.y - fixed.y
        val c = cos(-element.rotation)
        val s = sin(-element.rotation)
        val newWidth = abs(dx * c - dy * s)
        val newHeight = abs(dx * s + dy * c)
        val centerX = (fixed.x + pointer.x) / 2
        val centerY = (fixed.y + pointer.y) / 2
        return element.copy(x = centerX - newWidth / 2, y = centerY - newHeight / 2, width = newWidth, height = newHeight)
    }

    /** Rotation snaps to the nearest multiple of [ROTATION_SNAP_STEP_RAD] when within this (5°) of it. */
    private const val ROTATION_SNAP_RAD = PI / 36
    private const val ROTATION_SNAP_STEP_RAD = PI / 4

    /**
     * Rotates the bbox element with the given [id] so its rotate handle (straight
     * above the top-center at rotation 0) points at [pointer]: the angle from the
     * element's center to [pointer], plus 90°. Snapped via [snapRotation]. A no-op
     * for a missing id or a line/arrow (those already point any direction).
     */
    fun rotateElement(
        state: CanvasState,
        id: String,
        pointer: Point,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    if (element.id != id || element.hasEndpoints()) {
                        element
                    } else {
                        val center = CanvasCore.elementCenter(element)
                        val raw = atan2(pointer.y - center.y, pointer.x - center.x) + PI / 2
                        element.copy(rotation = snapRotation(raw))
                    }
                },
        )

    /**
     * Normalizes [angle] into (-π, π] and snaps it to the nearest 45° multiple
     * when within [ROTATION_SNAP_RAD], so squaring a shape back up is easy with a pen.
     */
    fun snapRotation(angle: Double): Double {
        val normalized = atan2(sin(angle), cos(angle))
        val nearest = round(normalized / ROTATION_SNAP_STEP_RAD) * ROTATION_SNAP_STEP_RAD
        val snapped = if (abs(normalized - nearest) <= ROTATION_SNAP_RAD) nearest else normalized
        return atan2(sin(snapped), cos(snapped))
    }

    /**
     * Moves one endpoint of a line/arrow element with the given [id] to a new
     * world-space position, and sets or clears its connector binding (FR7):
     * pass [targetElementId] to bind that endpoint to another element (e.g.
     * because the drag landed inside a shape), or null to make it a fixed,
     * unbound point. A no-op if no element has that id.
     */
    fun moveEndpoint(
        state: CanvasState,
        id: String,
        which: Endpoint,
        newPosition: Point,
        targetElementId: String?,
    ): CanvasState =
        state.copy(
            elements =
                state.elements.map { element ->
                    if (element.id != id) {
                        element
                    } else {
                        when (which) {
                            Endpoint.START ->
                                element.copy(startX = newPosition.x, startY = newPosition.y, startElementId = targetElementId)
                            Endpoint.END ->
                                element.copy(endX = newPosition.x, endY = newPosition.y, endElementId = targetElementId)
                        }
                    }
                },
        )
}
