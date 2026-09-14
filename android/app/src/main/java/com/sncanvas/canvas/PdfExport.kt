package com.sncanvas.canvas

import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Writes the canvas to a one-page PDF (FR11), on the page [PdfPages] fits to
 * its content, in true colour, through the same draw routines as the screen
 * and the note thumbnail ([CanvasRenderer]), images included. It draws only
 * into its own document, so it runs off the UI thread with a renderer of its own.
 */
internal object PdfExport {
    fun write(
        elements: List<Element>,
        file: File,
        images: ImageSource,
    ) {
        val page = PdfPages.fitted(elements)
        val document = PdfDocument()
        try {
            val sheet = document.startPage(PdfDocument.PageInfo.Builder(page.widthPt, page.heightPt, 1).create())
            val renderer = CanvasRenderer(images = images)
            renderer.drawBackground(sheet.canvas, page.widthPt.toFloat(), page.heightPt.toFloat())
            renderer.drawElements(sheet.canvas, elements, page.transform, StylePalette.TRUE_COLOR)
            document.finishPage(sheet)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
