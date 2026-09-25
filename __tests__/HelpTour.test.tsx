/**
 * The tour (#71): six illustrated pages, stepped through, skippable from the first one, and
 * reachable again afterwards from the ⋮ menu.
 */
import React from 'react';
import {useWindowDimensions, View} from 'react-native';
import ReactTestRenderer, {act} from 'react-test-renderer';
import HelpTour from '../src/ui/HelpTour';
import {ACTION_BAR_GEOMETRY} from '../src/ui/ActionBar';
import {TOOLBAR_GEOMETRY} from '../src/ui/Toolbar';

jest.mock('react-native/Libraries/Utilities/useWindowDimensions');
const onAScreen = (width: number, height: number) =>
  (useWindowDimensions as jest.Mock).mockReturnValue({width, height, scale: 1.875, fontScale: 1});

const {pages} = require('../assets/tour/tour.json');

const render = (onOpenEveryTime = false, standardScreen = true, screen?: () => void) => {
  if (screen) {
    screen();
  } else if (standardScreen) {
    onAScreen(1024, 1365);
  }
  const onClose = jest.fn();
  const onOpenEveryTimeChange = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(
      <HelpTour onClose={onClose} onOpenEveryTime={onOpenEveryTime} onOpenEveryTimeChange={onOpenEveryTimeChange} />,
    );
  });
  /** What the tour is actually given room for, which is the canvas area rather than the whole window. */
  const laidOutIn = (width: number, height: number) =>
    act(() => {
      renderer.root.findByProps({testID: 'tour'}).props.onLayout({nativeEvent: {layout: {width, height, x: 0, y: 0}}});
    });
  const tap = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  /** What the panel actually reads, which is not the same question as which controls are there. */
  const reads = () => JSON.stringify(renderer.toJSON());
  const checked = (testID: string) => renderer.root.findByProps({testID}).props.accessibilityState.checked;
  /** How big the page is actually drawn, which is what has to fit the screen it is on. */
  const pageSize = (): {width: number; height: number} => renderer.root.findByProps({testID: 'tour-page'}).props.style;
  /** Which file something is drawing, so the page and its arrow can be named rather than counted. */
  const showing = (testID: string): string => renderer.root.findByProps({testID}).props.source.testUri;
  /** The arrow's placement, flattened out of the style array it is passed in. */
  const arrowPlacement = (): Record<string, number> =>
    Object.assign({}, ...renderer.root.findByProps({testID: 'tour-arrow'}).props.style.filter((s: unknown) => s && typeof s === 'object'));
  /** The box the page is laid into, which is the card's size and does not change page to page. */
  const boxSize = (): {width: number; height: number} =>
    renderer.root.findByProps({testID: 'tour-stage'}).props.style.find((s: {width?: number}) => s && s.width !== undefined);
  /** Each dot as it is drawn, so the one being read can be told from the rest. */
  const dots = (): string[] =>
    pages.map((_: unknown, i: number) => JSON.stringify(renderer.root.findByProps({testID: `tour-dot-${i}`}).props.style));
  /** Everything laid on the panel, flattened, so where it sits can be compared page to page. */
  const panelStyle = () => JSON.stringify(renderer.root.findByProps({testID: 'tour-panel'}).props.style);
  /** The box the page sits in, which is what fixes the card's height across pages of different sizes. */
  const stageStyle = () => JSON.stringify(renderer.root.findByProps({testID: 'tour-stage'}).props.style);
  return {onClose, onOpenEveryTimeChange, renderer, tap, laidOutIn, has, reads, checked, pageSize, panelStyle, stageStyle, showing, arrowPlacement, boxSize, dots};
};

test('it starts on the first page, with no way back from it', () => {
  const {has} = render();
  expect(has('tour-dot-0')).toBe(true);
  // Nothing to go back to, so there is no control for it rather than a dead one.
  expect(has('tour-back')).toBe(false);
  expect(has('tour-next')).toBe(true);
});

test('Next walks to the end, where Next becomes Done', () => {
  const {tap, has, reads} = render();
  expect(reads()).toContain('Next');
  for (let page = 1; page < pages.length; page += 1) {
    tap('tour-next');
  }
  expect(has('tour-next')).toBe(false);
  expect(has('tour-done')).toBe(true);
  expect(has('tour-back')).toBe(true);
  // What it says, not only what it is: a last page reading Next while it closes the tour is a
  // button that lies about what it does.
  expect(reads()).toContain('Done');
  expect(reads()).not.toContain('Next');
});

