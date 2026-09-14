package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The note thumbnail's hand-drawn frame (FR12): a sketched box traced twice, and its tag. */
class ThumbnailFrameTest {
    private val size = 400.0
    private val passes = ThumbnailFrame.passes(size)

    @Test
    fun `the box is traced twice, four sides each time`() {
        assertEquals(2, passes.size)
        assertTrue(passes.all { it.size == 4 })
    }

    @Test
    fun `each side runs past its corners, as a box is sketched by hand`() {
        val top = passes.first().first()
        assertTrue(top.first().x < ThumbnailFrame.INSET)
        assertTrue(top.last().x > size - ThumbnailFrame.INSET)
        val left = passes.first().last()
        assertTrue(left.first().y > size - ThumbnailFrame.INSET)
        assertTrue(left.last().y < ThumbnailFrame.INSET)
    }

    @Test
    fun `the second pass sits inside the first, and wobbles differently`() {
        val firstTop = passes[0][0]
        val secondTop = passes[1][0]
        assertTrue(secondTop.map { it.y }.average() > firstTop.map { it.y }.average())
        assertNotEquals(firstTop.map { it.y - firstTop.first().y }, secondTop.map { it.y - secondTop.first().y })
    }

    @Test
    fun `every stroke stays on the thumbnail`() {
        val points = passes.flatten().flatten()
        assertTrue(points.all { it.x in 0.0..size && it.y in 0.0..size })
    }

    @Test
    fun `every thumbnail is framed alike`() {
        assertEquals(passes, ThumbnailFrame.passes(size))
        assertEquals(ThumbnailFrame.tag(90.0, 36.0), ThumbnailFrame.tag(90.0, 36.0))
    }

    @Test
    fun `the tag is a slightly wobbly outline of its label, walked back round to where it started`() {
        val tag = ThumbnailFrame.tag(90.0, 36.0)
        // Back within the wobble of its start; the renderer closes the gap.
        assertTrue(hypot(tag.last().x - tag.first().x, tag.last().y - tag.first().y) < 2.0)
        assertTrue(tag.all { it.x in -2.0..92.0 && it.y in -2.0..38.0 })
    }
}
