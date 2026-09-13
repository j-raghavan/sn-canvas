package com.snsupercanvas.canvas

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Registers both the [SuperCanvasModule] (Promise-based save/export calls)
 * and the [SuperCanvasViewManager] (the `<SuperCanvasView>` rendering
 * surface). This is the one place SuperCanvas's package differs from the
 * sn-tables TableGridPackage template it was copied from: that one returns
 * emptyList() from createViewManagers since it had no custom view.
 */
class SuperCanvasPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(SuperCanvasModule(reactContext))

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = listOf(SuperCanvasViewManager())
}
