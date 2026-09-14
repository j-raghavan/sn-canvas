package com.sncanvas.canvas

/**
 * The note thumbnail's hand-drawn frame (FR12), in thumbnail pixels: what
 * sets a Canvas apart from the handwriting around it, in the same sketched
 * look as the "draw" dash ([ShapeOutline.handDrawn]). Each side is its own
 * wobbly line running a little past the corners, the way a box is sketched by
 * hand, and the box is traced twice, the second pass just inside the first.
 * The "Canvas" tag is a wobbly outline too. The wobble has a fixed seed, so
 * every thumbnail is framed alike.
 */
object ThumbnailFrame {
    /** How far the first pass sits in from the thumbnail's edge. */
    const val INSET = 14.0

    // How far each side runs past its corners.
    private const val OVERSHOOT = 8.0

    // The second pass, this much inside the first.
    private const val SECOND_PASS_INSET = 4.0
    private const val WOBBLE = 2.5
    private const val SEED = 0x5CA7
    private const val PASSES = 2

    /** The frame of a [size]-square thumbnail: its passes, first to last, each its four sides. */
    fun passes(size: Double): List<List<List<Point>>> =
        (0 until PASSES).map { pass ->
            sides(size, INSET + pass * SECOND_PASS_INSET).mapIndexed { side, (from, to) ->
                ShapeOutline.handDrawn(listOf(from, to), closed = false, seed = SEED + pass * PASSES * PASSES + side, amplitude = WOBBLE)
            }
        }

    /** The tag's outline: a [width] × [height] label, hand-drawn, its top-left corner at the origin. */
    fun tag(
        width: Double,
        height: Double,
    ): List<Point> =
        ShapeOutline.handDrawn(
            listOf(Point(0.0, 0.0), Point(width, 0.0), Point(width, height), Point(0.0, height)),
            closed = true,
            seed = SEED - 1,
            amplitude = WOBBLE / 2,
        )

    /** The four sides [inset] in from the edges, clockwise from the top, each overshooting its corners. */
    private fun sides(
        size: Double,
        inset: Double,
    ): List<Pair<Point, Point>> {
        val near = inset
        val far = size - inset
        return listOf(
            Point(near - OVERSHOOT, near) to Point(far + OVERSHOOT, near),
            Point(far, near - OVERSHOOT) to Point(far, far + OVERSHOOT),
            Point(far + OVERSHOOT, far) to Point(near - OVERSHOOT, far),
            Point(near, far + OVERSHOOT) to Point(near, near - OVERSHOOT),
        )
    }
}
