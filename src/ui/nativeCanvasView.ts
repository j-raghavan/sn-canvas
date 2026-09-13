// The native `<SuperCanvasView>` (SuperCanvasViewManager.kt) and its
// commands, which UIManager.dispatchViewManagerCommand delivers by name.

import type React from 'react';
import {findNodeHandle, requireNativeComponent, UIManager, type ViewProps} from 'react-native';

/** The toolbar's tools; the same ids as CanvasTools in CanvasModel.kt. */
export type ToolMode = 'select' | 'rectangle' | 'ellipse' | 'line' | 'arrow';

export type CanvasCommand = 'deleteSelected' | 'undo' | 'redo';

type NativeProps = ViewProps & {toolMode: ToolMode};

export const SuperCanvasNativeView = requireNativeComponent<NativeProps>('SuperCanvasView');

export type CanvasViewRef = React.ComponentRef<typeof SuperCanvasNativeView>;

/** Sends [command] to the mounted canvas view; a no-op before it mounts. */
export function dispatchCanvasCommand(view: CanvasViewRef | null, command: CanvasCommand): void {
  const node = findNodeHandle(view);
  if (node == null) {
    return;
  }
  UIManager.dispatchViewManagerCommand(node, command, []);
}
