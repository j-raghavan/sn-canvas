package com.sncanvas.canvas

import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import com.ratta.supernote.pluginlib.core.PluginAppAPI
import com.ratta.supernote.pluginlib.modules.PluginModule

/**
 * RN bridge for the way back from followed links (#34): the badge over a note
 * a link opened ([BackBadgeWindow]), whose taps go to JS ([TAPPED_EVENT]), and
 * the last leg of the trip back ([ReturnTrip]): once JS has reopened a note,
 * bring Canvas up over it. That leg waits here, not in JS, which does not run
 * timers while Canvas is covered; the open note comes from the plugin host's
 * own cache.
 */
class BackBadgeModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val main = Handler(Looper.getMainLooper())

    private val pluginApp: PluginAppAPI?
        get() = reactContext.getNativeModule(PluginModule::class.java)?.pluginApp

    private val openNotePath: () -> String? = { HostDataCacheAPI.getInstance()?.currentFilePath }

    private val window by lazy {
        BackBadgeWindow(
            reactContext.applicationContext,
            openNote = openNotePath,
            onTap = {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(TAPPED_EVENT, null)
            },
        )
    }

    private val trip by lazy {
        ReturnTrip(
            object : ReturnTrip.Host {
                override fun openNotePath(): String? = openNotePath.invoke()

                override fun showCanvas(): Boolean {
                    val app = pluginApp ?: return false
                    app.showPluginView()
                    return true
                }
            },
        ) { delayMs, task -> main.postDelayed(task, delayMs) }
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun show(
        label: String,
        notePath: String,
    ) = UiThreadUtil.runOnUiThread { window.show(label, notePath) }

    @ReactMethod
    fun hide() = UiThreadUtil.runOnUiThread { window.hide() }

    /** Brings Canvas up over [notePath] once the host has it open; resolves whether it came up over that note. */
    @ReactMethod
    fun arriveOver(
        notePath: String,
        promise: Promise,
    ) = UiThreadUtil.runOnUiThread { trip.arriveOver(notePath) { promise.resolve(it) } }

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
