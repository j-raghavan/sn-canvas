// The action bar (FR18), above the toolbar as in tldraw: undo, redo, delete,
// duplicate and a ⋮ menu. Each action is enabled only when the canvas says it
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
    testID: 'supercanvas-undo',
    label: 'Undo',
    icon: require('../../assets/icons/action-undo.png'),
    enabled: ui => ui.canUndo,
  },
  {
    command: 'redo',
    testID: 'supercanvas-redo',
    label: 'Redo',
    icon: require('../../assets/icons/action-redo.png'),
    enabled: ui => ui.canRedo,
  },
  {
    command: 'deleteSelected',
    testID: 'supercanvas-delete',
    label: 'Delete',
    icon: require('../../assets/icons/action-delete.png'),
    enabled: ui => ui.hasSelection,
  },
  {
    command: 'duplicateSelected',
    testID: 'supercanvas-duplicate',
    label: 'Duplicate',
    icon: require('../../assets/icons/action-duplicate.png'),
    enabled: ui => ui.hasSelection,
  },
];

const MENU: readonly {command: CanvasCommand; label: string; needsSelection: boolean}[] = [
  {command: 'bringToFront', label: 'Bring to front', needsSelection: true},
  {command: 'sendToBack', label: 'Send to back', needsSelection: true},
  {command: 'zoomToFit', label: 'Zoom to fit', needsSelection: false},
  {command: 'zoomTo100', label: 'Zoom to 100%', needsSelection: false},
];

const MORE_ICON = require('../../assets/icons/action-more.png');

type Props = {
  ui: CanvasUiState;
  onCommand: (command: CanvasCommand) => void;
};

export default function ActionBar({ui, onCommand}: Props): React.JSX.Element {
  const [isMenuOpen, setMenuOpen] = useState(false);
  return (
    // box-none: taps beside the bar and menu still reach the canvas underneath.
    <View style={styles.wrapper} pointerEvents="box-none">
      {isMenuOpen && (
        <View style={styles.menu}>
          {MENU.map(item => {
            const enabled = !item.needsSelection || ui.hasSelection;
            return (
              <Pressable
                key={item.command}
                testID={`supercanvas-menu-${item.command}`}
                accessibilityLabel={item.label}
                disabled={!enabled}
                style={styles.menuItem}
                onPress={() => {
                  setMenuOpen(false);
                  onCommand(item.command);
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
          testID="supercanvas-more"
          accessibilityLabel="More actions"
          style={styles.button}
          onPress={() => setMenuOpen(open => !open)}>
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
