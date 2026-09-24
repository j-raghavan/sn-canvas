/**
 * The link index (FR12/FR13): the canvas last open, and the links pending
 * until the note places a thumbnail and a lasso claims it, read back
 * defensively from its JSON.
 */
import {
  EMPTY_INDEX,
  MAX_PENDING,
  canvasesIn,
  claimPending,
  belongsToANote,
  withoutCanvas,
  lastCanvasFor,
  elementSummary,
  isFreeToAdopt,
  notePageOf,
  numberOf,
  parseCanvasIndex,
  pictureNumbersOf,
  serializeCanvasIndex,
  withCanvasShownWithoutANote,
  withLastCanvas,
  withPending,
  pendingOn,
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
  // One page each, so the cap is the only thing deciding: links on one page prune and refuse each
  // other, which is what is being kept apart from here.
  const links = Array.from({length: MAX_PENDING + 5}, (_, n) => pendingFor(`c-${n}`, [], {notePath: '/note.note', page: n}));
  const added = links.reduce(withPending, EMPTY_INDEX);
  expect(added.pending).toEqual(links.slice(-MAX_PENDING));
  expect(parseCanvasIndex(JSON.stringify({pending: links})).pending).toEqual(links.slice(-MAX_PENDING));
});

describe('pruning links the page can no longer account for (#47)', () => {
  test('a link whose thumbnail has gone from the page goes with it', () => {
    // c-a's thumbnail was placed as picture 42. It has since been deleted, so the page is back to
    // picture 5 alone and nothing lassoed there can ever be c-a's thumbnail.
    const withA = withPending(EMPTY_INDEX, pendingFor('c-a', [5]));
    expect(withPending(withA, pendingFor('c-b', [5])).pending).toEqual([pendingFor('c-b', [5])]);
  });

  test('a link whose thumbnail is still on the page stays', () => {
    const withA = withPending(EMPTY_INDEX, pendingFor('c-a', [5]));
    // The page now holds 5 and c-a's thumbnail, 42, so c-a is still waiting for a lasso.
    expect(withPending(withA, pendingFor('c-b', [5, 42])).pending).toEqual([
      pendingFor('c-a', [5]),
      pendingFor('c-b', [5, 42]),
    ]);
  });

  test('the links are matched to the pictures one each, so only the ones left over go', () => {
    // The state this came from: both of these were left when the page still numbered its pictures in
    // the hundreds, and every one of those pictures has gone. One picture is on the page now, and the
    // newer link is the one that could be about it, so the older is what is left over (#47).
    const two = withPending(withPending(EMPTY_INDEX, pendingFor('c-a', [138])), pendingFor('c-b', [138, 139]));
    expect(two.pending).toHaveLength(2);
    expect(withPending(two, pendingFor('c-c', [1])).pending).toEqual([pendingFor('c-b', [138, 139]), pendingFor('c-c', [1])]);
  });

  // A newer link can know FEWER pictures than an older one, because numInPage is positional and a
  // deletion renumbers what is left. Then the sets are not nested and taking the first free picture
  // for each link in turn can starve one that had somewhere else to go (#47).
  test('a link is kept whenever some picture could be its, even if a newer link wanted that one too', () => {
    // Saving cannot reach this pair, since adding c-new would prune c-old on the spot, but an index
    // written by an earlier build loads straight into it. Page holds 1, 2 and 3: c-old can only be
    // about 1, c-new could be about 1 or 2.
    const two = parseCanvasIndex(JSON.stringify({pending: [pendingFor('c-old', [2, 3]), pendingFor('c-new', [3])]}));
    expect(two.pending).toHaveLength(2);
    // c-new must take 2 and leave 1 for c-old, rather than taking 1 and starving it.
    expect(withPending(two, pendingFor('c-next', [1, 2, 3])).pending.map(link => link.canvasId)).toEqual([
      'c-old',
      'c-new',
      'c-next',
    ]);
  });

  test('a page with no pictures on it keeps no links, so the one being left is the only one there', () => {
    const withA = withPending(EMPTY_INDEX, pendingFor('c-a', [5]));
    // Nothing on the page can ever be c-a's thumbnail, so c-a goes and c-b is left alone with it.
    expect(withPending(withA, pendingFor('c-b')).pending).toEqual([pendingFor('c-b')]);
  });

  test('links waiting on other pages and other notes are left alone', () => {
    const elsewhere = {notePath: '/other.note', page: 0};
    const otherPage = {notePath: '/note.note', page: 3};
    const index = [pendingFor('c-a', [5]), pendingFor('c-b', [5], elsewhere), pendingFor('c-c', [5], otherPage)].reduce(
      withPending,
      EMPTY_INDEX,
    );
    // The read is of PAGE, and takes out only PAGE's spent link.
    expect(withPending(index, pendingFor('c-d', [5])).pending).toEqual([
      pendingFor('c-b', [5], elsewhere),
      pendingFor('c-c', [5], otherPage),
      pendingFor('c-d', [5]),
    ]);
  });

  // Every other test here reads and writes page 0, so `=== at.page` and `<= at.page` are the same
  // thing to them and a save on a later page could prune an earlier page's live links unnoticed.
  test('a save on a later page leaves the links waiting on earlier pages of the same note alone', () => {
    const page1 = {notePath: '/note.note', page: 1};
    const page2 = {notePath: '/note.note', page: 2};
    const waiting = withPending(EMPTY_INDEX, pendingFor('c-a', [5], page1));
    expect(pendingOn(waiting, page2)).toEqual([]);
    // Page 2's read says nothing about page 1, however well its numbers line up with page 1's link.
    expect(withPending(waiting, pendingFor('c-b', [5], page2)).pending).toEqual([
      pendingFor('c-a', [5], page1),
      pendingFor('c-b', [5], page2),
    ]);
  });

  // The matching may move a link already placed, and has to leave the board as it found it when that
  // move fails. Recording the move before finding out whether it works keeps a link that has nowhere
  // to go. Found by trying every arrangement of three links over pictures 1 to 3: this is the only
  // one that tells the two apart.
  test('a link with nowhere left to go is not kept by a move that failed', () => {
    // c-a and c-c can only be about picture 1; c-b could be about any of the three. One of c-a and
    // c-c gets picture 1 and the other has nowhere to go.
    const three = parseCanvasIndex(
      JSON.stringify({pending: [pendingFor('c-a', [2, 3]), pendingFor('c-b', []), pendingFor('c-c', [2, 3])]}),
    );
    expect(three.pending).toHaveLength(3);
    expect(withPending(three, pendingFor('c-d', [1, 2, 3])).pending.map(link => link.canvasId)).toEqual([
      'c-b',
      'c-c',
      'c-d',
    ]);
  });

  test('pendingOn is the links waiting on one page, oldest first', () => {
    // c-a knew only picture 1, and c-c's read found 2 as well, so c-a's thumbnail is still there.
    const index = [pendingFor('c-a', [1]), pendingFor('c-b', [9], {notePath: '/other.note', page: 0}), pendingFor('c-c', [1, 2])].reduce(
      withPending,
      EMPTY_INDEX,
    );
    expect(pendingOn(index, PAGE).map(link => link.canvasId)).toEqual(['c-a', 'c-c']);
    expect(pendingOn(EMPTY_INDEX, PAGE)).toEqual([]);
  });
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

describe("a note's canvases (#30)", () => {
  test('every canvas shown in a note is kept, the most recent first, once each', () => {
    const shown = ['c-1', 'c-2', 'c-1'].reduce((index, id) => withLastCanvas(index, id, '/a.note'), EMPTY_INDEX);
    expect(canvasesIn(shown, '/a.note')).toEqual(['c-1', 'c-2']);
    expect(canvasesIn(shown, '/b.note')).toEqual([]);
    expect(canvasesIn(shown, null)).toEqual([]);
    // With no note to go by, only the plain last canvas is recorded.
    expect(withLastCanvas(shown, 'c-3', null).canvasesByNote).toEqual(shown.canvasesByNote);
  });

  test('an index from before they were kept finds them in what it has: the canvas reopened, then the pending links', () => {
    const pending = [
      {notePath: '/a.note', page: 0, canvasId: 'c-old', knownPictureNumbers: []},
      {notePath: '/a.note', page: 0, canvasId: 'c-new', knownPictureNumbers: [1]},
      {notePath: '/b.note', page: 0, canvasId: 'c-b', knownPictureNumbers: []},
    ];
    const upgraded = parseCanvasIndex(JSON.stringify({lastByNote: {'/a.note': 'c-old'}, pending}));
    expect(upgraded.canvasesByNote).toEqual({'/a.note': ['c-old', 'c-new'], '/b.note': ['c-b']});
  });

  test('saved lists are read back whole, and anything damaged in them is dropped', () => {
    const read = (saved: unknown) => parseCanvasIndex(JSON.stringify({canvasesByNote: saved, pending: []})).canvasesByNote;
    expect(read({'/a.note': ['c-1', 'not a canvas', 7], '': ['c-2'], '/b.note': 'c-3'})).toEqual({'/a.note': ['c-1']});
    expect(read(['c-1'])).toEqual({});
    expect(read(null)).toEqual({});
  });
});

describe('which canvases a note has claimed (#46)', () => {
  test('a canvas stays claimed after the note moves off it, which is what keeps it from another note', () => {
    const shown = withLastCanvas(EMPTY_INDEX, 'default', '/a.note');
    expect(belongsToANote(shown, 'default')).toBe(true);
    // Note A presses New canvas: it reopens c-1 now, but default.json still holds what was drawn on it.
    const moved = withLastCanvas(shown, 'c-1', '/a.note');
    expect(moved.lastByNote['/a.note']).toBe('c-1');
    expect(belongsToANote(moved, 'default')).toBe(true);
    expect(belongsToANote(moved, 'c-1')).toBe(true);
    expect(belongsToANote(moved, 'c-9')).toBe(false);
  });

  // Both records are read because a damaged saved file can have them disagree: a list of canvases that
  // reads back empty must not make the canvas the note reopens look like nobody's.
  test('a note whose saved list of canvases was damaged still holds the canvas it reopens', () => {
    const damaged = parseCanvasIndex(
      JSON.stringify({lastByNote: {'/a.note': 'default'}, canvasesByNote: {'/a.note': [7]}, pending: []}),
    );
    expect(damaged.canvasesByNote).toEqual({'/a.note': []});
    expect(belongsToANote(damaged, 'default')).toBe(true);
  });

  test('nothing is claimed in an empty index, so the first note to ask gets the scratch canvas', () => {
    expect(belongsToANote(EMPTY_INDEX, 'default')).toBe(false);
  });

  test('a canvas shown with no note to go by is claimed by nobody', () => {
    expect(belongsToANote(withLastCanvas(EMPTY_INDEX, 'c-1', null), 'c-1')).toBe(false);
  });

  // #49: belonging to no note and being free for a note to take are different questions. A canvas
  // drawn when nothing knew which note was open belongs to nobody and stays nobody's, or the next
  // note to ask is shown a drawing it never made and loses it the moment it saves.
  test('a canvas nobody owns is not free for a note to take', () => {
    const nobodys = withCanvasShownWithoutANote(EMPTY_INDEX, 'c-1');
    expect(belongsToANote(nobodys, 'c-1')).toBe(false);
    expect(isFreeToAdopt(nobodys, 'c-1')).toBe(false);
    expect(lastCanvasFor(nobodys, '/other.note')).toBeNull();
  });

  // The upgrade case, and the reason this is written down rather than worked out when it is read.
  // An index from a build before #49 holds a last canvas and no note records at all, which is what a
  // canvas nobody owns also looks like. That one has to stay adoptable or the drawing in it is
  // orphaned; it is told apart by the older build having had nowhere to write the canvas down.
  test("an older build's last canvas is still adopted by the first note to ask", () => {
    const upgraded = parseCanvasIndex(JSON.stringify({lastCanvasId: 'default', lastByNote: {}, canvasesByNote: {}}));
    expect(upgraded.shownWithoutANote).toEqual([]);
    expect(isFreeToAdopt(upgraded, 'default')).toBe(true);
    expect(lastCanvasFor(upgraded, '/note.note')).toBe('default');
  });

  // Every open and every switch writes this, not once a session, so without the dedupe a sitting
  // that switches between two canvases with no note known adds an entry per switch, for good.
  test('a canvas shown without a note twice is written down once', () => {
    const once = withCanvasShownWithoutANote(EMPTY_INDEX, 'default');
    expect(withCanvasShownWithoutANote(once, 'default').shownWithoutANote).toEqual(['default']);
  });

  test('a note taking a canvas nobody owned makes it that note s, and no longer nobody s', () => {
    const nobodys = withCanvasShownWithoutANote(EMPTY_INDEX, 'c-1');
    const claimed = withLastCanvas(nobodys, 'c-1', '/note.note');
    expect(claimed.shownWithoutANote).toEqual([]);
    expect(belongsToANote(claimed, 'c-1')).toBe(true);
  });

  test('a retired id belongs to nobody either, so the next note may have it', () => {
    const nobodys = withCanvasShownWithoutANote(EMPTY_INDEX, 'default');
    expect(withoutCanvas(nobodys, 'default').shownWithoutANote).toEqual([]);
    expect(isFreeToAdopt(withoutCanvas(nobodys, 'default'), 'default')).toBe(true);
  });

  test('what nobody owns survives a save and load, and rubbish in it is dropped', () => {
    const saved = serializeCanvasIndex(withCanvasShownWithoutANote(EMPTY_INDEX, 'c-1'));
    expect(parseCanvasIndex(saved).shownWithoutANote).toEqual(['c-1']);
    expect(parseCanvasIndex(JSON.stringify({shownWithoutANote: ['c-1', 'not a canvas', 7, null]})).shownWithoutANote).toEqual(['c-1']);
    expect(parseCanvasIndex(JSON.stringify({shownWithoutANote: 'nope'})).shownWithoutANote).toEqual([]);
  });

  test('retiring an id takes it out of every note that showed it, and off the plain last canvas', () => {
    const shown = ['default', 'c-1'].reduce((index, id) => withLastCanvas(index, id, '/a.note'), EMPTY_INDEX);
    const alsoB = withLastCanvas(shown, 'default', '/b.note');
    // Save to Note gives the scratch canvas an id of its own and deletes default.json, so the id means nothing now.
    const retired = withoutCanvas(alsoB, 'default');
    expect(belongsToANote(retired, 'default')).toBe(false);
    expect(canvasesIn(retired, '/a.note')).toEqual(['c-1']);
    expect(canvasesIn(retired, '/b.note')).toEqual([]);
    expect(retired.lastByNote['/b.note']).toBeUndefined();
    expect(retired.lastByNote['/a.note']).toBe('c-1');
    expect(retired.lastCanvasId).toBeNull();
  });

  // The canvas last open is adopted only by a note with some claim to it. An upgraded index can hold a note's
  // canvases without holding what it reopens, and reading only the latter would give that canvas away (#46).
  test('an upgraded index does not hand the canvas last open to a note with no claim on it', () => {
    const pending = [{notePath: '/a.note', page: 0, canvasId: 'c-old', knownPictureNumbers: []}];
    const upgraded = parseCanvasIndex(JSON.stringify({lastCanvasId: 'c-old', pending}));
    expect(upgraded.lastByNote).toEqual({});
    expect(upgraded.canvasesByNote).toEqual({'/a.note': ['c-old']});
    expect(lastCanvasFor(upgraded, '/b.note')).toBeNull();
    // The note whose canvas it is still reopens it.
    expect(lastCanvasFor(upgraded, '/a.note')).toBe('c-old');
  });

  test('retiring an id nothing holds leaves the index as it was', () => {
    const shown = withLastCanvas(EMPTY_INDEX, 'c-1', '/a.note');
    expect(withoutCanvas(shown, 'c-9')).toEqual(shown);
  });
});