test('Back walks the other way', () => {
  const {tap, has} = render();
  tap('tour-next');
  expect(has('tour-back')).toBe(true);
  tap('tour-back');
  expect(has('tour-back')).toBe(false);
});

test.each([
  ['Skip, from the first page', 'tour-skip'],
  ['a tap outside the panel', 'tour-scrim'],
])('%s closes it', (_case, testID) => {
  const {tap, onClose} = render();
  tap(testID);
  expect(onClose).toHaveBeenCalledTimes(1);
});

test('Done closes it', () => {
  const {tap, onClose} = render();
  for (let page = 1; page < pages.length; page += 1) {
    tap('tour-next');
  }
  tap('tour-done');
  expect(onClose).toHaveBeenCalledTimes(1);
});

// The pages are drawn by scripts/draw_tour.py, and the component lists them by hand because
// require() needs a literal. The two would drift silently: a page added to the script and not to
// the component is simply never shown.
test('every page the script drew has a dot to reach it', () => {
  const {has} = render();
  expect(pages.length).toBeGreaterThan(1);
  for (let page = 0; page < pages.length; page += 1) {
    expect(has(`tour-dot-${page}`)).toBe(true);
  }
  expect(has(`tour-dot-${pages.length}`)).toBe(false);
});

// #71: off unless asked for. The tour greets you once; someone who wants it every time says so
// here, rather than going to the menu for it each time.
test('the every-time box is off by default and asks for the other state when tapped', () => {
  const {checked, tap, onOpenEveryTimeChange} = render();
  expect(checked('tour-every-time')).toBe(false);
  tap('tour-every-time');
  expect(onOpenEveryTimeChange).toHaveBeenCalledWith(true);
});

// What the box looks like, not only what it reports: on an e-ink screen a tick that is never drawn
// is a box that does not respond to being tapped.
test('the box is drawn ticked when it is on and empty when it is off', () => {
  const off = render();
  expect(off.reads()).not.toContain('✓');
  const on = render(true);
  expect(on.reads()).toContain('✓');
  // And the outline darkens with it, so the change reads at a glance rather than only up close.
  const boxStyle = (r: ReturnType<typeof render>) =>
    JSON.stringify(r.renderer.root.findByProps({testID: 'tour-every-time'}).props.children[0].props.style);
  expect(boxStyle(on)).not.toBe(boxStyle(off));
});

test('the box shows as on when it has been asked for, and asks to turn it off', () => {
  const {checked, tap, onOpenEveryTimeChange} = render(true);
  expect(checked('tour-every-time')).toBe(true);
  tap('tour-every-time');
  expect(onOpenEveryTimeChange).toHaveBeenCalledWith(false);
});

// #71: the pages are drawn at one size and the screens are not. A Nomad and a Manta report
// different windows, and a card that is drawn for one and shown on the other either wastes the
// space or runs off the edge.
test('the card is never wider than the screen it is on', () => {
  const roomy = render().pageSize();
  onAScreen(420, 700);
  const cramped = render(false, false).pageSize();
  expect(cramped.width).toBeLessThan(roomy.width);
  expect(cramped.width).toBeLessThanOrEqual(420);
  // Shrunk, not squashed: the page is a picture and reading it depends on its shape.
  expect(cramped.height / cramped.width).toBeCloseTo(roomy.height / roomy.width, 2);
});

// A screen with width to spare and no height is the case width alone cannot catch: the card would
// fit across and run off the bottom, taking Next and the dots with it.
test('the card is fitted to the shorter way as well as the wider one', () => {
  const tall = render().pageSize();
  const short = render(false, false, () => onAScreen(2000, 420)).pageSize();
  expect(short.height).toBeLessThan(tall.height);
  expect(short.height).toBeLessThanOrEqual(420);
});

test('it is not blown up past the size it was drawn at', () => {
  const big = render(false, false, () => onAScreen(4000, 4000)).pageSize();
  expect(big.width).toBe(pages[0].width);
});

