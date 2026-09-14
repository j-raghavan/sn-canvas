package com.sncanvas.canvas

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event

/**
 * Exposes [CanvasView] to React Native as `<CanvasView>` (mounted
 * in JS via `requireNativeComponent('CanvasView')` — see
 * src/ui/nativeCanvasView.ts). Registered through [CanvasPackage].
 *
 * Deliberately thin: it only marshals the `toolMode` prop, the commands (to
 * the view's [CanvasController]), and two events: `onCanvasState`, which keeps
 * the action bar and style panel in step with the canvas (FR18/FR19), and
 * `onEditText`, which opens the keyboard editor over text (FR6/FR24). It also
 * exports each colour's e-ink gray, so the style panel's swatches match what
 * the canvas draws.
 *
 * Commands arrive through both [receiveCommand] overloads (an `Int`-keyed and
 * a `String`-keyed one; which the runtime calls depends on whether the view's
 * react tag is a Paper or a Fabric tag at the interop boundary), so both are
 * handled. Verified on device: Delete, Undo and Redo reach the view this way.
 *
 * Each view it creates receives the injected [registry] so the module can
 * find the live one (see [CanvasPackage]).
 */
class CanvasViewManager(
    private val registry: ActiveViewRegistry<CanvasView>,
    private val images: ImageSource,
) : SimpleViewManager<CanvasView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): CanvasView =
        CanvasView(
            reactContext,
            registry,
            object : CanvasView.Events {
                override fun onUiState(
                    view: CanvasView,
                    uiState: CanvasUiState,
                ) = dispatch(reactContext, view, EVENT_CANVAS_STATE, uiState.toPayload())

                override fun onEditText(
                    view: CanvasView,
                    request: TextEditRequest,
                ) = dispatch(reactContext, view, EVENT_EDIT_TEXT, request.toPayload())
            },
            images,
        )

    @ReactProp(name = "toolMode")
    fun setToolMode(
        view: CanvasView,
        toolMode: String?,
    ) {
        view.setToolMode(toolMode ?: CanvasTools.SELECT)
    }

    override fun getCommandsMap(): MutableMap<String, Int> = COMMAND_IDS.toMutableMap()

    // Deprecated upstream in favor of the String overload below, but still the one a Paper tag calls (see class doc).
    @Suppress("OVERRIDE_DEPRECATION")
    override fun receiveCommand(
        root: CanvasView,
        commandId: Int,
        args: ReadableArray?,
    ) {
        val command = COMMAND_IDS.entries.firstOrNull { it.value == commandId } ?: return
        runCommand(root, command.key, args)
    }

    override fun receiveCommand(
        root: CanvasView,
        commandId: String,
        args: ReadableArray?,
    ) = runCommand(root, commandId, args)

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
        mutableMapOf(
            EVENT_CANVAS_STATE to mapOf("registrationName" to "onCanvasState"),
            EVENT_EDIT_TEXT to mapOf("registrationName" to "onEditText"),
        )

    override fun getExportedViewConstants(): MutableMap<String, Any> =
        mutableMapOf("einkGrays" to StyleColor.entries.associate { it.id to "#%06X".format(StylePalette.einkGray(it)) })

    private fun runCommand(
        root: CanvasView,
        command: String,
        args: ReadableArray?,
    ) {
        val strings = args?.let { array -> List(array.size()) { array.getString(it).orEmpty() } }.orEmpty()
        when (command) {
            "setStyle" -> if (strings.size >= 2) root.controller.setStyle(strings[0], strings[1])
            "setText" -> if (strings.isNotEmpty()) root.controller.finishEdit(strings[0])
            else -> SIMPLE_COMMANDS[command]?.invoke(root)
        }
    }

    private fun dispatch(
        context: ThemedReactContext,
        view: CanvasView,
        eventName: String,
        payload: Map<String, Any>,
    ) {
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(context, view.id) ?: return
        dispatcher.dispatchEvent(CanvasEvent(UIManagerHelper.getSurfaceId(view), view.id, eventName, Arguments.makeNativeMap(payload)))
    }

    private class CanvasEvent(
        surfaceId: Int,
        viewTag: Int,
        private val name: String,
        private val payload: WritableMap,
    ) : Event<CanvasEvent>(surfaceId, viewTag) {
        override fun getEventName(): String = name

        override fun getEventData(): WritableMap = payload
    }

    companion object {
        const val NAME = "CanvasView"
        private const val EVENT_CANVAS_STATE = "topCanvasState"
        private const val EVENT_EDIT_TEXT = "topEditText"

        // The commands that take no arguments, as the view runs them.
        private val SIMPLE_COMMANDS: Map<String, (CanvasView) -> Unit> =
            mapOf(
                "deleteSelected" to { view -> view.controller.deleteSelected() },
                "undo" to { view -> view.controller.undo() },
                "redo" to { view -> view.controller.redo() },
                "duplicateSelected" to { view -> view.duplicateSelected() },
                "bringToFront" to { view -> view.controller.bringSelectedToFront() },
                "sendToBack" to { view -> view.controller.sendSelectedToBack() },
                "zoomToFit" to { view -> view.zoomToFit() },
                "zoomTo100" to { view -> view.zoomTo100() },
                "tableAddRow" to { view -> view.controller.addTableRow() },
                "tableAddColumn" to { view -> view.controller.addTableColumn() },
                "tableRemoveRow" to { view -> view.controller.removeTableRow() },
                "tableRemoveColumn" to { view -> view.controller.removeTableColumn() },
            )

        // Command names and the numeric ids the Int overload receives (delete/undo/redo keep 1-3).
        private val COMMAND_IDS =
            (SIMPLE_COMMANDS.keys + listOf("setStyle", "setText")).withIndex().associate { (index, name) -> name to index + 1 }
    }
}
