package com.snsupercanvas.canvas

// Shared fixtures for the canvas model, geometry, transform and JSON tests.

internal val baseState =
    CanvasState(
        elements =
            listOf(
                Element(id = "a", type = "rect", x = 0.0, y = 0.0, width = 10.0, height = 10.0),
                Element(id = "b", type = "rect", x = 5.0, y = 5.0, width = 10.0, height = 10.0),
            ),
        viewportX = 0.0,
        viewportY = 0.0,
        zoom = 1.0,
    )

internal val boxA = Element(id = "boxA", type = "rectangle", x = 0.0, y = 0.0, width = 10.0, height = 10.0)

internal val boxB = Element(id = "boxB", type = "rectangle", x = 100.0, y = 100.0, width = 20.0, height = 20.0)

internal val resizeBase =
    CanvasState(
        elements = listOf(Element(id = "r", type = "rectangle", x = 10.0, y = 10.0, width = 20.0, height = 20.0)),
        viewportX = 0.0,
        viewportY = 0.0,
        zoom = 1.0,
    )

internal val endpointBase =
    CanvasState(
        elements = listOf(Element(id = "arr", type = "arrow", startX = 0.0, startY = 0.0, endX = 10.0, endY = 10.0)),
        viewportX = 0.0,
        viewportY = 0.0,
        zoom = 1.0,
    )

// 100x20 bar centered at (50,10), rotated 90° clockwise: spans x 40..60, y -40..60.
internal val barRotated90 =
    Element(id = "bar", type = "rectangle", x = 0.0, y = 0.0, width = 100.0, height = 20.0, rotation = Math.PI / 2)

internal fun pointAtAngleFromUp(
    cx: Double,
    cy: Double,
    degreesClockwise: Double,
): Point {
    val a = Math.toRadians(degreesClockwise)
    return Point(cx + 100.0 * Math.sin(a), cy - 100.0 * Math.cos(a))
}
