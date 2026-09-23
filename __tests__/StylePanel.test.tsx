/**
 * The style panel (FR19): its open/close toggle, every option, which one is in
 * use, and that each tap sends exactly one style property.
 */
import React from 'react';
import {StyleSheet} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import {COLORS, DEFAULT_STYLE, FILLS, SIZES, type CanvasStyle} from '../src/domain/styles';
import StylePanel, {COLORS_PER_ROW} from '../src/ui/StylePanel';

const renderPanel = (style: CanvasStyle = DEFAULT_STYLE, open = true) => {
  const onChange = jest.fn();
  const onOpen = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(
      <StylePanel style={style} swatch={color => `swatch:${color}`} onChange={onChange} onOpen={onOpen} />,
    );
  });
  const tap = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  if (open) {
    tap('style-toggle');
  }
  // The rendered view (findByProps finds the Option wrapper first), which carries the state and look.
  const host = (testID: string) => renderer.root.find(node => node.props.testID === testID && typeof node.type === 'string');
  const isSelected = (testID: string) => host(testID).props.accessibilityState.selected;
  const look = (testID: string) => StyleSheet.flatten(host(testID).props.style);
  const isPanelShown = () => renderer.root.findAllByProps({testID: 'style-color-red'}).length > 0;
  return {renderer, onChange, onOpen, tap, host, isSelected, look, isPanelShown};
};

test('the panel starts closed behind a toggle that shows the colour in use, and the toggle opens and closes it', () => {
  const {renderer, tap, host, isPanelShown} = renderPanel({...DEFAULT_STYLE, color: 'violet'}, false);
  const toggleState = () => [host('style-toggle').props.accessibilityState.expanded, host('style-toggle').props.accessibilityLabel];
  expect(isPanelShown()).toBe(false);
  expect(JSON.stringify(renderer.toJSON())).toContain('swatch:violet');
  expect(toggleState()).toEqual([false, 'Show styles']);
  tap('style-toggle');
  expect(isPanelShown()).toBe(true);
  expect(toggleState()).toEqual([true, 'Hide styles']);
  tap('style-toggle');
  expect(isPanelShown()).toBe(false);
});

test('shows all 12 colours in the swatch colour it is given, the one in use selected and named', () => {
  const {renderer, isSelected} = renderPanel({...DEFAULT_STYLE, color: 'light-blue'});
  for (const color of COLORS) {
    expect(isSelected(`style-color-${color.id}`)).toBe(color.id === 'light-blue');
  }
  expect(JSON.stringify(renderer.toJSON())).toContain('swatch:red');
  expect(renderer.root.findAllByProps({children: 'Light blue'}).length).toBeGreaterThan(0);
});

test('the fill, dash and size in use are selected', () => {
  const {isSelected} = renderPanel({...DEFAULT_STYLE, fill: 'solid', dash: 'dotted', size: 's'});
  expect(['style-fill-solid', 'style-dash-dotted', 'style-size-s'].map(isSelected)).toEqual([true, true, true]);
  expect(['style-fill-none', 'style-dash-draw', 'style-size-m'].map(isSelected)).toEqual([false, false, false]);
});

test('the option in use is marked subtly: a light background and thin outline, or a thin ring round the colour', () => {
  const {look} = renderPanel({...DEFAULT_STYLE, color: 'red', fill: 'solid'});
  expect(look('style-fill-solid')).toMatchObject({backgroundColor: '#e6e6e6', borderWidth: 1.5});
  expect(look('style-fill-none').borderWidth).toBeUndefined();
  expect(look('style-color-red')).toMatchObject({borderWidth: 2});
  expect(look('style-color-red').backgroundColor).toBeUndefined();
  expect(look('style-color-blue').borderWidth).toBeUndefined();
});

test('the opacity knob sits at the step in use, with the track filled up to it', () => {
  const {look} = renderPanel({...DEFAULT_STYLE, opacity: 0.5});
  expect(look('style-opacity-knob').left).toBe('50%');
  const full = renderPanel({...DEFAULT_STYLE, opacity: 1});
  expect(full.look('style-opacity-knob').left).toBe('90%');
});

test.each([
  ['style-color-red', 'color', 'red'],
  ['style-opacity-0.25', 'opacity', '0.25'],
  ['style-fill-pattern', 'fill', 'pattern'],
  ['style-fill-gradient', 'fill', 'gradient'],
  ['style-dash-dashed', 'dash', 'dashed'],
  ['style-size-xl', 'size', 'xl'],
])('tapping %s sends %s = %s', (testID, property, value) => {
  const {onChange, tap} = renderPanel();
  tap(testID);
  expect(onChange).toHaveBeenCalledWith(property, value);
});

