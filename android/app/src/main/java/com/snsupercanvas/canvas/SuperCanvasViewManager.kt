package com.snsupercanvas.canvas

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event

/**
 * Exposes [SuperCanvasView] to React Native as `<SuperCanvasView>` (mounted
 * in JS via `requireNativeComponent('SuperCanvasView')` — see
 * src/ui/nativeCanvasView.ts). Registered through [SuperCanvasPackage].
 *
 * Deliberately thin: it only marshals the `toolMode` prop, the commands
 * (delete, undo, redo, duplicate, z-order, zoom, setStyle) and the
 * `onCanvasState` event that keeps the action bar and style panel in step
 * with the canvas (FR18/FR19). It also exports the e-ink gray of each colour,
 * so the style panel's swatches match what the canvas draws.
 *
 * Commands arrive through both [receiveCommand] overloads (an `Int`-keyed and
 * a `String`-keyed one; which the runtime calls depends on whether the view's
 * react tag is a Paper or a Fabric tag at the interop boundary), so both are
 * handled. Verified on device: Delete, Undo and Redo reach the view this way.
 *
 * Each view it creates receives the injected [registry] so the module can
 * find the live one (see [SuperCanvasPackage]).
 */
class SuperCanvasViewManager(
    private val registry: ActiveViewRegistry<SuperCanvasView>,
) : SimpleViewManager<SuperCanvasView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): SuperCanvasView =
        SuperCanvasView(reactContext, registry) { view, uiState -> dispatchUiState(reactContext, view, uiState) }

    @ReactProp(name = "toolMode")
    fun setToolMode(
        view: SuperCanvasView,
        toolMode: String?,
    ) {
        view.setToolMode(toolMode ?: CanvasTools.SELECT)
    }

    override fun getCommandsMap(): MutableMap<String, Int> = COMMAND_IDS.toMutableMap()

    // Deprecated upstream in favor of the String overload below, but still the one a Paper tag calls (see class doc).
    @Suppress("OVERRIDE_DEPRECATION")
    override fun receiveCommand(
        root: SuperCanvasView,
        commandId: Int,
        args: ReadableArray?,
    ) {
        val command = COMMAND_IDS.entries.firstOrNull { it.value == commandId } ?: return
        runCommand(root, command.key, args)
    }

    override fun receiveCommand(
        root: SuperCanvasView,
        commandId: String,
        args: ReadableArray?,
    ) = runCommand(root, commandId, args)

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
        mutableMapOf(EVENT_CANVAS_STATE to mapOf("registrationName" to "onCanvasState"))

    override fun getExportedViewConstants(): MutableMap<String, Any> =
        mutableMapOf("einkGrays" to StyleColor.entries.associate { it.id to "#%06X".format(StylePalette.einkGray(it)) })

    private fun runCommand(
        root: SuperCanvasView,
        command: String,
        args: ReadableArray?,
    ) {
        when (command) {
            "deleteSelected" -> root.deleteSelected()
            "undo" -> root.undo()
            "redo" -> root.redo()
            "duplicateSelected" -> root.duplicateSelected()
            "bringToFront" -> root.bringSelectedToFront()
            "sendToBack" -> root.sendSelectedToBack()
            "zoomToFit" -> root.zoomToFit()
            "zoomTo100" -> root.zoomTo100()
            "setStyle" -> if (args != null && args.size() >= 2) root.setStyle(args.getString(0).orEmpty(), args.getString(1).orEmpty())
        }
    }

    private fun dispatchUiState(
        context: ThemedReactContext,
        view: SuperCanvasView,
        uiState: CanvasUiState,
    ) {
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(context, view.id) ?: return
        val payload = Arguments.makeNativeMap(uiState.toPayload())
        dispatcher.dispatchEvent(CanvasStateEvent(UIManagerHelper.getSurfaceId(view), view.id, payload))
    }

    private class CanvasStateEvent(
        surfaceId: Int,
        viewTag: Int,
        private val payload: WritableMap,
    ) : Event<CanvasStateEvent>(surfaceId, viewTag) {
        override fun getEventName(): String = EVENT_CANVAS_STATE

        override fun getEventData(): WritableMap = payload
    }

    companion object {
        const val NAME = "SuperCanvasView"
        private const val EVENT_CANVAS_STATE = "topCanvasState"

        // Command names and the numeric ids the Int overload receives (delete/undo/redo keep 1-3).
        private val COMMAND_IDS =
            listOf(
                "deleteSelected",
                "undo",
                "redo",
                "duplicateSelected",
                "bringToFront",
                "sendToBack",
                "zoomToFit",
                "zoomTo100",
                "setStyle",
            ).withIndex().associate { (index, name) -> name to index + 1 }
    }
}
