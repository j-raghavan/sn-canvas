// The floating, centered tool pill: shapes, connectors, the pencil and eraser,
// text, sticky notes and tables (FR16), then the image button, which picks an
// image to insert rather than being a tool (FR22). Icons are drawn PNGs
// (assets/icons/) rather than Unicode glyphs, which rendered badly in the
// device font. They are black on transparent, so tintColor can invert the
// active tool.

import React from 'react';
import {Image, Pressable, StyleSheet, View, type ImageSourcePropType} from 'react-native';
import type {ToolMode} from './nativeCanvasView';

const TOOLS: ReadonlyArray<{id: ToolMode; label: string; icon: ImageSourcePropType}> = [
  {id: 'select', label: 'Select', icon: require('../../assets/icons/tool-select.png')},
  {id: 'rectangle', label: 'Rectangle', icon: require('../../assets/icons/tool-rectangle.png')},
  {id: 'ellipse', label: 'Ellipse', icon: require('../../assets/icons/tool-ellipse.png')},
  {id: 'line', label: 'Line', icon: require('../../assets/icons/tool-line.png')},
  {id: 'arrow', label: 'Arrow', icon: require('../../assets/icons/action-arrow.png')},
  {id: 'draw', label: 'Pencil', icon: require('../../assets/icons/tool-draw.png')},
  {id: 'eraser', label: 'Eraser', icon: require('../../assets/icons/tool-eraser.png')},
  {id: 'text', label: 'Text', icon: require('../../assets/icons/tool-text.png')},
  {id: 'note', label: 'Sticky note', icon: require('../../assets/icons/tool-note.png')},
  {id: 'table', label: 'Table', icon: require('../../assets/icons/tool-table.png')},
];

const IMAGE_ICON = require('../../assets/icons/tool-image.png');

type Props = {
  toolMode: ToolMode;
  onToolChange: (tool: ToolMode) => void;
  onInsertImage: () => void;
};

export default function Toolbar({toolMode, onToolChange, onInsertImage}: Props): React.JSX.Element {
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
        <Pressable testID="supercanvas-insert-image" accessibilityLabel="Image" style={styles.button} onPress={onInsertImage}>
          <Image source={IMAGE_ICON} style={styles.icon} />
        </Pressable>
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
});
