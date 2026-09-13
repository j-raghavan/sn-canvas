package com.snsupercanvas.canvas

import android.graphics.Bitmap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RN bridge for the infrequent, heavier SuperCanvas operations (spec/SuperCanvas-PRD.md
 * §9 — everything that is NOT per-frame gesture/render state, which instead lives in
 * [SuperCanvasView]). Mirrors sn-tables' TableGridModule's style: contains no canvas
 * logic itself, only marshals RN <-> Kotlin (Single Responsibility).
 *
 * Registered manually in MainApplication.kt via [SuperCanvasPackage] (this module
 * lives in the app itself, not an autolinked npm package).
 *
 * v1c implements saveCanvas/loadCanvas (JSON I/O against a fully-resolved path the
 * JS side builds from `PluginManager.getPluginDirPath()` — this module intentionally
 * does not try to independently derive the plugin-private directory itself, see
 * SuperCanvasScreen.tsx). exportPdf/generateThumbnail remain v0 scaffold stubs,
 * deliberately deferred rather than half-built (exportPdf: v1.2 per PRD §13;
 * generateThumbnail: v1d's FR12 sticker/userData reopen mechanism).
 *
 * There is no existing link between this module and the live [SuperCanvasView] (they
 * were built as fully separate classes) — [SuperCanvasView.currentInstance] is the
 * single, simplest bridge for "read the live element list" / "push a loaded list
 * back in", appropriate for a plugin that only ever mounts one canvas view at a time.
 */
class SuperCanvasModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = NAME

    /**
     * Serializes the live canvas's current elements and writes them to [path]
     * (parent directories created as needed). Reads the view's state on the UI
     * thread (the only thread that mutates it) before handing the actual file
     * I/O off to a background thread, so this neither races [SuperCanvasView]'s
     * own state nor blocks the thread the JS bridge call arrived on.
     */
    @ReactMethod
    fun saveCanvas(
        path: String,
        promise: Promise,
    ) {
        val view = SuperCanvasView.currentInstance()
        if (view == null) {
            promise.reject(ERR_NO_ACTIVE_VIEW, "No active SuperCanvasView to save from")
            return
        }
        view.post {
            val elements = view.getState().elements
            Thread {
                try {
                    val json = SuperCanvasCore.serializeElements(elements)
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(json)
                    promise.resolve(true)
                } catch (e: IOException) {
                    promise.reject(ERR_IO, e.message, e)
                }
            }.start()
        }
    }

    /**
     * Reads [path] (if it exists — a missing file resolves `false`, not an error;
     * that's just the first-run case) off the calling thread, deserializes it, and
     * pushes the result into the live canvas view via [SuperCanvasView.setElements]
     * — dispatched back onto the UI thread via [android.view.View.post], since
     * that method mutates view state and calls `invalidate()`.
     */
    @ReactMethod
    fun loadCanvas(
        path: String,
        promise: Promise,
    ) {
        Thread {
            try {
                val file = File(path)
                if (!file.exists()) {
                    promise.resolve(false)
                    return@Thread
                }
                val json = file.readText()
                val elements = SuperCanvasCore.deserializeElements(json)
                val view = SuperCanvasView.currentInstance()
                if (view != null) {
                    view.post { view.setElements(elements) }
                }
                promise.resolve(true)
            } catch (e: IOException) {
                promise.reject(ERR_IO, e.message, e)
            }
        }.start()
    }

    // Parameters are unused for now because both methods below are intentional
    // v0 scaffold stubs (see class doc comment) — the signature is the real,
    // load-bearing part to get right before the implementation lands, so the
    // names are kept meaningful rather than underscored.
    @Suppress("UnusedParameter")
    @ReactMethod
    fun exportPdf(
        path: String,
        promise: Promise,
    ) {
        promise.reject(ERR_NOT_IMPLEMENTED, "exportPdf is a v0 scaffold stub — see SuperCanvasModule.kt")
    }

    /**
     * Renders the live canvas's current elements to a PNG thumbnail at [path]
     * (v1d, FR12 — the note-embedded "Save to Note" thumbnail). Reads the
     * view's state on the UI thread first, same reasoning as [saveCanvas];
     * the actual [Bitmap] render + PNG compress + file write happen off it, on
     * a background thread — none of that touches this View's own attached
     * surface (see [SuperCanvasView.renderThumbnailBitmap]'s doc), so it's
     * safe there.
     */
    @ReactMethod
    fun generateThumbnail(
        path: String,
        promise: Promise,
    ) {
        val view = SuperCanvasView.currentInstance()
        if (view == null) {
            promise.reject(ERR_NO_ACTIVE_VIEW, "No active SuperCanvasView to render a thumbnail from")
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

    companion object {
        const val NAME = "SuperCanvasModule"
        const val ERR_NOT_IMPLEMENTED = "E_NOT_IMPLEMENTED"
        const val ERR_NO_ACTIVE_VIEW = "E_NO_ACTIVE_VIEW"
        const val ERR_IO = "E_IO"
        private const val PNG_QUALITY = 100
    }
}
