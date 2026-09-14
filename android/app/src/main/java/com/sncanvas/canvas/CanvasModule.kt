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

    /** Writes the live canvas's elements to [path] as JSON, creating parent directories as needed. */
    @ReactMethod
    fun saveCanvas(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current() ?: return promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to save from")
        view.post {
            val elements = view.getState().elements
            inBackground(promise) {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(CanvasJson.serializeElements(elements))
                true
            }
        }
    }

    /**
     * Shows the canvas saved at [path] in the live view, its images drawn from
     * [imageDir] (FR22), replacing whatever it showed. A missing file shows an
     * empty canvas and resolves `false`: that is a canvas never saved yet, not
     * an error. Replacing either way matters when the session switches
     * canvases in a view that stays mounted.
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
        registry.current()?.let { view -> view.post { view.setElements(elements) } }
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
        const val ERR_IO = "E_IO"
        const val ERR_DENIED = "E_DENIED"
        private const val PNG_QUALITY = 100
    }
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
