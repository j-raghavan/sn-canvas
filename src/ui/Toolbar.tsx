// The floating, centered tool pill: shapes, connectors, the pencil and eraser,
// text, sticky notes and tables (FR16), then the image button, which picks an
// image to insert rather than being a tool (FR22), and the help button, which
// shows or hides the onboarding hints. Icons are drawn PNGs (assets/icons/)
// rather than Unicode glyphs, which rendered badly in the device font. They are
// black on transparent, so tintColor can invert the active tool.

import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType} from 'react-native';
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
const HELP_ICON = require('../../assets/icons/tool-help.png');

// The pill's measures, which HelpHints also uses to land its arrows on these buttons.
const BOTTOM = 24;
const OPTIONS_WIDTH = 150;
const BUTTON = 40;
const BUTTON_MARGIN = 2;
const SLOT = BUTTON + 2 * BUTTON_MARGIN;
const PADDING_H = 10;
const PADDING_V = 8;
const BORDER = 1;
const SEPARATOR_MARGIN = 6;
const PILL_HEIGHT = BUTTON + 2 * (PADDING_V + BORDER);
// Every tool, then the image and help buttons, the separator between those two, and the pill's padding and border.
const HALF_WIDTH = ((TOOLS.length + 2) * SLOT + 1 + 2 * SEPARATOR_MARGIN + 2 * (PADDING_H + BORDER)) / 2;

/** Where the pill's buttons sit, in dp: its top above the screen's bottom, and button centres from the middle. */
export const TOOLBAR_GEOMETRY = {
  top: BOTTOM + BUTTON + 2 * (PADDING_V + BORDER),
  toolCenterX: (tool: ToolMode) => -HALF_WIDTH + BORDER + PADDING_H + SLOT * (TOOLS.findIndex(t => t.id === tool) + 0.5),
  helpCenterX: HALF_WIDTH - BORDER - PADDING_H - SLOT / 2,
};

type Props = {
  toolMode: ToolMode;
  onToolChange: (tool: ToolMode) => void;
  onInsertImage: () => void;
  /** Whether the onboarding hints overlay is showing (CanvasScreen), so this button reflects it. */
  showHints: boolean;
  onToggleHints: () => void;
  /** Asks to clear the canvas; the screen confirms it first. */
  onClearCanvas: () => void;
  /** Whether the canvas holds anything, so Clear canvas is offered. */
  canClearCanvas: boolean;
};

export default function Toolbar({
  toolMode,
  onToolChange,
  onInsertImage,
  showHints,
  onToggleHints,
  onClearCanvas,
  canClearCanvas,
}: Props): React.JSX.Element {
  // Erasing a stroke and erasing the lot belong together, so the eraser, once it
  // is the tool, opens its own options on a second tap (its caret says so).
  const [areEraserOptionsOpen, setEraserOptionsOpen] = useState(false);

  const pickTool = (tool: ToolMode) => {
    setEraserOptionsOpen(tool === 'eraser' && toolMode === 'eraser' ? !areEraserOptionsOpen : false);
    if (tool !== toolMode) {
      onToolChange(tool);
    }
  };

  // box-none: the wrapper spans the width so the pill can center itself, but
  // taps beside the pill must still reach the canvas underneath.
  return (
    <View style={styles.wrapper} pointerEvents="box-none">
      {areEraserOptionsOpen && (
        <View style={styles.eraserOptions}>
          <Pressable
            testID="canvas-eraser-clear"
            accessibilityLabel="Clear canvas"
            disabled={!canClearCanvas}
            style={styles.optionItem}
            onPress={() => {
              setEraserOptionsOpen(false);
              onClearCanvas();
            }}>
            <Text style={[styles.optionText, !canClearCanvas && styles.optionDisabled]}>Clear canvas</Text>
          </Pressable>
        </View>
      )}
      <View style={styles.pill}>
        {TOOLS.map(tool => {
          const active = tool.id === toolMode;
          return (
            <Pressable
              key={tool.id}
              testID={`canvas-tool-${tool.id}`}
              accessibilityLabel={tool.label}
              style={[styles.button, active && styles.buttonActive]}
              onPress={() => pickTool(tool.id)}>
              <Image source={tool.icon} style={[styles.icon, active && styles.iconActive]} />
              {tool.id === 'eraser' && <View style={[styles.caret, active && styles.caretActive]} />}
            </Pressable>
          );
        })}
        <Pressable
          testID="canvas-insert-image"
          accessibilityLabel="Image"
          style={styles.button}
          onPress={() => {
            setEraserOptionsOpen(false);
            onInsertImage();
          }}>
          <Image source={IMAGE_ICON} style={styles.icon} />
        </Pressable>
        <View style={styles.separator} />
        <Pressable
          testID="canvas-help"
          accessibilityLabel="Help"
          style={[styles.button, showHints && styles.buttonActive]}
          onPress={() => {
            setEraserOptionsOpen(false);
            onToggleHints();
          }}>
          <Image source={HELP_ICON} style={[styles.icon, showHints && styles.iconActive]} />
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
    bottom: BOTTOM,
    alignItems: 'center',
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: PADDING_H,
    paddingVertical: PADDING_V,
    borderRadius: 28,
    borderWidth: BORDER,
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
    width: BUTTON,
    height: BUTTON,
    marginHorizontal: BUTTON_MARGIN,
    borderRadius: BUTTON / 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  separator: {
    width: 1,
    height: 24,
    marginHorizontal: SEPARATOR_MARGIN,
    backgroundColor: '#cccccc',
  },
  buttonActive: {
    backgroundColor: '#000000',
  },
  // The eraser's "there is more here" caret, in its corner, pointing at where its options open.
  caret: {
    position: 'absolute',
    right: 5,
    bottom: 5,
    width: 0,
    height: 0,
    borderLeftWidth: 3.5,
    borderRightWidth: 3.5,
    borderBottomWidth: 5,
    borderLeftColor: 'transparent',
    borderRightColor: 'transparent',
    borderBottomColor: '#000000',
  },
  caretActive: {
    borderBottomColor: '#ffffff',
  },
  // Above the pill, over the eraser it belongs to.
  eraserOptions: {
    position: 'absolute',
    bottom: PILL_HEIGHT + 6,
    left: '50%',
    marginLeft: TOOLBAR_GEOMETRY.toolCenterX('eraser') - OPTIONS_WIDTH / 2,
    width: OPTIONS_WIDTH,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: BORDER,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
  },
  optionItem: {
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  optionText: {
    fontSize: 15,
    color: '#000000',
  },
  optionDisabled: {
    opacity: 0.3,
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
