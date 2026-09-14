package com.snsupercanvas.canvas

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Images on the canvas (FR22). A picked image is copied into the canvas
 * folder's `images` folder under a name of its own ([fileNameFor]), so the
 * canvas never depends on the original file, and placed as a bbox element
 * ([create]) that moves, rotates and erases like any shape, and keeps its
 * proportions when resized ([resize]). Its frame is its style's outline, which
 * an image alone may leave off ([DashStyle.NONE]). Pure: the pixels are
 * [ImageCache]'s.
 */
internal object ImageElements {
    const val TYPE = "image"

    /** The narrowest a resize leaves an image, in world units. */
    const val MIN_WIDTH = 8.0

    // What the device's image picker offers (sn-plugin-lib's FileSelector).
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    // A new image takes at most this share of the view, and never shows larger than its own pixels at 100%.
    private const val VIEW_SHARE = 0.6

    /** The name the copy of the picked [source] file gets as image [id], keeping its extension; null for a file the canvas can't show. */
    fun fileNameFor(
        id: String,
        source: String,
    ): String? {
        val name = "$id.${source.substringAfterLast('.', "").lowercase()}"
        return name.takeIf { it.substringAfterLast('.') in EXTENSIONS && ImageData.isFileName(it) }
    }

    /**
     * A new image element [id] showing [image], centred in the [visible] world
     * rect and small enough to fit it; a view with no size yet (one only just
     * back on screen) shows it at its own size.
     */
    fun create(
        id: String,
        image: ImageData,
        visible: WorldRect,
    ): Element {
        val fit = minOf(visible.width * VIEW_SHARE / image.pixelWidth, visible.height * VIEW_SHARE / image.pixelHeight)
        val scale = if (fit > 0) minOf(1.0, fit) else 1.0
        val width = image.pixelWidth * scale
        val height = image.pixelHeight * scale
        return Element(
            id = id,
            type = TYPE,
            x = (visible.left + visible.right - width) / 2,
            y = (visible.top + visible.bottom - height) / 2,
            width = width,
            height = height,
            image = image,
        )
    }

    /**
     * [element], showing [image], resized by dragging [corner] to [pointer]:
     * the opposite corner stays where it is in world space, and the image
     * keeps its proportions, growing to reach the pointer along whichever side
     * it went further, in the image's own (rotated) frame.
     */
    fun resize(
        element: Element,
        image: ImageData,
        corner: Corner,
        pointer: Point,
    ): Element {
        val fixed = SuperCanvasCore.cornerPoints(element)[Corner.entries.size - 1 - corner.ordinal]
        val cos = cos(element.rotation)
        val sin = sin(element.rotation)
        val dx = pointer.x - fixed.x
        val dy = pointer.y - fixed.y
        // The drag in the image's unrotated frame.
        val alongX = dx * cos + dy * sin
        val alongY = -dx * sin + dy * cos
        val aspect = image.pixelWidth.toDouble() / image.pixelHeight
        val width = max(max(abs(alongX), abs(alongY) * aspect), MIN_WIDTH)
        val height = width / aspect
        val diagonalX = if (alongX < 0) -width else width
        val diagonalY = if (alongY < 0) -height else height
        // The new centre: halfway along that diagonal, rotated back into the world.
        val centerX = fixed.x + (diagonalX * cos - diagonalY * sin) / 2
        val centerY = fixed.y + (diagonalX * sin + diagonalY * cos) / 2
        return element.copy(x = centerX - width / 2, y = centerY - height / 2, width = width, height = height)
    }
}
