import React, {useEffect, useRef, useState} from 'react';
import {
  findNodeHandle,
  Image,
  NativeModules,
  Pressable,
  requireNativeComponent,
  StyleSheet,
  Text,
  UIManager,
  View,
  ViewProps,
} from 'react-native';
import {PluginManager, PluginCommAPI, PluginNoteAPI, PluginFileAPI} from 'sn-plugin-lib';
import {getLastButtonEvent} from './pluginRouter';
import {buildUserData, parseUserData} from './canvasUserData';

const {SuperCanvasModule} = NativeModules;

// Default canvas identity when opened normally (the sidebar button, id 500).
// v1d (FR13): opened via the lasso "reopen" button (id 501, see index.js)
// instead resolves a different id from the lassoed thumbnail's userData —
// see the mount effect below. This path shape (`SuperCanvas/<id>.json`) is
// what v1c's own comment anticipated v1d would need.
const DEFAULT_CANVAS_ID = 'default';
// Must match index.js's LASSO_REOPEN_BUTTON_ID.
const LASSO_REOPEN_BUTTON_ID = 501;

type LooseApiResult = {result?: unknown} | null | undefined;

function resultOf(response: unknown): unknown {
  return (response as LooseApiResult)?.result;
}

/**
 * v1a root screen (spec/SuperCanvas-PRD.md §3 journey step 3 / §9
 * architecture). Mounts the native `SuperCanvasView` (registered by
 * SuperCanvasViewManager.kt) full-screen, with a header bar and a bottom
 * tool palette (grayscale/e-ink styling per sn-shapes' own design goal —
 * no colored swatches; Manta's color panel is a later consideration, not
 * this slice).
 *
 * v1b adds Line/Arrow tool buttons (connector binding FR7 and resize
 * handles FR9 are native-side-only behavior — dragging a shape's corner or
 * a line/arrow's endpoint — nothing new is exposed to JS for them).
 *
 * NOT yet implemented (post-v1b):
 *  - Save/export triggers (FR11-FR13) wired to SuperCanvasModule
 *  - Live selection-state-driven UI (e.g. disabling Delete when nothing is
 *    selected) — deliberately deferred; Delete/Undo/Redo are always
 *    tappable and the native side treats "nothing applicable" as a no-op.
 */

type SuperCanvasNativeProps = ViewProps & {
  toolMode?: string;
};

const SuperCanvasNativeView =
  requireNativeComponent<SuperCanvasNativeProps>('SuperCanvasView');

type ToolMode = 'select' | 'rectangle' | 'ellipse' | 'line' | 'arrow';
type CommandName = 'deleteSelected' | 'undo' | 'redo';

// Drawn PNG icons (assets/icons/), not Unicode glyphs: symbols like ↖ ╱ ↺
// aren't guaranteed to exist in the device firmware's font and rendered badly
// on-device. Black-on-transparent so `tintColor` can flip them white when
// active. `label` stays for accessibility and is what test queries key off.
const TOOLS: ReadonlyArray<{mode: ToolMode; label: string; icon: number}> = [
  {mode: 'select', label: 'Select', icon: require('../assets/icons/tool-select.png')},
  {mode: 'rectangle', label: 'Rectangle', icon: require('../assets/icons/tool-rectangle.png')},
  {mode: 'ellipse', label: 'Ellipse', icon: require('../assets/icons/tool-ellipse.png')},
  {mode: 'line', label: 'Line', icon: require('../assets/icons/tool-line.png')},
  {mode: 'arrow', label: 'Arrow', icon: require('../assets/icons/action-arrow.png')},
];

const COMMAND_ICONS: Record<CommandName, number> = {
  deleteSelected: require('../assets/icons/action-delete.png'),
  undo: require('../assets/icons/action-undo.png'),
  redo: require('../assets/icons/action-redo.png'),
};

const SAVE_ICON = require('../assets/icons/action-save.png');
const CLOSE_ICON = require('../assets/icons/action-close.png');

