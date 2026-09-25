// The tour of the plugin (#71): illustrated pages, stepped through with Back and Next, over a
// scrim so the canvas is still visibly there behind it. Shown once on the first open after an
// install, and from the ⋮ menu any time after.
//
// It complements the arrows in HelpHints rather than replacing them. The arrows say which control
// is which, which is what someone who knows the app wants; the tour says what things do, and can
// show what is not on screen to point at — the ⋮ menu, and the table options that only exist once
// a cell is tapped. Those are the parts an arrow can never reach.
//
// The pages are PNGs from scripts/draw_tour.py, drawn with the app's own icons so they cannot come
// to show a button that no longer looks like that, and tour.json carries each one's size in dp.

import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, useWindowDimensions, type ImageSourcePropType, type ImageStyle} from 'react-native';
import {ACTION_BAR_GEOMETRY} from './ActionBar';
import {TOOLBAR_GEOMETRY} from './Toolbar';

type Arrow = {width: number; height: number; tipX: number; tipY: number};
type Page = {width: number; height: number; arrow: Arrow};
const {pages}: {pages: Page[]} = require('../../assets/tour/tour.json');

// require() needs a literal, so the pages are listed rather than built from an index.
const ART: ImageSourcePropType[] = [
  require('../../assets/tour/page-1.png'),
  require('../../assets/tour/page-2.png'),
  require('../../assets/tour/page-3.png'),
  require('../../assets/tour/page-4.png'),
  require('../../assets/tour/page-5.png'),
  require('../../assets/tour/page-6.png'),
];

const ARROWS: ImageSourcePropType[] = [
  require('../../assets/tour/arrow-1.png'),
  require('../../assets/tour/arrow-2.png'),
  require('../../assets/tour/arrow-3.png'),
  require('../../assets/tour/arrow-4.png'),
  require('../../assets/tour/arrow-5.png'),
  require('../../assets/tour/arrow-6.png'),
];

// Where each page's arrow has to land, in the same terms the hints use. The page says what a thing
// does, which no arrow can; the arrow says where it is, which no page can.
const HEADER_EXPORT_RIGHT = 16 + 38 * 2 + 38 / 2;
const STYLE_TOGGLE_RIGHT = 12 + 68 / 2;
/** Arrows stop just short of what they point at, as the hints' do. */
const GAP = 6;
// Just above the bar's own top edge. Measured off the toolbar below it, the tip landed under the
// bar, having crossed all of it on the way down, and read as pointing at the tool pill instead.
const ACTION_BAR_BOTTOM = ACTION_BAR_GEOMETRY.top + GAP;

const fromBottomCenter = (a: Arrow, x: number, bottom: number): ImageStyle => ({
  bottom: bottom - (a.height - a.tipY),
  left: '50%',
  marginLeft: x - a.tipX,
});
const fromTopRight = (a: Arrow, right: number, top: number): ImageStyle => ({
  top: top - a.tipY,
  right: right - (a.width - a.tipX),
});

/** Where page [index]'s arrow points, given its recorded tip. */
const arrowAt = (index: number, a: Arrow): ImageStyle =>
  [
    fromBottomCenter(a, TOOLBAR_GEOMETRY.toolCenterX('draw'), TOOLBAR_GEOMETRY.top + GAP),
    fromTopRight(a, STYLE_TOGGLE_RIGHT, 12 + 40 + GAP),
    fromBottomCenter(a, ACTION_BAR_GEOMETRY.buttonCenterX('canvas-duplicate'), ACTION_BAR_BOTTOM),
    fromBottomCenter(a, ACTION_BAR_GEOMETRY.buttonCenterX('canvas-more'), ACTION_BAR_BOTTOM),
    fromBottomCenter(a, ACTION_BAR_GEOMETRY.buttonCenterX('canvas-link'), ACTION_BAR_BOTTOM),
    fromTopRight(a, HEADER_EXPORT_RIGHT, GAP),
  ][index];

