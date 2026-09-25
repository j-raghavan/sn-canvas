package com.sncanvas.canvas

import android.graphics.Bitmap
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RN bridge for the infrequent file operations on the live canvas: save, load,
 * delete, thumbnail rendering, and the canvas folder's own files (the link
 * index, the canvas list, taking in an older folder; see [CanvasFolder]). It
 * also hands the live canvas the note's pen, to set back as it closes
 * ([FirmwarePen]). Everything that is per-frame gesture or render state lives in
 * [CanvasView] (PRD §9). It holds no canvas logic of its own:
 * persistence is [CanvasJson], rendering is the view.
 *
 * Paths always arrive fully resolved from the JS side (the canvas folder in
 * MyStyle, or the plugin's own directory); this module never derives storage
 * locations itself. The live view comes from the injected [registry] (see
 * [CanvasPackage]).
 *
 * Threading: view state is read on the UI thread (via `post`) and file I/O
 * runs on a background thread, so neither races the view nor blocks the
 * bridge. Every file call settles its promise, even when the firmware refuses
 * the file (see [inBackground]).
 */
@Suppress("TooManyFunctions") // one method per bridge call
class CanvasModule(
    reactContext: ReactApplicationContext,
    private val registry: ActiveViewRegistry<CanvasView>,
    private val images: ImageCache,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = NAME

    /**
     * Writes the live canvas's elements to [path] as JSON, creating parent
     * directories as needed; only when [path] is the file the view holds
     * ([CanvasView.heldPath]). A view that holds another canvas, or none (it
     * was not there as a canvas loaded), is refused, never written: a canvas
     * file is only ever written with the canvas loaded from it (#30).
     */
    @ReactMethod
    fun saveCanvas(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to save from")
        view.post {
            val held = view.heldPath
            if (held != path) {
                promise.reject(ERR_NOT_HELD, "The canvas view holds ${held ?: "no canvas"}, not $path; not saved")
                return@post
            }
            val elements = view.getState().elements
            inBackground(promise) { writeCanvas(path, elements) }
        }
    }

    /**
     * Writes the live canvas to [path] and leaves the view where it is: a copy of what is shown,
     * taken for a file the canvas has not become yet (#62). Save to Note takes one under a new id
     * before asking the note to place a thumbnail, so that a note which refuses leaves nothing to
     * put back.
     *
     * Only ever creates. A file that is already there is somebody's canvas, and this writes the
     * canvas the view holds rather than the one that file holds, which is the write #30 exists to
     * refuse; [saveCanvas] is how a canvas is written to its own file.
     */
    @ReactMethod
    fun writeCanvasTo(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to save from")
        view.post {
            if (File(path).exists()) {
                promise.reject(ERR_NOT_HELD, "$path already holds a canvas; not written")
                return@post
            }
            val elements = view.getState().elements
            inBackground(promise) { writeCanvas(path, elements) }
        }
    }

    /**
     * Writes the live canvas to [path], a file it was not loaded from, and
     * keeps it there from now on: the scratch canvas, given its own id as it
     * goes into a note. Should the write fail, the view keeps the file it held.
     */
    @ReactMethod
    fun saveCanvasAs(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to save from")
        view.post {
            val held = view.heldPath
            val elements = view.getState().elements
            view.rebind(path)
            Thread {
                try {
                    promise.resolve(writeCanvas(path, elements))
                } catch (e: IOException) {
                    view.post { view.rebind(held) }
                    promise.reject(ERR_IO, e.message, e)
                } catch (e: SecurityException) {
                    view.post { view.rebind(held) }
                    promise.reject(ERR_DENIED, e.message, e)
                }
            }.start()
        }
    }

    /** Whether the live view holds the canvas saved at [path]: shown from it, so switching to it needs no load. */
    @ReactMethod
    fun holdsCanvas(
        path: String,
        promise: Promise,
    ) = promise.resolve(registry.current()?.heldPath == path)

    /**
     * Shows the canvas saved at [path] in the live view, its images drawn from
     * [imageDir] (FR22), replacing whatever it showed. A missing file shows an
     * empty canvas and resolves `false`: that is a canvas never saved yet, not
     * an error. Replacing either way matters when the session switches
     * canvases in a view that stays mounted.
     *
     * The view may not be there yet (it attaches as Canvas comes up, after the
     * open that loads into it), so the load waits for it, and settles only once
     * the canvas is in it. A view that never comes rejects the load, and the
     * load is called off: it must not land later, over another canvas (#30).
     */
    @ReactMethod
    fun loadCanvas(
        path: String,
        imageDir: String,
        promise: Promise,
    ) = inBackground(promise) {
        val file = File(path)
        val exists = file.exists()
        val elements = if (exists) CanvasJson.deserializeElements(file.readText()) else emptyList()
        images.folder = File(imageDir)
        val handoff = Handoff()
        val landed = CountDownLatch(1)
        registry.whenAttached { view ->
            view.post {
                handoff.deliver { view.show(path, elements) }
                landed.countDown()
            }
        }
        if (!landed.await(LOAD_WAIT_SECONDS, TimeUnit.SECONDS) && handoff.abandon()) {
            throw IOException("No canvas view came to load $path into")
        }
        exists
    }

    /** Deletes the file at [path]; resolves `true` once it is gone, including when it never existed. */
    @ReactMethod
    fun deleteCanvas(
        path: String,
        promise: Promise,
    ) = inBackground(promise) {
        val file = File(path)
        file.delete() || !file.exists()
    }

    /**
     * Renders the live canvas to a PNG thumbnail at [path] (FR12, the "Save to
     * Note" image). The bitmap render touches only a private Bitmap/Canvas pair,
     * never the view's own surface, so it is safe off the UI thread.
     */
    @ReactMethod
    fun generateThumbnail(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to render a thumbnail from")
        view.post {
            val elements = view.getState().elements
            inBackground(promise) {
                val bitmap = view.renderThumbnailBitmap(elements)
                try {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out) }
                } finally {
                    bitmap.recycle()
                }
                true
            }
        }
    }

    /**
     * Exports the live canvas to a one-page PDF at [path] (FR11), fitted to its
     * content, in true colour ([PdfExport]). It is written off the UI thread,
     * so the canvas never freezes while it is made (NFR4).
     */
    @ReactMethod
    fun exportPdf(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to export")
        view.post {
            val elements = view.getState().elements
            inBackground(promise) {
                PdfExport.write(elements, File(path), images)
                true
            }
        }
    }

    /** The text at [path], or null when there is no such file (the canvas link index, which the JS session reads). */
    @ReactMethod
    fun readText(
        path: String,
        promise: Promise,
    ) = inBackground(promise) { File(path).takeIf { it.exists() }?.readText() }

    /** Writes [text] to [path], creating parent directories as needed. */
    @ReactMethod
    fun writeText(
        path: String,
        text: String,
        promise: Promise,
    ) = inBackground(promise) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    }

    /** The names of the canvas files (`*.json`) in [dir], most recently saved first; none when there is no such folder. */
    @ReactMethod
    fun listCanvasFiles(
        dir: String,
        promise: Promise,
    ) = inBackground(promise) {
        Arguments.createArray().apply { CanvasFolder.canvasFiles(File(dir)).forEach(::pushString) }
    }

    /** Moves the files under [from] into [to], keeping any [to] already has; resolves how many moved. */
    @ReactMethod
    fun adoptFolder(
        from: String,
        to: String,
        promise: Promise,
    ) = inBackground(promise) { CanvasFolder.adopt(File(from), File(to)) }

    /**
     * Remembers the pen the note writes with, as the SDK's getPenInfo reports
     * it, for the live canvas to set back as it gives the pen back
     * ([FirmwarePen.ofNotePen]); resolves whether the firmware has a code for it.
     */
    @ReactMethod
    fun setNotePen(
        type: Double,
        width: Double,
        color: Double,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to hand the note's pen to")
        val pen = FirmwarePen.ofNotePen(type.toInt(), width.toInt(), color.toInt())
        view.post { view.setNotePen(pen) }
        promise.resolve(pen != null)
    }

    /**
     * Copies the image at [source] into [imageDir] under a name of its own and
     * puts it in the middle of the canvas, selected (FR22); resolves false,
     * adding nothing, for a file the canvas can't show. The picker is another
     * app's screen: the host hides the canvas while it shows and brings it
     * back only as the pick returns, so the image goes in once the canvas is
     * on screen again ([ActiveViewRegistry.whenAttached]).
     */
    @ReactMethod
    fun importImage(
        source: String,
        imageDir: String,
        promise: Promise,
    ) = inBackground(promise) {
        val name = ImageElements.fileNameFor("img-${java.util.UUID.randomUUID()}", source)
        val image = name?.let { images.import(File(source), File(imageDir), it) }
        image?.let { registry.whenAttached { view -> view.post { view.insertImage(it) } } }
        image != null
    }

    companion object {
        const val NAME = "CanvasModule"
        const val ERR_NO_ACTIVE_VIEW = "E_NO_ACTIVE_VIEW"
        const val ERR_NOT_HELD = "E_NOT_HELD"
        const val ERR_IO = "E_IO"
        const val ERR_DENIED = "E_DENIED"
        private const val PNG_QUALITY = 100

        /** How long a load waits for the view to come up: it attaches well within a second of Canvas showing. */
        private const val LOAD_WAIT_SECONDS = 5L
    }
}

/**
 * Writes [elements] to [path] as canvas JSON, making its folder as needed; true once written.
 *
 * Never returns false: it writes or it throws. [saveCanvasAs] puts the view's binding back only on
 * the throwing path, so a write that failed quietly would leave the view bound to a file it never
 * wrote, and the session would go on saving somewhere the drawing is not (#51).
 */
private fun writeCanvas(
    path: String,
    elements: List<Element>,
): Boolean {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(CanvasJson.serializeElements(elements))
    return true
}

/**
 * Runs [work] off the bridge thread and settles [promise] with what it
 * returns. A failure rejects the promise rather than escaping the thread,
 * which would kill the plugin: an I/O error, or the firmware refusing a file
 * in shared storage (SecurityException) when a file permission is missing.
 */
private fun inBackground(
    promise: Promise,
    work: () -> Any?,
) {
    Thread {
        try {
            promise.resolve(work())
        } catch (e: IOException) {
            promise.reject(CanvasModule.ERR_IO, e.message, e)
        } catch (e: SecurityException) {
            promise.reject(CanvasModule.ERR_DENIED, e.message, e)
        }
    }.start()
}