export default function SuperCanvasScreen(): React.JSX.Element {
  const [toolMode, setToolMode] = useState<ToolMode>('select');
  const canvasRef = useRef<React.ComponentRef<typeof SuperCanvasNativeView>>(null);
  // Resolved once on mount (PluginManager.getPluginDirPath() is async); the
  // close handler reads this ref rather than re-resolving so a same-session
  // load and save always target the same file even if the user closes fast.
  const canvasFilePathRef = useRef<string | null>(null);
  // Resolved alongside canvasFilePathRef — "Save to Note" reuses it so a
  // thumbnail always traces back to the canvas it was actually saved from.
  const canvasIdRef = useRef<string>(DEFAULT_CANVAS_ID);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      // v1d (FR13): opened via the lasso reopen button? Resolve which saved
      // canvas to load from the lassoed thumbnail's userData instead of the
      // default. Best-effort in every regard — any failure here (no lasso
      // context, malformed userData, an API rejection) must fall back to the
      // default canvas silently, never block the plugin from opening.
      let resolvedCanvasId = DEFAULT_CANVAS_ID;
      try {
        if (getLastButtonEvent()?.id === LASSO_REOPEN_BUTTON_ID) {
          const lassoResponse = await PluginCommAPI.getLassoElements();
          const elements = resultOf(lassoResponse);
          if (Array.isArray(elements)) {
            for (const element of elements) {
              const canvasId = parseUserData((element as {userData?: string})?.userData);
              if (canvasId) {
                resolvedCanvasId = canvasId;
                break;
              }
            }
          }
        }
      } catch {
        // Fall back to the default canvas — see comment above.
      }
      canvasIdRef.current = resolvedCanvasId;

      try {
        const pluginDir = await PluginManager.getPluginDirPath();
        if (!pluginDir || cancelled) {
          return;
        }
        const path = `${pluginDir}/SuperCanvas/${resolvedCanvasId}.json`;
        canvasFilePathRef.current = path;
        await SuperCanvasModule.loadCanvas(path);
      } catch {
        // Best-effort load — an empty/fresh canvas is an acceptable fallback
        // (e.g. first run, or the file doesn't exist yet).
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSaveToNote = () => {
    (async () => {
      try {
        const pluginDir = await PluginManager.getPluginDirPath();
        if (!pluginDir) {
          return;
        }
        const canvasId = canvasIdRef.current;
        const thumbnailPath = `${pluginDir}/SuperCanvas/thumbnails/${canvasId}.png`;
        await SuperCanvasModule.generateThumbnail(thumbnailPath);
        await PluginNoteAPI.insertImage(thumbnailPath);

        const element = resultOf(await PluginFileAPI.getLastElement());
        if (!element || typeof element !== 'object') {
          return;
        }
        const notePath = resultOf(await PluginCommAPI.getCurrentFilePath());
        const page = resultOf(await PluginCommAPI.getCurrentPageNum());
        if (typeof notePath !== 'string' || typeof page !== 'number') {
          return;
        }
        const modifiedElement = {...element, userData: buildUserData(canvasId)};
        await PluginFileAPI.modifyElements(notePath, page, [modifiedElement]);
      } catch {
        // Best-effort — a failed "Save to Note" must not crash the plugin or
        // block any other action.
      }
    })();
  };

  const handleClose = () => {
    (async () => {
      const path = canvasFilePathRef.current;
      if (path) {
        try {
          await SuperCanvasModule.saveCanvas(path);
        } catch {
          // Best-effort save — don't block closing the plugin on a save failure.
        }
      }
      PluginManager.closePluginView();
    })();
  };

  const dispatchCommand = (commandName: CommandName) => {
    const node = findNodeHandle(canvasRef.current);
    if (node == null) {
      return;
    }
    UIManager.dispatchViewManagerCommand(node, commandName, []);
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>SuperCanvas — v0 spike</Text>
        <View style={styles.headerActions}>
          <Pressable
            testID="supercanvas-save-to-note"
            accessibilityLabel="Save to Note"
            style={styles.closeButton}
            onPress={handleSaveToNote}>
            <Image source={SAVE_ICON} style={styles.headerIcon} />
          </Pressable>
          <Pressable
            testID="supercanvas-close"
            accessibilityLabel="Close"
            style={styles.closeButton}
            onPress={handleClose}>
            <Image source={CLOSE_ICON} style={styles.headerIcon} />
          </Pressable>
        </View>
      </View>
      <View style={styles.canvasArea}>
        <SuperCanvasNativeView
          ref={canvasRef}
          style={StyleSheet.absoluteFill}
          toolMode={toolMode}
        />
        {/* box-none: the wrapper spans the full width so the pill can center
            itself, but taps outside the pill must still reach the canvas
            underneath (pan/draw), not be swallowed by an invisible overlay. */}
        <View style={styles.toolbarWrapper} pointerEvents="box-none">
          <View style={styles.toolbar}>
            {TOOLS.map(tool => {
              const active = toolMode === tool.mode;
              return (
                <Pressable
                  key={tool.mode}
                  testID={`supercanvas-tool-${tool.mode}`}
                  accessibilityLabel={tool.label}
                  style={[styles.toolButton, active && styles.toolButtonActive]}
                  onPress={() => setToolMode(tool.mode)}>
                  <Image
                    source={tool.icon}
                    style={[styles.toolButtonIcon, active && styles.toolButtonIconActive]}
                  />
                </Pressable>
              );
            })}
            <View style={styles.toolbarDivider} />
            <Pressable
              testID="supercanvas-delete"
              accessibilityLabel="Delete"
              style={styles.toolButton}
              onPress={() => dispatchCommand('deleteSelected')}>
              <Image source={COMMAND_ICONS.deleteSelected} style={styles.toolButtonIcon} />
            </Pressable>
            <Pressable
              testID="supercanvas-undo"
              accessibilityLabel="Undo"
              style={styles.toolButton}
              onPress={() => dispatchCommand('undo')}>
              <Image source={COMMAND_ICONS.undo} style={styles.toolButtonIcon} />
            </Pressable>
            <Pressable
              testID="supercanvas-redo"
              accessibilityLabel="Redo"
              style={styles.toolButton}
              onPress={() => dispatchCommand('redo')}>
              <Image source={COMMAND_ICONS.redo} style={styles.toolButtonIcon} />
            </Pressable>
          </View>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#cccccc',
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    color: '#000000',
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  closeButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  headerIcon: {
    width: 22,
    height: 22,
    tintColor: '#000000',
  },
  canvasArea: {
    flex: 1,
    position: 'relative',
  },
  // Spans the full width (so `alignItems: 'center'` below can center the
  // pill), pinned near the bottom, but see `pointerEvents="box-none"` on the
  // JSX side — only the pill itself, not this wrapper, blocks canvas touches.
  toolbarWrapper: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 24,
    alignItems: 'center',
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 28,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
    // A floating pill reads best with a little separation from the content
    // behind it; e-ink has no true soft shadow, so this is a cheap, mostly
    // decorative hint for the (non-e-ink) preview and any color panel.
    shadowColor: '#000000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.15,
    shadowRadius: 6,
    elevation: 4,
  },
  toolButton: {
    width: 40,
    height: 40,
    marginHorizontal: 2,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  toolButtonActive: {
    backgroundColor: '#000000',
  },
  toolButtonIcon: {
    width: 22,
    height: 22,
    tintColor: '#000000',
  },
  toolButtonIconActive: {
    tintColor: '#ffffff',
  },
  toolbarDivider: {
    width: 1,
    height: 24,
    backgroundColor: '#cccccc',
    marginHorizontal: 6,
  },
});
