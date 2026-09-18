/**
 * The link index (FR12/FR13): the canvas last open, and the links pending
 * until the note places a thumbnail and a lasso claims it, read back
 * defensively from its JSON.
 */
import {
  EMPTY_INDEX,
  MAX_PENDING,
  claimPending,
  lastCanvasFor,
  elementSummary,
  notePageOf,
  numberOf,
  parseCanvasIndex,
  pictureNumbersOf,
  serializeCanvasIndex,
  withLastCanvas,
  withPending,
  type NotePage,
  type PendingLink,
} from '../src/domain/canvasIndex';

const PAGE: NotePage = {notePath: '/note.note', page: 0};
const pendingFor = (canvasId: string, knownPictureNumbers: number[] = [], at: NotePage = PAGE): PendingLink => ({
  ...at,
  canvasId,
  knownPictureNumbers,
});
const pictureNumbered = (num: number) => ({uuid: 'copy', type: 200, numInPage: num});

test('an index survives its save and load: the canvas each note reopens, and pending links alike', () => {
  const index = withPending(withLastCanvas(EMPTY_INDEX, 'c-1', '/note.note'), pendingFor('c-2', [3, 7]));
  expect(parseCanvasIndex(serializeCanvasIndex(index))).toEqual(index);
});

test('a canvas is recorded against the note it was open in, and without one only as the last canvas', () => {
  const inNote = withLastCanvas(EMPTY_INDEX, 'c-1', '/a.note');
  expect(inNote.lastByNote).toEqual({'/a.note': 'c-1'});
  expect(lastCanvasFor(inNote, '/a.note')).toBe('c-1');
  // Another note has none of its own, and c-1 is spoken for, so it gets nothing.
  expect(lastCanvasFor(inNote, '/b.note')).toBeNull();
  // No note to go by: the canvas last open anywhere is the best guess there is.
  expect(lastCanvasFor(inNote, null)).toBe('c-1');

  const noNote = withLastCanvas(EMPTY_INDEX, 'c-9', null);
  expect(noNote.lastByNote).toEqual({});
  expect(noNote.lastCanvasId).toBe('c-9');
});

test('a canvas recorded before notes owned them goes to the first note that asks, and stays with it', () => {
  // What an index saved by an earlier build looks like: a last canvas, and no note against it.
  const upgraded = parseCanvasIndex(JSON.stringify({lastCanvasId: 'c-old', pending: []}));
  expect(upgraded.lastByNote).toEqual({});
  expect(lastCanvasFor(upgraded, '/a.note')).toBe('c-old');
  // Once a note has claimed it, no other note is handed the same canvas.
  const claimed = withLastCanvas(upgraded, 'c-old', '/a.note');
  expect(lastCanvasFor(claimed, '/b.note')).toBeNull();
});

test('a damaged or missing lastByNote reads as no note owning anything', () => {
  const pairs = JSON.stringify({lastByNote: {'/a.note': 'c-1', '/b.note': 'not a canvas', '': 'c-2'}, pending: []});
  expect(parseCanvasIndex(pairs).lastByNote).toEqual({'/a.note': 'c-1'});
  expect(parseCanvasIndex(JSON.stringify({lastByNote: ['c-1'], pending: []})).lastByNote).toEqual({});
  expect(parseCanvasIndex(JSON.stringify({lastByNote: null, pending: []})).lastByNote).toEqual({});
});

test.each([
  ['no file', null],
  ['unparseable JSON', '{nope'],
  ['JSON that is not an object', '42'],
  ['null', 'null'],
  ['pending links that are not a list', JSON.stringify({pending: 'nope'})],
  ['a last canvas that is not a canvas id', JSON.stringify({lastCanvasId: '/x'})],
])('%s reads as the empty index', (_case, json) => {
  expect(parseCanvasIndex(json)).toEqual(EMPTY_INDEX);
});

test.each([
  ['nothing', null],
  ['not an object', 42],
  ['no canvas id', {notePath: '/n', page: 0, knownPictureNumbers: []}],
  ['no note path', {canvasId: 'c-1', page: 0, knownPictureNumbers: []}],
  ['an empty note path', {canvasId: 'c-1', notePath: '', page: 0, knownPictureNumbers: []}],
  ['a page that is not a whole number', {canvasId: 'c-1', notePath: '/n', page: 1.5, knownPictureNumbers: []}],
  ['a negative page', {canvasId: 'c-1', notePath: '/n', page: -1, knownPictureNumbers: []}],
  ['no known picture numbers (a build that recorded uuids)', {canvasId: 'c-1', notePath: '/n', page: 0, knownPictures: ['u']}],
  ['a known picture number below 1', {canvasId: 'c-1', notePath: '/n', page: 0, knownPictureNumbers: [0]}],
])('a pending link with %s is dropped', (_case, link) => {
  expect(parseCanvasIndex(JSON.stringify({pending: [link]})).pending).toEqual([]);
});

