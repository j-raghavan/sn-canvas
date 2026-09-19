package com.sncanvas.canvas

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI

/**
 * RN bridge for the back badge ([BackBadgeWindow], #34): show it over the note a
 * link opened, hide it, and tell JS when it is tapped ([TAPPED_EVENT]), which
 * brings Canvas back. The open note comes from the plugin host's own cache,
 * readable here without JS, which does not run timers while Canvas is covered.
 */
class BackBadgeModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val window by lazy {
        BackBadgeWindow(
            reactContext.applicationContext,
            openNote = { HostDataCacheAPI.getInstance()?.currentFilePath },
            onTap = {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(TAPPED_EVENT, null)
            },
        )
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun show(
        label: String,
        notePath: String,
    ) = UiThreadUtil.runOnUiThread { window.show(label, notePath) }

    @ReactMethod
    fun hide() = UiThreadUtil.runOnUiThread { window.hide() }

    // NativeEventEmitter on the JS side asks for these; the events go out whether or not anyone listens.
    @ReactMethod
    fun addListener(
        @Suppress("UNUSED_PARAMETER") eventName: String,
    ) = Unit

    @ReactMethod
    fun removeListeners(
        @Suppress("UNUSED_PARAMETER") count: Int,
    ) = Unit

    companion object {
        const val NAME = "BackBadge"
        const val TAPPED_EVENT = "canvasBackBadgeTapped"
    }
}
