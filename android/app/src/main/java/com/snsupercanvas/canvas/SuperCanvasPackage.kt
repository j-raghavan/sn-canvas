package com.snsupercanvas.canvas

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Native composition root: registers [SuperCanvasModule] (save/load/thumbnail
 * calls) and [SuperCanvasViewManager] (the `<SuperCanvasView>` surface), and
 * wires the one dependency they share, the [ActiveViewRegistry] that tells the
 * module which canvas view is live. Registered in MainApplication.kt.
 */
class SuperCanvasPackage : ReactPackage {
    private val canvasRegistry = ActiveViewRegistry<SuperCanvasView>()

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(SuperCanvasModule(reactContext, canvasRegistry))

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(SuperCanvasViewManager(canvasRegistry))
}
