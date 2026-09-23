/**
 * The style domain (FR19): the canvas-state event validated at the bridge,
 * colour names, and what a swatch shows.
 */
import fs from 'fs';
import path from 'path';
import {
  COLORS,
  DASHES,
  DEFAULT_STYLE,
  FILLS,
  IMAGE_DASHES,
  INITIAL_UI_STATE,
  OPACITY_STEPS,
  SIZES,
  colorName,
  opacityStepIndex,
  parseUiState,
  swatchColor,
} from '../src/domain/styles';

/**
 * The style panel's catalog also lives, independently, as CanvasStyle.kt's enums (FR19) — the two must
 * agree on ids and values. style-catalog.json at the repo root is the golden fixture both this test and
 * CanvasStyleTest.kt assert their own copy against, so the two can't silently drift apart (see its
 * `_comment`). Read from disk, not imported, since resolveJsonModule isn't set for this project.
 */
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'style-catalog.json'), 'utf8'));

test("tldraw's 12 colours, with tldraw's defaults, matching the shared style-catalog.json fixture", () => {
  expect(COLORS).toEqual(catalog.colors);
  expect([...FILLS]).toEqual(catalog.fills);
  expect([...DASHES]).toEqual(catalog.dashes);
  expect([...IMAGE_DASHES]).toEqual(catalog.imageDashes);
  expect([...SIZES]).toEqual(catalog.sizes.map((size: {id: string}) => size.id));
  expect([...OPACITY_STEPS]).toEqual(catalog.opacitySteps);
  expect(DEFAULT_STYLE).toEqual(catalog.defaultStyle);
});

test('parseUiState reads a well-formed event body', () => {
  const style = {color: 'light-green', opacity: 0.25, fill: 'pattern', dash: 'dashed', size: 'xl'};
  expect(
    parseUiState({
      canUndo: true,
      canRedo: true,
      hasSelection: true,
      hasContent: true,
      selectionCount: 3,
      canUngroup: true,
      hasLink: true,
      canRemoveTableRow: true,
      canRemoveTableColumn: true,
      zoomPercent: 250,
      selectedType: 'table',
      style,
    }),
  ).toEqual({
    canUndo: true,
    canRedo: true,
    hasSelection: true,
    hasContent: true,
    canRemoveTableRow: true,
    canRemoveTableColumn: true,
    zoomPercent: 250,
    selectionCount: 3,
    canUngroup: true,
    hasLink: true,
    selectedType: 'table',
    style,
  });
  expect(parseUiState({selectedType: ''}).selectedType).toBeNull();
});

// A zoom of nothing would read as 0% and offer no way back, so anything unusable is actual size (#12).
test('a zoom the canvas could not say falls back to actual size', () => {
  expect(parseUiState({zoomPercent: 250}).zoomPercent).toBe(250);
  expect(parseUiState({}).zoomPercent).toBe(100);
  expect(parseUiState({zoomPercent: 0}).zoomPercent).toBe(100);
  expect(parseUiState({zoomPercent: -50}).zoomPercent).toBe(100);
  expect(parseUiState({zoomPercent: 'lots'}).zoomPercent).toBe(100);
  expect(parseUiState({zoomPercent: 12.5}).zoomPercent).toBe(100);
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

// #59: a fill the canvas draws has to survive the bridge, or the panel shows one thing while the
// canvas draws another and the next tap sends the wrong style back.
test('every fill the catalog lists comes back through parseUiState, gradient included', () => {
  expect(FILLS.map(fill => parseUiState({style: {fill}}).style.fill)).toEqual([...FILLS]);
  // And one it has never heard of still falls back rather than coming through.
  expect(parseUiState({style: {fill: 'plaid'}}).style.fill).toBe(DEFAULT_STYLE.fill);
});