export type HelpTourProps = {
  /** Closes it: the last page's Done, Skip, or a tap on the scrim. */
  onClose: () => void;
  /** Whether it has been asked for on every open, and how to change that. */
  onOpenEveryTime: boolean;
  onOpenEveryTimeChange: (on: boolean) => void;
};

export default function HelpTour({onClose, onOpenEveryTime, onOpenEveryTimeChange}: HelpTourProps): React.JSX.Element {
  const [page, setPage] = useState(0);
  const last = page === pages.length - 1;
  const art = pages[page];
  // The pages are drawn at one size; the screens are not. A Nomad and a Manta report different
  // windows, so the card is fitted to the one it is on rather than to the device it was drawn for:
  // never wider or taller than there is room for, and never enlarged past its own resolution.
  // The window is the right first guess and the only one available before anything is laid out. It
  // is not the truth: the tour sits in the canvas area, which is shorter than the window by the
  // header above it, so on a screen where that difference decides the fit the card would be cut off
  // at the bottom rather than fitted. The layout that follows says how much room there really is.
  const window = useWindowDimensions();
  const [measured, setMeasured] = useState<{width: number; height: number} | null>(null);
  // A layout of nothing is a measurement that has not happened yet, not a screen with no room on it,
  // and taking it at its word makes every size below it negative.
  const room = measured !== null && measured.width > 0 && measured.height > 0 ? measured : window;
  // Sized to the tallest page, not to the one being read: a card that grows and shrinks as the
  // pages turn makes the reader find it again each time, the same as one that moves sideways.
  const widest = Math.max(...pages.map(p => p.width));
  const tallest = Math.max(...pages.map(p => p.height));
  const fit = Math.min(
    1,
    (room.width - MARGIN * 2 - PADDING * 2) / widest,
    (room.height - MARGIN * 2 - PADDING * 2 - CHROME) / tallest,
  );
  const box = {width: Math.round(widest * fit), height: Math.round(tallest * fit)};
  const shown = {width: Math.round(art.width * fit), height: Math.round(art.height * fit)};

  return (
    <View
      style={styles.scrim}
      testID="tour"
      onLayout={event => {
        const {width, height} = event.nativeEvent.layout;
        setMeasured(current => (current?.width === width && current?.height === height ? current : {width, height}));
      }}>
      {/* A tap anywhere outside the panel closes it, as the ⋮ menu does. */}
      <Pressable style={StyleSheet.absoluteFill} testID="tour-scrim" accessibilityLabel="Close the tour" onPress={onClose} />
      {/* Above the pill rather than inside it: the toolbar is opaque and draws over whatever is
          under it, so an arrow that reaches into it loses its head.

          In a View, and deaf to touches, so the tap it covers still reaches the scrim and closes the
          tour as a tap anywhere else outside does. It has to be the View that is deaf: on the old
          architecture only a ReactViewGroup is asked about pointer events, so the same thing said on
          the Image is read by nobody. This is how HelpHints does it too. */}
      <View testID="tour-arrow-layer" pointerEvents="none" style={StyleSheet.absoluteFill}>
        <Image testID="tour-arrow" source={ARROWS[page]} style={[styles.arrow, {width: art.arrow.width, height: art.arrow.height}, arrowAt(page, art.arrow)]} />
      </View>
      {/* In one place from the first page to the last. A card that moves to dodge what it is about
          makes the reader find it again each time, which costs more than the overlap it avoids, and
          the arrow is what says where to look anyway. */}
      <View testID="tour-panel" style={[styles.panel, {width: box.width + PADDING * 2}]}>
        <Pressable style={styles.skip} testID="tour-skip" accessibilityLabel="Skip the tour" onPress={onClose}>
          <Text style={styles.skipText}>{last ? 'Done' : 'Skip'}</Text>
        </Pressable>
        {/* The page sits at the top of a box the height of the tallest one, so a short page leaves
            space below rather than pulling everything under it upwards. */}
        <View testID="tour-stage" style={[styles.stage, box]}>
          <Image testID="tour-page" source={ART[page]} style={shown} resizeMode="contain" />
        </View>
        {/* Off unless asked for: the tour comes up once on its own, and someone who wants it every
            time can say so here rather than hunting the menu for it each time. */}
        <Pressable
          testID="tour-every-time"
          accessibilityRole="checkbox"
          accessibilityState={{checked: onOpenEveryTime}}
          accessibilityLabel="Show this when Canvas opens"
          style={styles.everyTime}
          onPress={() => onOpenEveryTimeChange(!onOpenEveryTime)}>
          <View style={[styles.box, onOpenEveryTime && styles.boxOn]}>
            {onOpenEveryTime && <Text style={styles.tick}>✓</Text>}
          </View>
          <Text style={styles.everyTimeText}>Show this when Canvas opens</Text>
        </Pressable>
        <View style={styles.chrome}>
          {/* Nothing to go back to on the first page, so there is no control for it: a dead button
              is a small puzzle on the first thing a new user is shown. */}
          <View style={styles.side}>
            {page > 0 && (
              <Pressable testID="tour-back" accessibilityLabel="Back" onPress={() => setPage(page - 1)}>
                <Text style={styles.step}>‹ Back</Text>
              </Pressable>
            )}
          </View>
          <View style={styles.dots}>
            {pages.map((_, index) => (
              <View key={index} testID={`tour-dot-${index}`} style={[styles.dot, index === page && styles.dotHere]} />
            ))}
          </View>
          <View style={[styles.side, styles.sideEnd]}>
            <Pressable
              testID={last ? 'tour-done' : 'tour-next'}
              accessibilityLabel={last ? 'Done' : 'Next'}
              onPress={() => (last ? onClose() : setPage(page + 1))}>
              <Text style={[styles.step, styles.stepOn]}>{last ? 'Done' : 'Next ›'}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </View>
  );
}

