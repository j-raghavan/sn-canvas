// The onboarding hints (toggled by the (?) button in Toolbar's dock), drawn the way Excalidraw draws its
// own: a hand-written caption with a hand-drawn arrow to the control it names. Each is one PNG from
// scripts/draw_hints.py, which records in hints.json where its arrow's tip is, so the tip lands on its
// control. Shown by default each time the plugin opens (CanvasScreen); a touch on the canvas, by pen or
// finger, or a toolbar action dismisses them.

import React from 'react';
import {Image, StyleSheet, View, type ImageSourcePropType, type ImageStyle} from 'react-native';
import {TOOLBAR_GEOMETRY} from './Toolbar';
import {ZOOM_GEOMETRY} from './ZoomControl';

type Layout = {width: number; height: number; tipX: number; tipY: number};
const LAYOUT: Record<'header' | 'styles' | 'toolbar' | 'eraser' | 'help' | 'zoom', Layout> = require('../../assets/hints/hints.json');

// CanvasScreen's header: its 16dp padding, then Close and Save to Note (38dp each), to the middle of Export.
const EXPORT_CENTER_RIGHT = 16 + 38 * 2 + 38 / 2;
// StylePanel's toggle: 12dp in from the top right, 40dp tall, about 68dp wide.
const STYLE_TOGGLE_BOTTOM = 12 + 40;
const STYLE_TOGGLE_CENTER_RIGHT = 12 + 68 / 2;
// Arrows stop just short of what they point at.
const GAP = 4;

/** A hint placed so its arrow's tip is [right] in from the right edge and [top] down from the top. */
const tipFromTopRight = (layout: Layout, right: number, top: number): ImageStyle => ({
  top: top - layout.tipY,
  right: right - (layout.width - layout.tipX),
});

/** A hint placed so its arrow's tip is [left] in from the left edge and [bottom] up from the bottom. */
const tipFromBottomLeft = (layout: Layout, left: number, bottom: number): ImageStyle => ({
  bottom: bottom - (layout.height - layout.tipY),
  left: left - layout.tipX,
});

/** A hint placed so its arrow's tip is [x] from the middle and [bottom] up from the bottom. */
const tipFromBottomCenter = (layout: Layout, x: number, bottom: number): ImageStyle => ({
  bottom: bottom - (layout.height - layout.tipY),
  left: '50%',
  marginLeft: x - layout.tipX,
});

const HINTS: ReadonlyArray<{label: string; source: ImageSourcePropType; layout: Layout; place: ImageStyle}> = [
  {
    label: 'Export, save to note & close',
    source: require('../../assets/hints/hint-header.png'),
    layout: LAYOUT.header,
    place: tipFromTopRight(LAYOUT.header, EXPORT_CENTER_RIGHT, GAP),
  },
  {
    label: 'Colours & styles',
    source: require('../../assets/hints/hint-styles.png'),
    layout: LAYOUT.styles,
    place: tipFromTopRight(LAYOUT.styles, STYLE_TOGGLE_CENTER_RIGHT, STYLE_TOGGLE_BOTTOM + GAP),
  },
  {
    label: 'Pick a tool & start drawing!',
    source: require('../../assets/hints/hint-toolbar.png'),
    layout: LAYOUT.toolbar,
    place: tipFromBottomCenter(LAYOUT.toolbar, TOOLBAR_GEOMETRY.toolCenterX('rectangle'), TOOLBAR_GEOMETRY.top + GAP),
  },
  {
    label: 'Tap again to clear the canvas',
    source: require('../../assets/hints/hint-eraser.png'),
    layout: LAYOUT.eraser,
    place: tipFromBottomCenter(LAYOUT.eraser, TOOLBAR_GEOMETRY.toolCenterX('eraser'), TOOLBAR_GEOMETRY.top + GAP),
  },
  {
    label: 'Show or hide these hints',
    source: require('../../assets/hints/hint-help.png'),
    layout: LAYOUT.help,
    place: tipFromBottomCenter(LAYOUT.help, TOOLBAR_GEOMETRY.helpCenterX, TOOLBAR_GEOMETRY.top + GAP),
  },
  {
    label: 'Zoom, and the way back to 100%',
    source: require('../../assets/hints/hint-zoom.png'),
    layout: LAYOUT.zoom,
    place: tipFromBottomLeft(LAYOUT.zoom, ZOOM_GEOMETRY.centerX, ZOOM_GEOMETRY.bottom + ZOOM_GEOMETRY.height + GAP),
  },
];

export default function HelpHints(): React.JSX.Element {
  return (
    <View testID="canvas-hints" style={StyleSheet.absoluteFill} pointerEvents="none">
      {HINTS.map(hint => (
        <Image
          key={hint.label}
          accessibilityLabel={hint.label}
          source={hint.source}
          style={[styles.hint, {width: hint.layout.width, height: hint.layout.height}, hint.place]}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  hint: {
    position: 'absolute',
    // Excalidraw's hint gray, which e-ink renders as a light but legible gray.
    tintColor: '#707070',
  },
});
