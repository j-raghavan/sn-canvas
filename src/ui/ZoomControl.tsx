// The zoom control (#12): what the canvas is zoomed to, and one tap to a few
// fixed levels. Zoom to fit and Zoom to 100% were already in the ⋮ menu and
// people pinched and then could not find the way back, so this sits on the
// canvas itself, in the corner the toolbar and action bar leave free, and shows
// the current zoom whether or not it is open.

import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View} from 'react-native';
import type {CanvasUiState} from '../domain/styles';
import type {CanvasCommand} from './nativeCanvasView';

const CHEVRON_DOWN = require('../../assets/icons/chevron-down.png');
const CHEVRON_UP = require('../../assets/icons/chevron-up.png');

// The pill's own size and place, for the hint that points at it (HelpHints).
const LEFT = 16;
const BOTTOM = 92;
const WIDTH = 78;
const HEIGHT = 36;

export const ZOOM_GEOMETRY = {left: LEFT, bottom: BOTTOM, width: WIDTH, height: HEIGHT, centerX: LEFT + WIDTH / 2};

type Preset = {command: CanvasCommand; label: string; testID: string};

/** Widest first, as they read going up the panel from the button that opens it. */
const PRESETS: readonly Preset[] = [
  {command: 'zoomToFit', label: 'Fit', testID: 'canvas-zoom-fit'},
  {command: 'zoomTo100', label: '100%', testID: 'canvas-zoom-100'},
  {command: 'zoomTo50', label: '50%', testID: 'canvas-zoom-50'},
  {command: 'zoomTo25', label: '25%', testID: 'canvas-zoom-25'},
];

type Props = {
  ui: CanvasUiState;
  onCommand: (command: CanvasCommand) => void;
};

export default function ZoomControl({ui, onCommand}: Props): React.JSX.Element {
  const [isOpen, setOpen] = useState(false);

  const run = (command: CanvasCommand) => {
    setOpen(false);
    onCommand(command);
  };

  return (
    // box-none: taps beside the control still reach the canvas underneath.
    <View style={styles.wrapper} pointerEvents="box-none">
      {isOpen && (
        <View style={styles.panel}>
          {PRESETS.map(preset => (
            <Pressable
              key={preset.command}
              testID={preset.testID}
              accessibilityLabel={`Zoom to ${preset.label}`}
              style={styles.preset}
              onPress={() => run(preset.command)}>
              <Text style={styles.presetText}>{preset.label}</Text>
            </Pressable>
          ))}
        </View>
      )}
      <Pressable
        testID="canvas-zoom"
        accessibilityLabel={`Zoom, ${ui.zoomPercent}%`}
        style={styles.button}
        onPress={() => setOpen(open => !open)}>
        <Text style={styles.buttonText}>{ui.zoomPercent}%</Text>
        {/* The same chevron the style button uses: without it the pill reads as a readout, not a control. */}
        <Image source={isOpen ? CHEVRON_UP : CHEVRON_DOWN} style={styles.chevron} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  // Bottom left, level with the action bar, which starts well to the right of here.
  wrapper: {
    position: 'absolute',
    left: LEFT,
    bottom: BOTTOM,
    alignItems: 'flex-start',
  },
  button: {
    flexDirection: 'row',
    width: WIDTH,
    height: HEIGHT,
    paddingHorizontal: 10,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
  },
  chevron: {
    width: 12,
    height: 12,
    marginLeft: 4,
    tintColor: '#000000',
  },
  buttonText: {
    fontSize: 15,
    color: '#000000',
  },
  panel: {
    marginBottom: 6,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
  },
  preset: {
    minWidth: WIDTH,
    paddingHorizontal: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  presetText: {
    fontSize: 15,
    color: '#000000',
  },
});
