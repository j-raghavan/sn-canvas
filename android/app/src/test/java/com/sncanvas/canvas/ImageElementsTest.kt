package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Images on the canvas (FR22): the copy's file name, where a new image lands, and a resize that keeps its proportions. */
class ImageElementsTest {
    private val photo = ImageData("img-1.jpg", 400, 300)
    private val placed = Element(id = "i", type = ImageElements.TYPE, x = 10.0, y = 10.0, width = 40.0, height = 30.0, image = photo)

    @Test
    fun `an image's file is a plain name in the images folder, and it has a size`() {
        assertTrue(ImageData.isFileName("img-1.png"))
        assertFalse(ImageData.isFileName("../img-1.png"))
        assertFalse(ImageData.isFileName("pictures/img-1.png"))
        assertFalse(ImageData.isFileName("img-1"))
        assertThrows(IllegalArgumentException::class.java) { ImageData("../img-1.png", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImageData("img-1.png", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImageData("img-1.png", 1, 0) }
    }

    @Test
    fun `a picked file's copy is named for the image, keeping its extension in lower case`() {
        assertEquals("img-1.jpg", ImageElements.fileNameFor("img-1", "/sdcard/Pictures/Holiday.JPG"))
        assertEquals("img-1.webp", ImageElements.fileNameFor("img-1", "/sdcard/a.b/c.webp"))
    }

    @Test
    fun `a file the canvas can't show, or an image id that isn't a plain name, gets no copy`() {
        assertNull(ImageElements.fileNameFor("img-1", "/sdcard/notes.pdf"))
        assertNull(ImageElements.fileNameFor("img-1", "/sdcard/no-extension"))
        assertNull(ImageElements.fileNameFor("../img-1", "/sdcard/a.png"))
    }

    @Test
    fun `a new image that fits shows at its own size, centred in the view`() {
        val view = ViewTransform(0.0, 0.0, 1.0).visibleRect(1000.0, 800.0)
        assertEquals(WorldRect(0.0, 0.0, 1000.0, 800.0), view)
        assertEquals(
            Element(id = "i", type = ImageElements.TYPE, x = 300.0, y = 250.0, width = 400.0, height = 300.0, image = photo),
            ImageElements.create("i", photo, view),
        )
    }

    @Test
    fun `a view with no size yet shows a new image at its own size, centred where the view is`() {
        assertBox(-100.0, -50.0, 400.0, 300.0, ImageElements.create("i", photo, WorldRect(100.0, 100.0, 100.0, 100.0)))
    }

    @Test
    fun `a view shows the world rect its size spans at its zoom`() {
        assertEquals(WorldRect(10.0, 20.0, 60.0, 45.0), ViewTransform(10.0, 20.0, 2.0).visibleRect(100.0, 50.0))
    }

    @Test
    fun `a large image shrinks to fit the view, keeping its proportions`() {
        val wide = ImageElements.create("w", ImageData("w.png", 4000, 3000), WorldRect(100.0, 100.0, 1100.0, 900.0))
        // 60% of the view's 1000 width: 4000 × 0.15.
        assertBox(300.0, 275.0, 600.0, 450.0, wide)
        val tall = ImageElements.create("t", ImageData("t.png", 300, 4000), WorldRect(0.0, 0.0, 1000.0, 800.0))
        // 60% of the view's 800 height: 4000 × 0.12.
        assertBox(482.0, 160.0, 36.0, 480.0, tall)
    }

    @Test
    fun `dragging a corner keeps the opposite corner and follows the pointer along the side it went further`() {
        assertBox(10.0, 10.0, 80.0, 60.0, ImageElements.resize(placed, photo, Corner.BOTTOM_RIGHT, Point(90.0, 20.0)))
        assertBox(10.0, 10.0, 80.0, 60.0, ImageElements.resize(placed, photo, Corner.BOTTOM_RIGHT, Point(20.0, 70.0)))
        assertBox(-30.0, -20.0, 80.0, 60.0, ImageElements.resize(placed, photo, Corner.TOP_LEFT, Point(-30.0, 0.0)))
    }

    @Test
    fun `dragging past the opposite corner flips the image to that side`() {
        assertBox(-30.0, -20.0, 40.0, 30.0, ImageElements.resize(placed, photo, Corner.BOTTOM_RIGHT, Point(-30.0, -20.0)))
    }

    @Test
    fun `a resize never leaves an image narrower than the minimum`() {
        assertBox(10.0, 10.0, ImageElements.MIN_WIDTH, 6.0, ImageElements.resize(placed, photo, Corner.BOTTOM_RIGHT, Point(10.0, 10.0)))
    }

    @Test
    fun `a rotated image resizes in its own frame, its opposite corner staying where it was`() {
        val rotated = placed.copy(rotation = Math.PI / 2)
        val fixed = CanvasCore.cornerPoints(rotated)[0]
        val resized = ImageElements.resize(rotated, photo, Corner.BOTTOM_RIGHT, rotated.toWorld(90.0, 70.0))
        assertEquals(80.0, resized.width, EPSILON)
        assertEquals(60.0, resized.height, EPSILON)
        val corner = CanvasCore.cornerPoints(resized)[0]
        assertEquals(fixed.x, corner.x, EPSILON)
        assertEquals(fixed.y, corner.y, EPSILON)
    }

    @Test
    fun `resizing on the canvas keeps an image's proportions and leaves other elements alone`() {
        val state = CanvasState(listOf(placed, boxA), 0.0, 0.0, 1.0)
        val resized = ShapeEdits.resizeElement(state, "i", Corner.BOTTOM_RIGHT, 90.0, 20.0)
        assertBox(10.0, 10.0, 80.0, 60.0, resized.elements[0])
        assertEquals(boxA, resized.elements[1])
    }

    private fun assertBox(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        element: Element,
    ) {
        assertEquals(x, element.x, EPSILON)
        assertEquals(y, element.y, EPSILON)
        assertEquals(width, element.width, EPSILON)
        assertEquals(height, element.height, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
