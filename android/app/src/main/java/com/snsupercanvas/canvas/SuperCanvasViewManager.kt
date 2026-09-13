package com.snsupercanvas.canvas

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * Exposes [SuperCanvasView] to React Native as `<SuperCanvasView>` (mounted
 * in JS via `requireNativeComponent('SuperCanvasView')` — see
 * src/SuperCanvasScreen.tsx). Registered manually via [SuperCanvasPackage],
 * same pattern as sn-tables' TableGridPackage, but with createViewManagers
 * populated (sn-tables' left it empty since it had no custom view).
 *
 * Deliberately thin: this class only marshals RN props/commands to the view.
 * All rendering/gesture/state logic lives in [SuperCanvasView] and
 * [SuperCanvasCore] per PRD NFR5's Single Responsibility split.
 *
 * Command dispatch overrides both [receiveCommand] signatures
 * (`ViewManager` declares an `Int`-keyed and a `String`-keyed overload — see
 * `com.facebook.react.uimanager.ViewManager` in the resolved RN 0.79.2 AAR)
 * because which one the runtime calls depends on whether the view's react
 * tag is a Paper or Fabric tag at the interop boundary; handling both is the
 * safe choice rather than assuming one path. This is compiled and reviewed
 * against the real RN 0.79.2 API surface, but not yet behaviorally verified
 * on-device with an actual button tap — do that before relying on it.
 */
class SuperCanvasViewManager : SimpleViewManager<SuperCanvasView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): SuperCanvasView = SuperCanvasView(reactContext)

    @ReactProp(name = "toolMode")
    fun setToolMode(
        view: SuperCanvasView,
        toolMode: String?,
    ) {
        view.setToolMode(toolMode ?: SuperCanvasView.TOOL_SELECT)
    }

    override fun getCommandsMap(): MutableMap<String, Int> =
        MapBuilder.of(
            COMMAND_DELETE_SELECTED_NAME,
            COMMAND_DELETE_SELECTED,
            COMMAND_UNDO_NAME,
            COMMAND_UNDO,
            COMMAND_REDO_NAME,
            COMMAND_REDO,
        )

    override fun receiveCommand(
        root: SuperCanvasView,
        commandId: Int,
        args: ReadableArray?,
    ) {
        when (commandId) {
            COMMAND_DELETE_SELECTED -> root.deleteSelected()
            COMMAND_UNDO -> root.undo()
            COMMAND_REDO -> root.redo()
        }
    }

    override fun receiveCommand(
        root: SuperCanvasView,
        commandId: String,
        args: ReadableArray?,
    ) {
        when (commandId) {
            COMMAND_DELETE_SELECTED_NAME -> root.deleteSelected()
            COMMAND_UNDO_NAME -> root.undo()
            COMMAND_REDO_NAME -> root.redo()
        }
    }

    companion object {
        const val NAME = "SuperCanvasView"
        private const val COMMAND_DELETE_SELECTED_NAME = "deleteSelected"
        private const val COMMAND_UNDO_NAME = "undo"
        private const val COMMAND_REDO_NAME = "redo"
        private const val COMMAND_DELETE_SELECTED = 1
        private const val COMMAND_UNDO = 2
        private const val COMMAND_REDO = 3
    }
}