test('only the newest pending links are kept, saved or loaded', () => {
  const links = Array.from({length: MAX_PENDING + 5}, (_, n) => pendingFor(`c-${n}`));
  const added = links.reduce(withPending, EMPTY_INDEX);
  expect(added.pending).toEqual(links.slice(-MAX_PENDING));
  expect(parseCanvasIndex(JSON.stringify({pending: links})).pending).toEqual(links.slice(-MAX_PENDING));
});

describe('claimPending', () => {
  test('a lassoed picture the page did not have claims the link pending on its page', () => {
    const index = withPending(EMPTY_INDEX, pendingFor('c-1', [5]));
    const lassoed = [{uuid: 'stroke', type: 0, numInPage: 3}, pictureNumbered(42)];
    expect(claimPending(index, lassoed, PAGE)).toEqual({index: EMPTY_INDEX, canvasId: 'c-1', picture: pictureNumbered(42)});
  });

  test('with two thumbnails pending on one page, each picture claims its own canvas, in either order', () => {
    // A's link knew no pictures; B's, left once A's thumbnail was placed as picture 42, knew it.
    const index = withPending(withPending(EMPTY_INDEX, pendingFor('c-a')), pendingFor('c-b', [42]));
    expect(claimPending(index, [pictureNumbered(42)], PAGE)?.canvasId).toBe('c-a');
    expect(claimPending(index, [pictureNumbered(43)], PAGE)?.canvasId).toBe('c-b');
    const afterB = claimPending(index, [pictureNumbered(43)], PAGE)?.index ?? EMPTY_INDEX;
    expect(claimPending(afterB, [pictureNumbered(42)], PAGE)?.canvasId).toBe('c-a');
  });

  test('a picture with no number claims the newest link pending on its page', () => {
    const index = withPending(withPending(EMPTY_INDEX, pendingFor('c-a')), pendingFor('c-b', [42]));
    expect(claimPending(index, [{type: 200}], PAGE)?.canvasId).toBe('c-b');
  });

  test('claims nothing for a link pending elsewhere, a picture known already, or a lasso with no picture', () => {
    const otherNote = withPending(EMPTY_INDEX, pendingFor('c-1', [], {notePath: '/other.note', page: 0}));
    expect(claimPending(otherNote, [pictureNumbered(9)], PAGE)).toBeNull();
    const otherPage = withPending(EMPTY_INDEX, pendingFor('c-1', [], {notePath: '/note.note', page: 3}));
    expect(claimPending(otherPage, [pictureNumbered(9)], PAGE)).toBeNull();
    expect(claimPending(withPending(EMPTY_INDEX, pendingFor('c-1', [9])), [pictureNumbered(9)], PAGE)).toBeNull();
    expect(claimPending(withPending(EMPTY_INDEX, pendingFor('c-1')), [{uuid: 'stroke', type: 0, numInPage: 9}], PAGE)).toBeNull();
  });
});

test('pictureNumbersOf keeps the numbers of the pictures, known by their type or their picture, in order', () => {
  const elements = [
    {numInPage: 1, type: 200},
    {numInPage: 2, type: 0},
    {numInPage: 3, picture: {picturePath: 'plugin/1.png'}},
    {numInPage: 4, picture: {picturePath: null}},
    {type: 200},
    null,
  ];
  expect(pictureNumbersOf(elements)).toEqual([1, 3]);
});

test.each([
  [{numInPage: 42}, 42],
  [{numInPage: 0}, null],
  [{numInPage: 1.5}, null],
  [{numInPage: '42'}, null],
  [undefined, null],
])('numberOf(%p) is %p', (element, expected) => {
  expect(numberOf(element)).toBe(expected);
});

test.each([
  ['/n.note', 0, {notePath: '/n.note', page: 0}],
  ['', 0, null],
  [undefined, 0, null],
  ['/n.note', -1, null],
  ['/n.note', 1.5, null],
  ['/n.note', '1', null],
])('notePageOf(%p, %p) is %p', (notePath, page, expected) => {
  expect(notePageOf(notePath, page)).toEqual(expected);
});

test('elementSummary tells an element by its uuid, type, number, page, picture rect and path and user data, never its content', () => {
  const rect = {left: 1, top: 2, right: 3, bottom: 4};
  const element = {
    uuid: 'u',
    type: 200,
    numInPage: 5,
    pageNum: 2,
    userData: 'x',
    picture: {picturePath: 'plugin/1.png', rect},
    stroke: {points: [1, 2]},
  };
  expect(elementSummary(element)).toEqual({uuid: 'u', type: 200, num: 5, page: 2, rect, path: 'plugin/1.png', userData: 'x'});
  expect(JSON.stringify(elementSummary(null))).toBe('{}');
  expect(JSON.stringify(elementSummary({picture: null}))).toBe('{}');
});
