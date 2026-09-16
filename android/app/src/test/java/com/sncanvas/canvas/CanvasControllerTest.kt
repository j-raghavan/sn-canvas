package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The command layer: every command as one undoable step, the selection, the style for new elements and text editing. */
class CanvasControllerTest {
    private class Recorder : CanvasController.Listener {
        var changes = 0
        val uiStates = mutableListOf<CanvasUiState>()
        val edits = mutableListOf<CanvasController.EditTarget>()

        override fun onChanged() {
            changes++
        }

        override fun onUiState(uiState: CanvasUiState) {
            uiStates += uiState
        }

        override fun onEditText(target: CanvasController.EditTarget) {
            edits += target
        }
    }

    private val recorder = Recorder()
    private var lastId = 0
    private val controller = CanvasController(fakeMeasurer, { "id-${++lastId}" }, recorder)
    private val box = Element(id = "box", type = "rectangle", width = 10.0, height = 10.0)
    private val red = ShapeStyle.DEFAULT.copy(color = StyleColor.RED)
    private val table = TableElements.create("tb", Point(0.0, 0.0), Point(320.0, 96.0))
    private val ui get() = recorder.uiStates.last()
    private val ids get() = controller.state.elements.map { it.id }

    @Test
    fun `load replaces the content, restarts the history and clears the selection`() {
        controller.insert(box)
        controller.load(listOf(box.copy(id = "loaded")))
        assertEquals(listOf("loaded"), ids)
        assertFalse(ui.canUndo)
        assertNull(controller.selectedId)
    }

    @Test
    fun `insert adds the element in the current style, as one undoable step`() {
        controller.insert(box)
        assertEquals(
            ShapeStyle.DEFAULT,
            controller.state.elements
                .single()
                .style,
        )
        assertTrue(ui.canUndo)
        controller.undo()
        assertEquals(emptyList<String>(), ids)
        assertTrue(ui.canRedo)
        controller.redo()
        assertEquals(listOf("box"), ids)
    }

    @Test
    fun `an inserted text box is fitted to its text`() {
        controller.insert(TextElements.createText("t", Point(0.0, 0.0)))
        assertEquals(
            24.0 * 1.2 + 16,
            controller.state.elements
                .single()
                .height,
            1e-9,
        )
    }

    @Test
    fun `select publishes the selection's style and type`() {
        controller.load(listOf(box.copy(style = red)))
        controller.select("box")
        assertEquals(listOf<Any?>(true, red, "rectangle"), listOf(ui.hasSelection, ui.style, ui.selectedType))
        controller.select(null)
        assertEquals(listOf<Any?>(false, ShapeStyle.DEFAULT, null), listOf(ui.hasSelection, ui.style, ui.selectedType))
    }

    @Test
    fun `a style set with nothing selected is for new elements only, with no undo step`() {
        controller.setStyle("color", "red")
        assertFalse(ui.canUndo)
        assertEquals(StyleColor.RED, ui.style.color)
        controller.insert(box)
        assertEquals(
            StyleColor.RED,
            controller.state.elements
                .single()
                .style.color,
        )
    }

    @Test
    fun `a style set on the selection is one undo step, and repeating it adds none`() {
        controller.load(listOf(box))
        controller.select("box")
        controller.setStyle("color", "red")
        controller.setStyle("color", "red")
        assertEquals(
            StyleColor.RED,
            controller.state.elements
                .single()
                .style.color,
        )
        controller.undo()
        assertEquals(
            ShapeStyle.LEGACY,
            controller.state.elements
                .single()
                .style,
        )
        assertFalse(ui.canUndo)
    }

    @Test
    fun `a new size refits a selected text box`() {
        val text = TextElements.fit(TextElements.createText("t", Point(0.0, 0.0)).copy(text = "x".repeat(30)), fakeMeasurer)
        controller.load(listOf(text))
        controller.select("t")
        controller.setStyle("size", "xl")
        assertTrue(
            controller.state.elements
                .single()
                .height > text.height,
        )
    }