// #71: the card stays where it is from the first page to the last. One that shifts to dodge what it
// is describing makes the reader find it again each time, and the arrow is what says where to look.
// Run on a screen with room and on one without: with room to spare nothing is scaled, so a card
// sized to the page being read looks identical to one sized to the tallest, and the difference only
// shows where something has to give.
test.each([
  ['with room to spare', 1024, 1365],
  ['on a screen that has to scale it', 700, 520],
])('the card does not move or change size as the pages turn, %s', (_case, width, height) => {
  const {tap, panelStyle, stageStyle} = render(false, false, () => onAScreen(width, height));
  const [where, size] = [panelStyle(), stageStyle()];
  for (let page = 1; page < pages.length; page += 1) {
    tap('tour-next');
    // Both where it is and how big it is: the pages are not all the same height, and a card that
    // grows and shrinks around them is as unsettling to read as one that slides about.
    expect(panelStyle()).toBe(where);
    expect(stageStyle()).toBe(size);
  }
});

// #71: six pages, each drawn for the thing it explains. Showing one page's drawing on all six would
// step through the same card six times, which reads as a tour that is broken rather than short.
test('each page shows its own drawing and its own arrow, in the order they were drawn', () => {
  const {tap, showing} = render();
  for (let page = 0; page < pages.length; page += 1) {
    expect(showing('tour-page')).toContain(`page-${page + 1}.png`);
    expect(showing('tour-arrow')).toContain(`arrow-${page + 1}.png`);
    if (page < pages.length - 1) {
      tap('tour-next');
    }
  }
});

// The arrow does the half a drawing cannot: it says where on this screen the thing actually is. One
// that stays put through the tour is pointing at the wrong control on five of the six pages.
test('the arrow moves as the pages turn', () => {
  const {tap, arrowPlacement} = render();
  const seen = new Set([JSON.stringify(arrowPlacement())]);
  for (let page = 1; page < pages.length; page += 1) {
    tap('tour-next');
    seen.add(JSON.stringify(arrowPlacement()));
  }
  expect(seen.size).toBe(pages.length);
});

// Where the tip lands is the whole of what makes it an arrow at something rather than a decoration,
// and the toolbar is asked where the tool is rather than the number being written down twice.
test("the first page's arrow tips onto the draw tool it is about", () => {
  const {arrowPlacement} = render();
  expect(arrowPlacement().marginLeft + pages[0].arrow.tipX).toBe(TOOLBAR_GEOMETRY.toolCenterX('draw'));
});

// Six identical dots say how long the tour is but not where in it you are.
test('the dot for the page being read is marked, and it is the only one', () => {
  const {tap, dots} = render();
  for (let page = 0; page < pages.length; page += 1) {
    const drawn = dots();
    expect(drawn.filter(dot => dot === drawn[page])).toHaveLength(1);
    if (page < pages.length - 1) {
      tap('tour-next');
    }
  }
});

// The card is one size for every page, so the shorter pages sit in a box taller than they are. They
// have to keep their own shape in it: stretched to fill it, a page is drawn at the wrong proportions.
test('a page shorter than the tallest keeps its own shape rather than filling the card', () => {
  const shortest = pages.reduce((a: {height: number}, b: {height: number}) => (a.height <= b.height ? a : b));
  const {tap, pageSize, boxSize} = render();
  for (let page = 0; page < pages.indexOf(shortest); page += 1) {
    tap('tour-next');
  }
  expect(pageSize().height / pageSize().width).toBeCloseTo(shortest.height / shortest.width, 2);
  expect(pageSize().height).toBeLessThan(boxSize().height);
});

// Fitting the picture is not fitting the card. The padding around it, and the every-time box and
// the buttons under it, are what run off the bottom of a short screen when only the picture is
// measured. These are what the card needs at the least, read off the styles rather than imported
// from them, so that shrinking one in the component is a failure here rather than a matching edit.
const NEEDED = {padding: 40, margin: 48, buttons: 100};

test.each([
  ['a narrow one', 420, 700],
  ['a short one', 700, 520],
  ['a Nomad', 1024, 1365],
])('the whole card fits on %s, buttons and padding and all', (_case, width, height) => {
  const {boxSize} = render(false, false, () => onAScreen(width, height));
  expect(boxSize().width + NEEDED.padding + NEEDED.margin).toBeLessThanOrEqual(width);
  expect(boxSize().height + NEEDED.padding + NEEDED.buttons + NEEDED.margin).toBeLessThanOrEqual(height);
});

