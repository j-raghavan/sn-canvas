/**
 * The style domain (FR19): the canvas-state event validated at the bridge,
 * colour names, and what a swatch shows.
 */
import {
  COLORS,
  DEFAULT_STYLE,
  INITIAL_UI_STATE,
  colorName,
  opacityStepIndex,
  parseUiState,
  swatchColor,
} from '../src/domain/styles';

test("tldraw's 12 colours, with tldraw's defaults", () => {
  expect(COLORS).toHaveLength(12);
  expect(DEFAULT_STYLE).toEqual({color: 'black', opacity: 1, fill: 'none', dash: 'draw', size: 'm'});
});

test('parseUiState reads a well-formed event body', () => {
  const style = {color: 'light-green', opacity: 0.25, fill: 'pattern', dash: 'dashed', size: 'xl'};
  expect(parseUiState({canUndo: true, canRedo: true, hasSelection: true, selectedType: 'table', style})).toEqual({
    canUndo: true,
    canRedo: true,
    hasSelection: true,
    selectedType: 'table',
    style,
  });
  expect(parseUiState({selectedType: ''}).selectedType).toBeNull();
});

test('parseUiState falls back to the initial state for anything missing or unknown', () => {
  expect(parseUiState(null)).toEqual(INITIAL_UI_STATE);
  expect(parseUiState({canUndo: 'yes', style: {color: 'magenta', opacity: 3, fill: 'glitter', dash: 7, size: 'xxl'}})).toEqual(
    INITIAL_UI_STATE,
  );
  expect(parseUiState({style: {opacity: 0.05}}).style.opacity).toBe(1);
});

test('colorName names each colour', () => {
  expect(colorName('light-violet')).toBe('Light violet');
  expect(colorName('black')).toBe('Black');
});

test('a swatch shows the e-ink gray when the canvas exports one, otherwise the true colour', () => {
  expect(swatchColor('red', {red: '#101010'})).toBe('#101010');
  expect(swatchColor('red', {})).toBe('#e03131');
  expect(swatchColor('red', null)).toBe('#e03131');
});

test("an image's outline of none reads back from the canvas-state event", () => {
  expect(parseUiState({selectedType: 'image', style: {dash: 'none'}}).style.dash).toBe('none');
});

test('opacityStepIndex finds the nearest slider step', () => {
  expect([1, 0.1, 0.3, 0.6, 0.75].map(opacityStepIndex)).toEqual([4, 0, 1, 2, 3]);
});

