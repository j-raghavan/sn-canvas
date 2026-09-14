package com.snsupercanvas.canvas

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
 * delete, thumbnail rendering, and the canvas link index beside them (PRD §9 — everything that is not per-frame gesture or
 * render state, which lives in [SuperCanvasView]). It holds no canvas logic of
 * its own: persistence is [CanvasJson], rendering is the view.
 *
 * Paths always arrive fully resolved from the JS side (built from the host's
 * plugin directory); this module never derives storage locations itself. The
 * live view comes from the injected [registry] (see [SuperCanvasPackage]).
 *
 * Threading: view state is read and written on the UI thread (via `post`) and
 * file I/O runs on a background thread, so neither races the view nor blocks
 * the bridge.
 */
class SuperCanvasModule(
    reactContext: ReactApplicationContext,
    private val registry: ActiveViewRegistry<SuperCanvasView>,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = NAME

    /** Writes the live canvas's elements to [path] as JSON, creating parent directories as needed. */
    @ReactMethod
    fun saveCanvas(
        path: String,
        promise: Promise,
    ) {
        val view = registry.current()
        if (view == null) {
            promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to save from")
            return
        }
        view.post {
            val elements = view.getState().elements
            Thread {
                try {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(CanvasJson.serializeElements(elements))
                    promise.resolve(true)
                } catch (e: IOException) {
                    promise.reject(ERR_IO, e.message, e)
                }
            }.start()
        }
    }

    /**
     * Shows the canvas saved at [path] in the live view, replacing whatever it
     * showed. A missing file shows an empty canvas and resolves `false`: that is
     * a canvas never saved yet, not an error. Replacing either way matters when
     * the session switches canvases in a view that stays mounted.
     */
    @ReactMethod
    fun loadCanvas(
        path: String,
        promise: Promise,
    ) {
        Thread {
            try {
                val file = File(path)
                val exists = file.exists()
                val elements = if (exists) CanvasJson.deserializeElements(file.readText()) else emptyList()
                registry.current()?.let { view -> view.post { view.setElements(elements) } }
                promise.resolve(exists)
            } catch (e: IOException) {
                promise.reject(ERR_IO, e.message, e)
            }
        }.start()
    }

    /** Deletes the file at [path]; resolves `true` once it is gone, including when it never existed. */
    @ReactMethod
    fun deleteCanvas(
        path: String,
        promise: Promise,
    ) {
        val file = File(path)
        promise.resolve(file.delete() || !file.exists())
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
        val view = registry.current()
        if (view == null) {
            promise.reject(ERR_NO_ACTIVE_VIEW, "No active canvas view to render a thumbnail from")
            return
        }
        view.post {
            val elements = view.getState().elements
            Thread {
                try {
                    val bitmap = view.renderThumbnailBitmap(elements)
                    try {
                        val file = File(path)
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out) }
                        promise.resolve(true)
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: IOException) {
                    promise.reject(ERR_IO, e.message, e)
                }
            }.start()
        }
    }

    /** The text at [path], or null when there is no such file (the canvas link index, which the JS session reads). */
    @ReactMethod
    fun readText(
        path: String,
        promise: Promise,
    ) {
        Thread {
            try {
                val file = File(path)
                promise.resolve(if (file.exists()) file.readText() else null)
            } catch (e: IOException) {
                promise.reject(ERR_IO, e.message, e)
            }
        }.start()
    }

    /** Writes [text] to [path], creating parent directories as needed. */
    @ReactMethod
    fun writeText(
        path: String,
        text: String,
        promise: Promise,
    ) {
        Thread {
            try {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(text)
                promise.resolve(true)
            } catch (e: IOException) {
                promise.reject(ERR_IO, e.message, e)
            }
        }.start()
    }

    /** The names of the canvas files (`*.json`) in [dir], most recently saved first; none when there is no such folder. */
    @ReactMethod
    fun listCanvasFiles(
        dir: String,
        promise: Promise,
    ) {
        val names = Arguments.createArray()
        File(dir)
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .forEach { names.pushString(it.name) }
        promise.resolve(names)
    }

    companion object {
        const val NAME = "SuperCanvasModule"
        const val ERR_NO_ACTIVE_VIEW = "E_NO_ACTIVE_VIEW"
        const val ERR_IO = "E_IO"
        private const val PNG_QUALITY = 100
    }
}