// Which edge each arrow hangs from, which is not decoration: a control near the top of the screen
// has to be pointed at from above, and one above the toolbar from below. Hang them all off the same
// edge and five of the six arrows reach for a part of the screen their control is nowhere near.
test.each([
  ['the tools', 0, 'bottom'],
  ['colour and style, in the top corner', 1, 'top'],
  ['the bar over the tools', 2, 'bottom'],
  ['the three dots on that bar', 3, 'bottom'],
  ['linking, from that bar', 4, 'bottom'],
  ['getting a canvas out, from the header', 5, 'top'],
])('the arrow on the page about %s hangs from the %s of the screen', (_case, page, edge) => {
  const {tap, arrowPlacement} = render();
  for (let step = 0; step < page; step += 1) {
    tap('tour-next');
  }
  expect(Object.keys(arrowPlacement())).toContain(edge);
  expect(Object.keys(arrowPlacement())).not.toContain(edge === 'top' ? 'bottom' : 'top');
});

// The toolbar is opaque and draws over what is under it, so an arrow whose tip reaches down into
// the pill loses its head there and reads as a curve stopping at nothing.
test('the arrow at the tools stops above the pill rather than inside it', () => {
  const {arrowPlacement} = render();
  const arrow = pages[0].arrow;
  // fromBottomCenter hangs the image so its tip is the distance given above the screen's bottom.
  const tip = arrowPlacement().bottom + (arrow.height - arrow.tipY);
  expect(tip).toBeGreaterThanOrEqual(TOOLBAR_GEOMETRY.top);
});

// It covers a good part of the scrim, and everywhere else outside the card closes the tour. A tap
// that lands on the arrow and does nothing reads as the tour having stuck. It has to be the View
// around it that is deaf to touches: on the old architecture nothing asks an Image about it, so the
// same thing said on the Image itself is read by nobody and the arrow keeps eating the tap.
test('the arrow does not swallow the taps that close the tour', () => {
  const {renderer} = render();
  const layer = renderer.root.findByProps({testID: 'tour-arrow-layer'});
  expect(layer.type).toBe(View);
  expect(layer.props.pointerEvents).toBe('none');
  expect(layer.findAllByProps({testID: 'tour-arrow'}).length).toBeGreaterThan(0);
});

// The tour is laid out in the canvas area, under the header, so the window is taller than the room
// it actually has. Fitted to the window, the card runs past the bottom of its own container on any
// screen where the header is the difference between fitting and not.
test('the card is fitted to the room it is given, not to the whole window', () => {
  const tour = render(false, false, () => onAScreen(700, 600));
  const beforeMeasuring = tour.boxSize().height;
  tour.laidOutIn(700, 520);
  expect(tour.boxSize().height).toBeLessThan(beforeMeasuring);
  expect(tour.boxSize().height + NEEDED.padding + NEEDED.buttons + NEEDED.margin).toBeLessThanOrEqual(520);
});

// A layout of nothing is a measurement that has not happened, not a screen with no room on it. Kept
// as the truth it makes every size negative, and what is left is a scrim with an arrow on it and no
// card at all: for the one showing a new install gets, that is the whole tour gone.
test('a layout of nothing leaves the card as it was rather than sizing it to nothing', () => {
  const tour = render(false, false, () => onAScreen(1024, 1365));
  const before = tour.boxSize();
  tour.laidOutIn(0, 0);
  expect(tour.boxSize()).toEqual(before);
  expect(tour.pageSize().width).toBeGreaterThan(0);
});

// The three pages about the bar of actions point at it from above. A tip measured off the toolbar
// below it crosses the whole bar on the way down and stops beside the tool pill, so what the reader
// sees is three pages about the bar all pointing at the tools.
test.each([
  ['the bar over the tools', 2, 'canvas-duplicate'],
  ['the three dots on it', 3, 'canvas-more'],
  ['linking, from it', 4, 'canvas-link'],
])('the arrow on the page about %s stops above the bar, on the right button', (_case, page, button) => {
  const {tap, arrowPlacement} = render();
  for (let step = 0; step < page; step += 1) {
    tap('tour-next');
  }
  const arrow = pages[page].arrow;
  const place = arrowPlacement();
  expect(place.bottom + (arrow.height - arrow.tipY)).toBeGreaterThanOrEqual(ACTION_BAR_GEOMETRY.top);
  expect(place.marginLeft + arrow.tipX).toBe(ACTION_BAR_GEOMETRY.buttonCenterX(button));
});