    @Test
    fun `deleteSelected removes the selection and clears it, with nothing selected it does nothing`() {
        controller.load(listOf(box))
        controller.deleteSelected()
        assertEquals(listOf("box"), ids)
        controller.select("box")
        controller.deleteSelected()
        assertEquals(emptyList<String>(), ids)
        assertNull(controller.selectedId)
    }

    @Test
    fun `clearCanvas empties the canvas as one undoable step and clears the selection`() {
        controller.load(listOf(box, box.copy(id = "b2")))
        controller.select("box")
        controller.clearCanvas()
        assertEquals(emptyList<String>(), ids)
        assertNull(controller.selectedId)
        assertFalse(ui.hasContent)
        controller.undo()
        assertEquals(listOf("box", "b2"), ids)
        assertTrue(ui.hasContent)
    }

    @Test
    fun `clearCanvas on an empty canvas does nothing`() {
        controller.load(emptyList())
        controller.clearCanvas()
        assertFalse(ui.canUndo)
    }

    @Test
    fun `duplicateSelected adds an offset copy and selects it`() {
        controller.load(listOf(box))
        controller.select("box")
        controller.duplicateSelected(16.0)
        assertEquals(listOf("box", "id-1"), ids)
        assertEquals("id-1", controller.selectedId)
        assertEquals(
            16.0,
            controller.state.elements
                .last()
                .x,
            0.0,
        )
    }

    @Test
    fun `bring to front and send to back reorder the selection`() {
        controller.load(listOf(box, box.copy(id = "other")))
        controller.select("box")
        controller.bringSelectedToFront()
        assertEquals(listOf("other", "box"), ids)
        controller.sendSelectedToBack()
        assertEquals(listOf("box", "other"), ids)
    }

    @Test
    fun `with nothing selected, selection commands do nothing`() {
        controller.load(listOf(box, table))
        controller.bringSelectedToFront()
        controller.sendSelectedToBack()
        controller.duplicateSelected(16.0)
        controller.addTableRow()
        assertEquals(listOf("box", "tb"), ids)
        assertFalse(ui.canUndo)
    }

