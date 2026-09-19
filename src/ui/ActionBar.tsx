// The action bar (FR18), above the toolbar as in tldraw: undo, redo, delete,
// duplicate and a ⋮ menu, which also starts a new canvas. Each action is enabled only when the canvas says it
// applies (the canvas-state event), so nothing looks tappable that isn't.

import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType} from 'react-native';
import type {CanvasUiState} from '../domain/styles';
import type {CanvasCommand} from './nativeCanvasView';

type Action = {
  command: CanvasCommand;
  testID: string;
  label: string;
  icon: ImageSourcePropType;
  enabled: (ui: CanvasUiState) => boolean;
};

const ACTIONS: readonly Action[] = [
  {
    command: 'undo',
    testID: 'canvas-undo',
    label: 'Undo',
    icon: require('../../assets/icons/action-undo.png'),
    enabled: ui => ui.canUndo,
  },
  {
    command: 'redo',
    testID: 'canvas-redo',
    label: 'Redo',
    icon: require('../../assets/icons/action-redo.png'),
    enabled: ui => ui.canRedo,
  },
  {
    command: 'deleteSelected',
    testID: 'canvas-delete',
    label: 'Delete',
    icon: require('../../assets/icons/action-delete.png'),
    enabled: ui => ui.hasSelection,
  },
  {
    command: 'duplicateSelected',
    testID: 'canvas-duplicate',
    label: 'Duplicate',
    icon: require('../../assets/icons/action-duplicate.png'),
    enabled: ui => ui.hasSelection,
  },
  // Grouping acts on the selection, like delete and duplicate, so it belongs beside them rather than in the ⋮ menu.
  {
    command: 'group',
    testID: 'canvas-group',
    label: 'Group',
    icon: require('../../assets/icons/action-group.png'),
    enabled: ui => ui.selectionCount > 1,
  },
  {
    command: 'ungroup',
    testID: 'canvas-ungroup',
    label: 'Ungroup',
    icon: require('../../assets/icons/action-ungroup.png'),
    enabled: ui => ui.canUngroup,
  },
];

type MenuItem = {
  /** A canvas command, or one the screen's session handles ('newCanvas', 'linkToNote'). */
  action: CanvasCommand | 'newCanvas' | 'linkToNote';
  label: string;
  /** Greyed out with nothing selected. */
  needsSelection?: boolean;
  /** Greyed out on an empty canvas. */
  needsContent?: boolean;
  /** Greyed out unless the selected element links somewhere. */
  needsLink?: boolean;
  /** Listed only while a table is selected (FR24). */
  tableOnly?: boolean;
};

const MENU: readonly MenuItem[] = [
  {action: 'bringToFront', label: 'Bring to front', needsSelection: true},
  {action: 'sendToBack', label: 'Send to back', needsSelection: true},
  {action: 'tableAddRow', label: 'Add row', tableOnly: true},
  {action: 'tableAddColumn', label: 'Add column', tableOnly: true},
  {action: 'tableRemoveRow', label: 'Remove last row', tableOnly: true},
  {action: 'tableRemoveColumn', label: 'Remove last column', tableOnly: true},
  {action: 'linkToNote', label: 'Link to note…', needsSelection: true},
  {action: 'unlinkSelected', label: 'Remove link', needsLink: true},
  {action: 'zoomToFit', label: 'Zoom to fit'},
  {action: 'zoomTo100', label: 'Zoom to 100%'},
  {action: 'clearCanvas', label: 'Clear canvas', needsContent: true},
  {action: 'newCanvas', label: 'New canvas'},
];

const MORE_ICON = require('../../assets/icons/action-more.png');

type Props = {
  ui: CanvasUiState;
  onCommand: (command: CanvasCommand) => void;
  /** Saves the canvas shown and starts a new, empty one. */
  onNewCanvas: () => void;
  /** Asks to clear the canvas; the screen confirms it first. */
  onClearCanvas: () => void;
  /** Asks for a note to link the selected element to; the screen runs the picker. */
  onLinkToNote: () => void;
  /** The ⋮ menu is opening: the screen puts the onboarding hints away, so neither is drawn over the other. */
  onMenuOpen: () => void;
};

export default function ActionBar({ui, onCommand, onNewCanvas, onClearCanvas, onLinkToNote, onMenuOpen}: Props): React.JSX.Element {
  const [isMenuOpen, setMenuOpen] = useState(false);

  return (
    // box-none: taps beside the bar and menu still reach the canvas underneath.
    <View style={styles.wrapper} pointerEvents="box-none">
      {isMenuOpen && (
        <View style={styles.menu}>
          {MENU.filter(item => !item.tableOnly || ui.selectedType === 'table').map(item => {
            const enabled =
              (!item.needsSelection || ui.hasSelection) &&
              (!item.needsContent || ui.hasContent) &&
              (!item.needsLink || ui.hasLink);
            return (
              <Pressable
                key={item.action}
                testID={`canvas-menu-${item.action}`}
                accessibilityLabel={item.label}
                disabled={!enabled}
                style={styles.menuItem}
                onPress={() => {
                  setMenuOpen(false);
                  if (item.action === 'newCanvas') {
                    onNewCanvas();
                  } else if (item.action === 'linkToNote') {
                    onLinkToNote();
                  } else if (item.action === 'clearCanvas') {
                    onClearCanvas();
                  } else {
                    onCommand(item.action);
                  }
                }}>
                <Text style={[styles.menuText, !enabled && styles.disabled]}>{item.label}</Text>
              </Pressable>
            );
          })}
        </View>
      )}
      <View style={styles.bar}>
        {ACTIONS.map(action => {
          const enabled = action.enabled(ui);
          return (
            <Pressable
              key={action.command}
              testID={action.testID}
              accessibilityLabel={action.label}
              disabled={!enabled}
              style={styles.button}
              onPress={() => onCommand(action.command)}>
              <Image source={action.icon} style={[styles.icon, !enabled && styles.disabled]} />
            </Pressable>
          );
        })}
        <Pressable
          testID="canvas-more"
          accessibilityLabel="More actions"
          style={styles.button}
          onPress={() => {
            if (!isMenuOpen) {
              onMenuOpen();
            }
            setMenuOpen(open => !open);
          }}>
          <Image source={MORE_ICON} style={styles.icon} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  // Sits above the toolbar pill (see Toolbar's wrapper).
  wrapper: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 92,
    alignItems: 'center',
  },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
  },
  button: {
    width: 40,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  icon: {
    width: 20,
    height: 20,
    tintColor: '#000000',
  },
  disabled: {
    opacity: 0.3,
  },
  menu: {
    marginBottom: 6,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
  },
  menuItem: {
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  menuText: {
    fontSize: 15,
    color: '#000000',
  },
});
