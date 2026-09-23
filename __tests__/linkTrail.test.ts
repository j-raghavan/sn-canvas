import {MAX_TRAIL, backLabelOf, noteNameOf, noteOf, trailKeptAt, withStep, type TrailStep} from '../src/domain/linkTrail';

const step = (n: number): TrailStep => ({notePath: `/n${n}.note`, page: 0, kind: 'note', canvasId: `c-${n}`, to: `/n${n + 1}.note`});

/** A link to another canvas of the same note (#2): it never left, so it has no note it led to. */
const canvasStep = (n: number): TrailStep => ({notePath: `/n${n}.note`, page: 0, kind: 'canvas', canvasId: `c-${n}`});

test('steps stack newest last, and only the newest MAX_TRAIL are kept', () => {
  const trail = Array.from({length: MAX_TRAIL + 2}, (_, n) => step(n)).reduce(withStep, [] as TrailStep[]);
  expect(trail).toHaveLength(MAX_TRAIL);
  expect(trail.at(-1)).toEqual(step(MAX_TRAIL + 1));
});

test('the trail is kept only in the note the last link led to', () => {
  const trail = [step(1), step(2)];
  expect(trailKeptAt(trail, {notePath: '/n3.note', page: 4})).toBe(trail);
  expect(trailKeptAt(trail, {notePath: '/n2.note', page: 0})).toEqual([]);
  expect(trailKeptAt(trail, null)).toEqual([]);
  expect(trailKeptAt([], {notePath: '/n3.note', page: 0})).toEqual([]);
});

test("a note's name is its file name without the folder or .note", () => {
  expect(noteNameOf('/storage/emulated/0/Note/💡 Ideas/ChronoGuard.note')).toBe('ChronoGuard');
  expect(noteNameOf('plain')).toBe('plain');
});

// #2: a step back into another canvas of the same note has no note name to offer, since a canvas has
// only an id and the date it was made.
test('the Back control names the note for a note step, and says canvas for a canvas one', () => {
  expect(backLabelOf({notePath: '/Note/Work/plan.note', page: 2, kind: 'note', canvasId: 'c-1', to: '/n.note'})).toBe('plan');
  expect(backLabelOf({notePath: '/Note/Work/plan.note', page: 2, kind: 'canvas', canvasId: 'c-1'})).toBe(
    'canvas',
  );
});

// #2: a canvas step never left its note, so the trail it tops is good in the note it is already in.
test('a canvas step keeps the trail in the note it never left', () => {
  const trail = [step(1), canvasStep(2)];
  expect(trailKeptAt(trail, {notePath: '/n2.note', page: 0})).toEqual(trail);
  expect(trailKeptAt(trail, {notePath: '/n3.note', page: 0})).toEqual([]);
  expect(trailKeptAt(trail, null)).toEqual([]);
});

test('noteOf is where a step is still good: the note a note step led to, and the one a canvas step stayed in', () => {
  expect(noteOf(step(1))).toBe('/n2.note');
  expect(noteOf(canvasStep(1))).toBe('/n1.note');
});