    @Test
    fun `placing a text box opens the editor, and the box joins the history once it has text`() {
        controller.placeText(CanvasTools.TEXT, Point(5.0, 5.0))
        assertEquals(listOf("id-1"), ids)
        assertEquals(CanvasController.EditTarget("id-1"), controller.editing)
        assertEquals(listOf(CanvasController.EditTarget("id-1")), recorder.edits)
        assertEquals("id-1", controller.selectedId)
        assertFalse(ui.canUndo)
        controller.finishEdit("hello")
        assertEquals(
            "hello",
            controller.state.elements
                .single()
                .text,
        )
        assertNull(controller.editing)
        controller.undo()
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `a text box left blank disappears without an undo step`() {
        controller.placeText(CanvasTools.TEXT, Point(5.0, 5.0))
        controller.finishEdit("   ")
        assertEquals(emptyList<String>(), ids)
        assertNull(controller.selectedId)
        assertFalse(ui.canUndo)
    }

    @Test
    fun `the note tool places a sticky note`() {
        controller.placeText(CanvasTools.NOTE, Point(100.0, 100.0))
        assertEquals(
            "note",
            controller.state.elements
                .single()
                .type,
        )
    }

    @Test
    fun `finishing an edit that changed nothing adds no undo step, and with no edit it does nothing`() {
        controller.finishEdit("ignored")
        controller.load(listOf(TextElements.fit(TextElements.createText("t", Point(0.0, 0.0)).copy(text = "a"), fakeMeasurer)))
        controller.beginEdit(CanvasController.EditTarget("t"))
        controller.finishEdit("a")
        assertFalse(ui.canUndo)
        assertEquals("t", controller.selectedId)
    }

    @Test
    fun `editing a table cell sets that cell`() {
        controller.load(listOf(table))
        controller.beginEdit(CanvasController.EditTarget("tb", 1))
        controller.finishEdit("B")
        assertEquals(
            listOf("", "B", "", ""),
            controller.state.elements
                .single()
                .table
                ?.cells,
        )
        assertTrue(ui.canUndo)
    }

    @Test
    fun `erase removes several elements in one step and clears an erased selection`() {
        controller.load(listOf(box, box.copy(id = "b2"), box.copy(id = "b3")))
        controller.select("b2")
        controller.erase(setOf("box", "b2"))
        assertEquals(listOf("b3"), ids)
        assertNull(controller.selectedId)
        controller.undo()
        assertEquals(listOf("box", "b2", "b3"), ids)
    }

    @Test
    fun `erasing nothing does nothing`() {
        controller.load(listOf(box))
        controller.erase(emptySet())
        assertFalse(ui.canUndo)
    }

    @Test
    fun `row and column commands act on the selected table`() {
        controller.load(listOf(table))
        controller.select("tb")
        controller.addTableRow()
        controller.addTableColumn()
        val grown =
            controller.state.elements
                .single()
                .table
        assertEquals(listOf(3, 3), listOf(grown?.rows, grown?.cols))
        controller.removeTableRow()
        controller.removeTableColumn()
        assertEquals(
            TableData.empty(2, 2),
            controller.state.elements
                .single()
                .table,
        )
    }

    @Test
    fun `viewport changes redraw without an undo step or a new UI state`() {
        controller.load(emptyList())
        val changesBefore = recorder.changes
        val statesBefore = recorder.uiStates.size
        controller.setViewport(ViewTransform(10.0, 20.0, 2.0))
        assertEquals(listOf(10.0, 20.0, 2.0), listOf(controller.state.viewportX, controller.state.viewportY, controller.state.zoom))
        assertEquals(changesBefore + 1, recorder.changes)
        assertEquals(statesBefore, recorder.uiStates.size)
        assertFalse(ui.canUndo)
    }

    @Test
    fun `fitted refits an edited state's text`() {
        val text = TextElements.createText("t", Point(0.0, 0.0)).copy(text = "x".repeat(40))
        val edited = CanvasState(listOf(text), 0.0, 0.0, 1.0)
        assertEquals(
            2 * 24.0 * 1.2 + 16,
            controller
                .fitted(edited, "t")
                .elements
                .single()
                .height,
            1e-9,
        )
    }

    @Test
    fun `an unchanged UI state is not sent again unless republished`() {
        controller.load(emptyList())
        val sent = recorder.uiStates.size
        controller.select(null)
        assertEquals(sent, recorder.uiStates.size)
        controller.republish()
        assertEquals(sent + 1, recorder.uiStates.size)
    }

    @Test
    fun `undo and redo with nothing to step to do nothing`() {
        controller.undo()
        controller.redo()
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `a selection that no longer exists counts as none`() {
        controller.load(listOf(box))
        controller.select("ghost")
        assertFalse(ui.hasSelection)
    }

    @Test
    fun `erasing other elements keeps the selection`() {
        controller.load(listOf(box, box.copy(id = "b2")))
        controller.select("box")
        controller.erase(setOf("b2"))
        assertEquals("box", controller.selectedId)
    }

    @Test
    fun `an inserted image lands in the middle of the view, in the current style and selected, as one undoable step`() {
        controller.setStyle("color", "red")
        controller.insertImage(ImageData("img-1.png", 100, 50), WorldRect(0.0, 0.0, 1000.0, 800.0))
        val image = controller.selected
        assertEquals(listOf("id-1"), ids)
        assertEquals(450.0, image?.x)
        assertEquals(375.0, image?.y)
        assertEquals(StyleColor.RED, image?.style?.color)
        assertEquals(ImageElements.TYPE, ui.selectedType)
        assertTrue(ui.canUndo)
    }

    @Test
    fun `an outline of none goes to the selected image alone, never to what is drawn next`() {
        controller.insertImage(ImageData("img-1.png", 100, 50), WorldRect(0.0, 0.0, 1000.0, 800.0))
        controller.setStyle("dash", "none")
        assertEquals(DashStyle.NONE, controller.selected?.style?.dash)
        assertEquals(DashStyle.DRAW, controller.currentStyle.dash)
        controller.setStyle("dash", "dotted")
        assertEquals(DashStyle.DOTTED, controller.currentStyle.dash)
    }
}
