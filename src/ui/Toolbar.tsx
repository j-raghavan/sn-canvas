// The floating, centered tool pill: the drawing tools, then Delete/Undo/Redo.
// Icons are drawn PNGs (assets/icons/) rather than Unicode glyphs, which
// rendered badly in the device font. They are black on transparent, so
// tintColor can invert the active tool.

import React from 'react';
import {Image, Pressable, StyleSheet, View, type ImageSourcePropType} from 'react-native';
import type {CanvasCommand, ToolMode} from './nativeCanvasView';

type Item<T> = {id: T; label: string; icon: ImageSourcePropType};

const TOOLS: ReadonlyArray<Item<ToolMode>> = [
  {id: 'select', label: 'Select', icon: require('../../assets/icons/tool-select.png')},
  {id: 'rectangle', label: 'Rectangle', icon: require('../../assets/icons/tool-rectangle.png')},
  {id: 'ellipse', label: 'Ellipse', icon: require('../../assets/icons/tool-ellipse.png')},
  {id: 'line', label: 'Line', icon: require('../../assets/icons/tool-line.png')},
  {id: 'arrow', label: 'Arrow', icon: require('../../assets/icons/action-arrow.png')},
];

const COMMANDS: ReadonlyArray<Item<CanvasCommand> & {testID: string}> = [
  {id: 'deleteSelected', label: 'Delete', testID: 'supercanvas-delete', icon: require('../../assets/icons/action-delete.png')},
  {id: 'undo', label: 'Undo', testID: 'supercanvas-undo', icon: require('../../assets/icons/action-undo.png')},
  {id: 'redo', label: 'Redo', testID: 'supercanvas-redo', icon: require('../../assets/icons/action-redo.png')},
];

type Props = {
  toolMode: ToolMode;
  onToolChange: (tool: ToolMode) => void;
  onCommand: (command: CanvasCommand) => void;
};

export default function Toolbar({toolMode, onToolChange, onCommand}: Props): React.JSX.Element {
  // box-none: the wrapper spans the width so the pill can center itself, but
  // taps beside the pill must still reach the canvas underneath.
  return (
    <View style={styles.wrapper} pointerEvents="box-none">
      <View style={styles.pill}>
        {TOOLS.map(tool => {
          const active = tool.id === toolMode;
          return (
            <Pressable
              key={tool.id}
              testID={`supercanvas-tool-${tool.id}`}
              accessibilityLabel={tool.label}
              style={[styles.button, active && styles.buttonActive]}
              onPress={() => onToolChange(tool.id)}>
              <Image source={tool.icon} style={[styles.icon, active && styles.iconActive]} />
            </Pressable>
          );
        })}
        <View style={styles.divider} />
        {COMMANDS.map(command => (
          <Pressable
            key={command.id}
            testID={command.testID}
            accessibilityLabel={command.label}
            style={styles.button}
            onPress={() => onCommand(command.id)}>
            <Image source={command.icon} style={styles.icon} />
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 24,
    alignItems: 'center',
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 28,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
    // E-ink has no soft shadow; this is a cheap separation hint where one renders.
    shadowColor: '#000000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.15,
    shadowRadius: 6,
    elevation: 4,
  },
  button: {
    width: 40,
    height: 40,
    marginHorizontal: 2,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonActive: {
    backgroundColor: '#000000',
  },
  icon: {
    width: 22,
    height: 22,
    tintColor: '#000000',
  },
  iconActive: {
    tintColor: '#ffffff',
  },
  divider: {
    width: 1,
    height: 24,
    backgroundColor: '#cccccc',
    marginHorizontal: 6,
  },
});
