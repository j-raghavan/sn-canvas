package com.snsupercanvas.canvas

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * Exposes [SuperCanvasView] to React Native as `<SuperCanvasView>` (mounted
 * in JS via `requireNativeComponent('SuperCanvasView')` — see
 * src/ui/nativeCanvasView.ts). Registered manually via [SuperCanvasPackage],
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
 * safe choice rather than assuming one path. Verified on-device: Delete, Undo
 * and Redo taps reach the view through this path.
 *
 * Each view it creates receives the injected [registry] so the module can find
 * the live one (see [SuperCanvasPackage]).
 */
class SuperCanvasViewManager(
    private val registry: ActiveViewRegistry<SuperCanvasView>,
) : SimpleViewManager<SuperCanvasView>() {
    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): SuperCanvasView = SuperCanvasView(reactContext, registry)

    @ReactProp(name = "toolMode")
    fun setToolMode(
        view: SuperCanvasView,
        toolMode: String?,
    ) {
        view.setToolMode(toolMode ?: CanvasTools.SELECT)
    }

    override fun getCommandsMap(): MutableMap<String, Int> =
        mutableMapOf(
            COMMAND_DELETE_SELECTED_NAME to COMMAND_DELETE_SELECTED,
            COMMAND_UNDO_NAME to COMMAND_UNDO,
            COMMAND_REDO_NAME to COMMAND_REDO,
        )

    // Deprecated upstream in favor of the String overload below, but still the one a Paper tag calls (see class doc).
    @Suppress("OVERRIDE_DEPRECATION")
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
