package com.sncanvas.canvas

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Native composition root: registers [CanvasModule] (save/load/thumbnail
 * calls) and [CanvasViewManager] (the `<CanvasView>` surface), and
 * wires the two dependencies they share: the [ActiveViewRegistry] that tells
 * the module which canvas view is live, and the [ImageCache] that the module
 * imports images into and the view draws them from (FR22). Registered in
 * MainApplication.kt.
 */
class CanvasPackage : ReactPackage {
    private val canvasRegistry = ActiveViewRegistry<CanvasView>()
    private val images = ImageCache()

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(CanvasModule(reactContext, canvasRegistry, images))

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(CanvasViewManager(canvasRegistry, images))
}
