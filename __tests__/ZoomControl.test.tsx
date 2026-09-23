/**
 * The zoom control (#12): the zoom it shows, and the presets behind it. Zoom to
 * fit and Zoom to 100% were already in the ⋮ menu; what this adds is a way to
 * see the zoom and get back without opening a menu.
 */
import React from 'react';
import {Image, Text} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import {INITIAL_UI_STATE, type CanvasUiState} from '../src/domain/styles';
import ZoomControl from '../src/ui/ZoomControl';

const renderControl = (overrides: Partial<CanvasUiState> = {}) => {
  const onCommand = jest.fn();
  const onOpen = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(
      <ZoomControl ui={{...INITIAL_UI_STATE, ...overrides}} onCommand={onCommand} onOpen={onOpen} />,
    );
  });
  const press = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  const isListed = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const shown = () => renderer.root.findByProps({testID: 'canvas-zoom'}).props.accessibilityLabel;
  // What is actually drawn in the pill, not the label beside it: the label alone would pass with the
  // readout hardcoded.
  const readout = () =>
    renderer.root
      .findByProps({testID: 'canvas-zoom'})
      .findAllByType(Text)
      .map(node => node.props.children.join(''))
      .join('');
  // The chevron beside the readout, which is what says the pill can be tapped at all.
  const chevron = () => {
    const images = renderer.root.findByProps({testID: 'canvas-zoom'}).findAllByType(Image);
    return images.length === 0 ? null : images[0].props.source;
  };
  return {onCommand, onOpen, press, isListed, shown, readout, chevron};
};

const PRESETS = ['canvas-zoom-fit', 'canvas-zoom-100', 'canvas-zoom-50', 'canvas-zoom-25'];

test('the zoom is shown without opening anything, which is the way back people could not find', () => {
  expect(renderControl({zoomPercent: 250}).readout()).toBe('250%');
  expect(renderControl({zoomPercent: 5}).readout()).toBe('5%');
  expect(renderControl({zoomPercent: 250}).shown()).toBe('Zoom, 250%');
});

test('the presets are behind the button, and each sends its own command', () => {
  const control = renderControl();
  expect(PRESETS.map(control.isListed)).toEqual([false, false, false, false]);

  control.press('canvas-zoom');
  expect(PRESETS.map(control.isListed)).toEqual([true, true, true, true]);

  control.press('canvas-zoom-50');
  expect(control.onCommand).toHaveBeenCalledWith('zoomTo50');
  // Chosen, so the panel closes rather than sitting over the canvas.
  expect(PRESETS.map(control.isListed)).toEqual([false, false, false, false]);
});

test('each preset sends the command that matches its label', () => {
  const sent = PRESETS.map(testID => {
    const control = renderControl();
    control.press('canvas-zoom');
    control.press(testID);
    return control.onCommand.mock.calls[0][0];
  });
  expect(sent).toEqual(['zoomToFit', 'zoomTo100', 'zoomTo50', 'zoomTo25']);
});

test('the button closes the panel again, so it is not stuck open over the canvas', () => {
  const control = renderControl();
  control.press('canvas-zoom');
  expect(control.isListed('canvas-zoom-fit')).toBe(true);
  control.press('canvas-zoom');
  expect(control.isListed('canvas-zoom-fit')).toBe(false);
  expect(control.onCommand).not.toHaveBeenCalled();
});

// Reported on the device: without it the pill reads as a readout and nobody tries tapping it. The
// style button solves the same problem the same way, so the chevron is the app's own signal (#12).
test('the pill carries a chevron, which flips when the panel opens', () => {
  const control = renderControl();
  const closed = control.chevron();
  expect(closed).not.toBeNull();

  control.press('canvas-zoom');
  const open = control.chevron();
  expect(open).not.toBeNull();
  expect(open).not.toEqual(closed);

  control.press('canvas-zoom');
  expect(control.chevron()).toEqual(closed);
});

// The panel opens upward, right where the hint's arrow comes down, so the hint has to go with it. The
// ⋮ menu, the tools and an inserted image all put the hints away the same way (#12).
test('opening the panel puts the hints away, and closing it does not do so again', () => {
  const control = renderControl();
  control.press('canvas-zoom');
  expect(control.onOpen).toHaveBeenCalledTimes(1);

  control.press('canvas-zoom');
  expect(control.onOpen).toHaveBeenCalledTimes(1);

  control.press('canvas-zoom');
  expect(control.onOpen).toHaveBeenCalledTimes(2);
});
