// The native `<CanvasView>` (CanvasViewManager.kt): its props, the
// commands UIManager.dispatchViewManagerCommand delivers by name, and the
// view constants it exports.

import type React from 'react';
import {
  findNodeHandle,
  requireNativeComponent,
  UIManager,
  type NativeSyntheticEvent,
  type ViewProps,
} from 'react-native';

/** The toolbar's tools; the same ids as CanvasTools in CanvasModel.kt. */
export type ToolMode =
  | 'select'
  | 'rectangle'
  | 'ellipse'
  | 'line'
  | 'arrow'
  | 'draw'
  | 'eraser'
  | 'text'
  | 'note'
  | 'table';

export type CanvasCommand =
  | 'deleteSelected'
  | 'undo'
  | 'redo'
  | 'duplicateSelected'
  | 'bringToFront'
  | 'sendToBack'
  | 'zoomToFit'
  | 'zoomTo100'
  | 'setStyle'
  | 'setText'
  | 'tableAddRow'
  | 'tableAddColumn'
  | 'tableRemoveRow'
  | 'tableRemoveColumn';

type NativeProps = ViewProps & {
  toolMode: ToolMode;
  /** The canvas-state event (FR18/FR19); validate its body with parseUiState. */
  onCanvasState?: (event: NativeSyntheticEvent<unknown>) => void;
  /** Text editing began (FR6/FR24); validate its body with parseTextEditRequest. */
  onEditText?: (event: NativeSyntheticEvent<unknown>) => void;
};

const NATIVE_NAME = 'CanvasView';

export const CanvasNativeView = requireNativeComponent<NativeProps>(NATIVE_NAME);

export type CanvasViewRef = React.ComponentRef<typeof CanvasNativeView>;

/** Sends [command] (with [args], e.g. setStyle's property and value) to the mounted canvas view; a no-op before it mounts. */
export function dispatchCanvasCommand(
  view: CanvasViewRef | null,
  command: CanvasCommand,
  args: readonly string[] = [],
): void {
  const node = findNodeHandle(view);
  if (node == null) {
    return;
  }
  UIManager.dispatchViewManagerCommand(node, command, [...args]);
}

type ViewManagerConfig = {Constants?: {einkGrays?: Record<string, string>}} | null | undefined;

/** The e-ink gray of each colour id, as the native view exports it; null when the host doesn't expose view constants. */
export function nativeEinkGrays(): Record<string, string> | null {
  const config = UIManager.getViewManagerConfig?.(NATIVE_NAME) as ViewManagerConfig;
  return config?.Constants?.einkGrays ?? null;
}