test('a selected image offers no outline besides the four dashes, and no fill', () => {
  const onChange = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(
      <StylePanel
        style={{...DEFAULT_STYLE, dash: 'none'}}
        selectedType="image"
        swatch={color => color}
        onChange={onChange}
        onOpen={jest.fn()}
      />,
    );
  });
  act(() => {
    renderer.root.findByProps({testID: 'style-toggle'}).props.onPress();
  });
  const shown = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  expect(shown('style-dash-none')).toBe(true);
  expect(shown('style-dash-draw')).toBe(true);
  expect(shown('style-fill-none')).toBe(false);
  act(() => {
    renderer.root.findByProps({testID: 'style-dash-dotted'}).props.onPress();
  });
  expect(onChange).toHaveBeenCalledWith('dash', 'dotted');
});

test('anything but an image offers the four dashes and the fills', () => {
  const {host} = renderPanel();
  expect(() => host('style-dash-none')).toThrow();
  expect(host('style-fill-none')).toBeDefined();
});

// The panel opens over the canvas where the hints sit, so it puts them away, the way the ⋮ menu and
// the zoom control do (#12).
test('opening the panel puts the hints away, and closing it does not do so again', () => {
  const panel = renderPanel(DEFAULT_STYLE, false);
  panel.tap('style-toggle');
  expect(panel.onOpen).toHaveBeenCalledTimes(1);
  panel.tap('style-toggle');
  expect(panel.onOpen).toHaveBeenCalledTimes(1);
  panel.tap('style-toggle');
  expect(panel.onOpen).toHaveBeenCalledTimes(2);
});

// #59: the fifth fill. The panel offers one button per fill in the catalog, so a fill added to the
// catalog and nowhere else would leave a style the canvas can hold and the panel cannot choose.
test('every fill in the catalog has a button, gradient included', () => {
  const {isSelected} = renderPanel({...DEFAULT_STYLE, fill: 'gradient'});
  expect(FILLS.map(fill => isSelected(`style-fill-${fill}`))).toEqual(FILLS.map(fill => fill === 'gradient'));
});

// Asked for on the device: a colour should line up with the outline and the size under it, so every
// row of four is laid out the same way rather than the colours using cells of their own (#59).
test('a colour takes the same cell as an outline and a size, so the columns line up', () => {
  const {look} = renderPanel();
  const colour = look('style-color-black').width;
  expect(colour).toBe(look('style-size-m').width);
  expect(colour).toBe(look('style-dash-draw').width);
  // And the same as a fill, so the five fills span exactly what four colours do.
  expect(colour).toBe(look('style-fill-none').width);
});

test('every row spans the panel and spreads its own options across it', () => {
  const {look} = renderPanel();
  const panel = look('style-panel');
  // Wide enough for every fill at full size, counted from FILLS rather than written in: pinned to a
  // number, the panel kept the width it had when there were four fills and the gradient fell off the
  // edge of the screen. The padding is read back from the panel for the same reason.
  expect(panel.width).toBe(look('style-fill-none').width * FILLS.length + panel.padding * 2);
  // One absolute, because the line above moves with whatever the cell is: without this, a cell
  // shrunk to fit a sixth fill would keep the panel honest and the icons unreadable. 44 is the cell
  // the icons were drawn for, so a change here is a decision, not a side effect.
  expect(look('style-fill-none').width).toBe(44);
  // Every row, not just the colours: each row had to be named for this to mean what it says, since
  // a row centred on its own would leave the others flush and nothing would have caught it.
  const rows = ['style-colors-0', 'style-colors-1', 'style-colors-2', 'style-fills', 'style-dashes', 'style-sizes'];
  expect(rows.map(row => look(row).justifyContent)).toEqual(rows.map(() => 'space-between'));
});

// Counted from COLORS and COLORS_PER_ROW rather than written out as [4, 4, 4]: what matters is that
// the rows carve up the colours evenly and drop none of them, and that holds at twelve colours or
// sixteen. Written as literals, this test would have had to be edited to add a colour, and editing
// a test to make it pass again is how a test stops meaning what it says.
test('the colours are carved into full rows, every colour placed once', () => {
  const {host} = renderPanel();
  // The anchor, and the reason the number is four: a colour sits above an outline and a size, so the
  // row holds as many as those rows do. Without this the test reads COLORS_PER_ROW on both sides and
  // any value would satisfy it, which is the same identity the panel width nearly shipped with.
  expect(COLORS_PER_ROW).toBe(SIZES.length);
  const rowCount = Math.ceil(COLORS.length / COLORS_PER_ROW);
  const counts = Array.from({length: rowCount}, (_, row) => host(`style-colors-${row}`).props.children.length);
  expect(counts.reduce((total, count) => total + count, 0)).toBe(COLORS.length);
  // Full except the last, which is short only when the colours do not divide evenly.
  expect(counts.slice(0, -1)).toEqual(counts.slice(0, -1).map(() => COLORS_PER_ROW));
  expect(counts.at(-1)).toBeLessThanOrEqual(COLORS_PER_ROW);
});