const PADDING = 20;
/** Kept off the edges of whatever screen it lands on. */
const MARGIN = 24;
/** What the panel needs below the page: the every-time box, and Back, the dots and Next. */
const CHROME = 104;

const styles = StyleSheet.create({
  scrim: {
    ...StyleSheet.absoluteFillObject,
    // Pale rather than dark: the screen is e-ink, and what is behind should stay legible enough to
    // place the tour against, rather than being blacked out. Half, not more: at 0.78 the toolbar's
    // #cccccc border washes out to #f4, so the pages that point at the pill pointed at an edge that
    // was no longer on screen.
    backgroundColor: 'rgba(255,255,255,0.5)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  panel: {
    padding: PADDING,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#bbbbbb',
    backgroundColor: '#ffffff',
  },
  arrow: {position: 'absolute', tintColor: '#000000'},
  stage: {alignItems: 'center'},
  skip: {position: 'absolute', top: 8, right: 12, padding: 8},
  skipText: {fontSize: 15, color: '#999999'},
  everyTime: {flexDirection: 'row', alignItems: 'center', marginTop: 14, paddingVertical: 4},
  box: {width: 20, height: 20, borderRadius: 4, borderWidth: 1.5, borderColor: '#888888', alignItems: 'center', justifyContent: 'center'},
  boxOn: {borderColor: '#000000'},
  tick: {fontSize: 14, lineHeight: 18, color: '#000000'},
  everyTimeText: {fontSize: 16, color: '#666666', marginLeft: 10},
  chrome: {flexDirection: 'row', alignItems: 'center', marginTop: 10},
  side: {flex: 1},
  sideEnd: {alignItems: 'flex-end'},
  step: {fontSize: 19, color: '#aaaaaa', padding: 6},
  stepOn: {color: '#000000'},
  dots: {flexDirection: 'row', alignItems: 'center'},
  dot: {width: 9, height: 9, borderRadius: 5, marginHorizontal: 5, backgroundColor: '#cccccc'},
  dotHere: {backgroundColor: '#000000'},
});
