import {MAX_TRAIL, noteNameOf, trailKeptAt, withStep, type TrailStep} from '../src/domain/linkTrail';

const step = (n: number): TrailStep => ({notePath: `/n${n}.note`, page: 0, canvasId: `c-${n}`, to: `/n${n + 1}.note`});

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
