package com.snsupercanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The canvas folder's files (FR12/FR13): the canvases it lists, and taking in another folder's canvases. */
class CanvasFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(
        file: File,
        text: String,
        modified: Long = 0L,
    ): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        if (modified > 0) file.setLastModified(modified)
        return file
    }

    @Test
    fun `canvases list by name, newest first, leaving out folders and other files`() {
        val dir = temp.newFolder("canvases")
        write(File(dir, "c-old.json"), "old", modified = 1_000_000L)
        write(File(dir, "c-new.json"), "new", modified = 3_000_000L)
        write(File(dir, "links.json"), "{}", modified = 2_000_000L)
        write(File(dir, "thumbnails/c-new.png"), "png")
        write(File(dir, "notes.txt"), "text")
        assertEquals(listOf("c-new.json", "links.json", "c-old.json"), CanvasFolder.canvasFiles(dir))
    }

    @Test
    fun `a folder that doesn't exist lists no canvases`() {
        assertEquals(emptyList<String>(), CanvasFolder.canvasFiles(File(temp.root, "missing")))
    }

    @Test
    fun `adopting moves every file across, thumbnails included, and removes the emptied folder`() {
        val from = temp.newFolder("plugin")
        val to = File(temp.root, "MyStyle/SnSuperCanvas")
        write(File(from, "c-1.json"), "one")
        write(File(from, "links.json"), "{\"links\":{}}")
        write(File(from, "thumbnails/c-1.png"), "png")
        assertEquals(3, CanvasFolder.adopt(from, to))
        assertEquals("one", File(to, "c-1.json").readText())
        assertEquals("png", File(to, "thumbnails/c-1.png").readText())
        assertFalse(from.exists())
    }

    @Test
    fun `adopting keeps what the destination already has, and leaves the original of it behind`() {
        val from = temp.newFolder("plugin")
        val to = temp.newFolder("shared")
        write(File(from, "links.json"), "old index")
        write(File(from, "c-2.json"), "two")
        write(File(to, "links.json"), "new index")
        assertEquals(1, CanvasFolder.adopt(from, to))
        assertEquals("new index", File(to, "links.json").readText())
        assertTrue(File(from, "links.json").exists())
        assertFalse(File(from, "c-2.json").exists())
    }

    @Test
    fun `adopting a folder that doesn't exist moves nothing`() {
        assertEquals(0, CanvasFolder.adopt(File(temp.root, "missing"), temp.newFolder("shared")))
    }
}
