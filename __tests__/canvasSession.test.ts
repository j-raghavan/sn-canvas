/**
 * The canvas session against in-memory ports: which canvas each button press
 * opens, the save-before-switch rule, "Save to Note" linking (FR12/FR13) and
 * its failure handling, the link index that brings a thumbnail's canvas back,
 * the double-tap guard, new canvases, and close.
 */
import {createCanvasSession} from '../src/application/canvasSession';
import {createFakeBadge, createFakeHost, createFakeStore, createRecordingLogger, notePicture} from './helpers/fakePorts';

const SCRATCH = '/plugin/Canvas/default.json';
const INDEX = '/plugin/Canvas/links.json';
// Canvas has opened since it was installed; a test of the first open since install leaves it out.
const MARKER = '/plugin/canvas-opened';
const canvasFile = (id: string) => `/plugin/Canvas/${id}.json`;
const thumbnail = (id: string) => `/plugin/Canvas/thumbnails/${id}.png`;
const lassoedThumbnail = (id: string) => ({picture: {picturePath: thumbnail(id)}});
// What this firmware hands back for a lassoed thumbnail: a copy with a fresh uuid, no page, its number in the
// page, any userData, and a temporary copy of the picture.
const lassoedPicture = (num: number, userData?: string) => ({
  uuid: 'copy',
  type: 200,
  numInPage: num,
  pageNum: -1,
  userData,
  picture: {picturePath: 'plugin/1789355421616.png'},
});
const indexWith = (index: {lastCanvasId?: string}) => JSON.stringify({lastCanvasId: null, pending: [], ...index});
const savedIndex = (store: {files: Map<string, string>}) => JSON.parse(String(store.files.get(INDEX)));

const setup = (files: Record<string, string> = {}, {installedJustNow = false} = {}) => {
  const store = createFakeStore(installedJustNow ? files : {[MARKER]: 'opened', ...files});
  const host = createFakeHost();
  const badge = createFakeBadge();
  const logger = createRecordingLogger();
  let minted = 0;
  const session = createCanvasSession({
    store,
    host,
    badge,
    logger,
    newCanvasId: () => {
      minted += 1;
      return `c-${minted}`;
    },
  });
  return {store, host, badge, logger, session};
};

describe('open', () => {
  test('shows the scratch canvas before any button press is known', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(store.shown).toBe('scratch');
    expect(session.currentCanvasId()).toBe('default');
  });

  test('the sidebar button shows the scratch canvas when it is the only one', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(500);
    expect(store.shown).toBe('scratch');
  });

  test('Open Canvas on a lassoed Canvas thumbnail shows its linked canvas', async () => {
    const {store, host, logger, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [{}, lassoedThumbnail('c-9')];
    await session.open(501);
    expect(store.shown).toBe('nine');
    expect(session.currentCanvasId()).toBe('c-9');
    expect(logger.lines).toContain(
      `log [SNCANVAS][LINK] lassoed=2 elements=[{},{"path":"${thumbnail('c-9')}"}] canvas=c-9`,
    );
  });

  test('Open Canvas on any other picture, with no other canvas saved, falls back to the scratch canvas', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    host.lassoed = [{picture: {picturePath: '/note/images/photo.png'}}];
    await session.open(501);
    expect(store.shown).toBe('scratch');
    expect(session.currentCanvasId()).toBe('default');
  });

  test("every open hands the canvas the note's pen, to set back as it closes", async () => {
    const {store, host, logger, session} = setup();
    await session.open(null);
    expect(store.notePen).toBeNull();
    host.pen = {type: 14, width: 700, color: 0};
    await session.open(500);
    expect(store.notePen).toEqual({type: 14, width: 700, color: 0});
    store.failing.add('rememberNotePen');
    await session.open(500);
    expect(logger.lines).toContain(
      `warn [SNCANVAS][PEN] the note's pen {"type":14,"width":700,"color":0} is not one the canvas can set back`,
    );
  });

  test('switching canvases saves the one being left first', async () => {
    const {store, host, session} = setup({
      [SCRATCH]: 'scratch',
      [canvasFile('c-9')]: 'nine',
      [INDEX]: indexWith({lastCanvasId: 'default'}),
    });
    await session.open(null);
    store.shown = 'scratch, edited';
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    expect(store.files.get(SCRATCH)).toBe('scratch, edited');
    expect(store.shown).toBe('nine');
  });

  test('a press for the canvas already shown neither saves nor reloads it', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.shown = 'unsaved edit';
    await session.open(500);
    expect(store.shown).toBe('unsaved edit');
    expect(store.files.get(SCRATCH)).toBe('scratch');
  });

  test('without a plugin directory nothing loads, and the next press tries again', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.dir = null;
    await session.open(null);
    expect(store.shown).toBe('');
    expect(logger.lines).toContain('warn [SNCANVAS] no plugin directory; canvas not loaded or saved');
    host.dir = '/plugin';
    await session.open(500);
    expect(store.shown).toBe('scratch');
  });
});

describe('each note has its own canvas', () => {
  const A = {notePath: '/Note/a.note', page: 0};
  const B = {notePath: '/Note/b.note', page: 0};

  test("opening Canvas in another note shows that note's canvas, not the one last open elsewhere", async () => {
    const {store, host, session} = setup({[SCRATCH]: 'drawn in A'});
    host.page = A;
    await session.open(500);
    expect(store.shown).toBe('drawn in A');
    // The same install, a different note: the bug was that this showed A's canvas.
    host.page = B;
    await session.open(500);
    expect(store.shown).toBe('');
    expect(session.currentCanvasId()).toBe('c-1');
  });

  test('going back to a note reopens the canvas it was left showing', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'drawn in A'});
    host.page = A;
    await session.open(500);
    host.page = B;
    await session.open(500);
    store.shown = 'drawn in B';
    host.page = A;
    await session.open(500);
    expect(store.shown).toBe('drawn in A');
    host.page = B;
    await session.open(500);
    expect(store.shown).toBe('drawn in B');
  });

  test('the scratch canvas goes to the first note that asks, and every other note gets a canvas of its own', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.page = A;
    await session.open(500);
    expect(session.currentCanvasId()).toBe('default');
    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).toBe('c-1');
    expect(logger.lines).toContain('log [SNCANVAS] no canvas for this note yet: a new one');
    expect(savedIndex(store).lastByNote).toEqual({[A.notePath]: 'default', [B.notePath]: 'c-1'});
  });

  // #46, all the way to the step that destroyed the drawing: the note moved off the scratch canvas, but
  // default.json still holds what was drawn on it, and the next note used to be handed it and save over it.
  test('a note that left the scratch canvas behind keeps it, and the note after it cannot destroy the drawing', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    host.page = A;
    await session.open(500);
    store.shown = "note A's drawing";
    await session.newCanvas();
    expect(store.files.get(SCRATCH)).toBe("note A's drawing");

    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).not.toBe('default');
    expect(store.shown).toBe('');
    // The step that used to take A's drawing for B and delete the file it was in.
    expect(await session.saveToNote()).toBe('inserted');
    expect(store.files.get(SCRATCH)).toBe("note A's drawing");

    // And A can still get to it, not merely see it listed.
    host.page = A;
    expect((await session.canvasesHere()).map(canvas => canvas.canvasId)).toContain('default');
    await session.switchTo('default');
    expect(store.shown).toBe("note A's drawing");
  });

  // An index holding only a note's canvases is not a build from before the index, and its canvases are
  // someone's. Reading it as old would hand the newest one to whichever note asked next (#46).
  test('an index that names only the canvases a note has shown does not give them away', async () => {
    const {store, host, session} = setup({
      [canvasFile('c-kept')]: 'work in a note',
      [INDEX]: JSON.stringify({canvasesByNote: {[A.notePath]: ['c-kept']}, lastByNote: {}, lastCanvasId: null, pending: []}),
    });
    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).not.toBe('c-kept');
    expect(store.shown).not.toBe('work in a note');
  });

  // Giving up the id rests on the file having gone. If it is still there it still holds the drawing, and
  // handing it to the next note is exactly the loss this is about (#46).
  test('a scratch canvas whose file would not delete stays the note it was saved from', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.page = A;
    await session.open(500);
    store.failing.add('remove');
    await session.saveToNote();
    expect(store.files.get(SCRATCH)).toBe('scratch');
    expect(logger.lines).toContain("warn [SNCANVAS][LINK] default is still on disk, so it stays this note's");

    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).not.toBe('default');
    expect(store.shown).not.toBe('scratch');
  });

  // The id is given up only when the save landed. A save that did not leaves the drawing back in
  // default.json, so giving the id away would hand the next note that drawing (#46).
  test('a save to note that never landed leaves the scratch canvas with the note that drew on it', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    host.page = A;
    await session.open(500);
    host.insertSucceeds = false;
    await session.saveToNote();
    expect(session.currentCanvasId()).toBe('default');
    expect(store.files.get(SCRATCH)).toBe('scratch');

    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).not.toBe('default');
    expect(store.shown).toBe('');
  });

  // Only a save from the scratch canvas retires it. One note saving a canvas of its own says nothing
  // about whose the scratch canvas is.
  test('saving a canvas of its own into a note leaves the scratch canvas where it belongs', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'drawn in A'});
    host.page = A;
    await session.open(500);
    expect(session.currentCanvasId()).toBe('default');

    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).toBe('c-1');
    await session.saveToNote();

    // A third note is still not offered the scratch canvas, which is still A's.
    host.page = {notePath: '/c.note', page: 0};
    await session.open(500);
    expect(session.currentCanvasId()).not.toBe('default');
    expect(store.files.get(SCRATCH)).toBe('drawn in A');
  });

  test('the scratch canvas is free again once Save to Note gives it an id of its own', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    host.page = A;
    await session.open(500);
    await session.saveToNote();
    // Its file is gone: the drawing lives on under the new id, so the id 'default' means nothing now.
    expect(store.files.has(SCRATCH)).toBe(false);
    host.page = B;
    await session.open(500);
    expect(session.currentCanvasId()).toBe('default');
  });

  test('a canvas an earlier build left as the last one goes to the first note that asks, and to no other', async () => {
    const {store, host, session} = setup({
      [canvasFile('c-old')]: 'my work',
      [INDEX]: indexWith({lastCanvasId: 'c-old'}),
    });
    host.page = A;
    await session.open(500);
    // Upgrading does not strand the canvas that was open: the first note to ask gets it.
    expect(store.shown).toBe('my work');
    host.page = B;
    await session.open(500);
    expect(store.shown).not.toBe('my work');
  });

  test('Open Canvas on a thumbnail still opens its canvas, whichever note it is in', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.page = B;
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    expect(store.shown).toBe('nine');
    // ...and the note it was opened in now reopens it from the sidebar too.
    host.lassoed = [];
    await session.open(500);
    expect(store.shown).toBe('nine');
  });
});

describe('the first open after an install', () => {
  test('starts a new, empty canvas; the canvases saved before stay, and later opens reopen the last one', async () => {
    const first = setup({[canvasFile('c-9')]: 'nine', [INDEX]: indexWith({lastCanvasId: 'c-9'})}, {installedJustNow: true});
    await first.session.open(500);
    expect(first.store.shown).toBe('');
    expect(first.session.currentCanvasId()).toBe('c-1');
    expect(first.store.files.get(canvasFile('c-9'))).toBe('nine');
    expect(first.store.files.get(MARKER)).toBe('opened');
    expect(first.logger.lines).toContain('log [SNCANVAS] first open since install: a new canvas');
    await first.session.close();
    const later = setup(Object.fromEntries(first.store.files), {installedJustNow: true});
    await later.session.open(500);
    expect(later.session.currentCanvasId()).toBe('c-1');
  });

  test("a note with a canvas of its own reopens it: an update does not take a note's work away", async () => {
    const index = JSON.stringify({lastByNote: {'/note.note': 'c-9'}, lastCanvasId: 'c-9', pending: []});
    const {store, session, logger} = setup({[canvasFile('c-9')]: 'nine', [INDEX]: index}, {installedJustNow: true});
    await session.open(500);
    expect(store.shown).toBe('nine');
    expect(store.files.get(MARKER)).toBe('opened');
    expect(logger.lines).not.toContain('log [SNCANVAS] first open since install: a new canvas');
  });

  test('Open Canvas as the first open still opens its thumbnail canvas, and a sidebar press after it stays there', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'}, {installedJustNow: true});
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    expect(store.shown).toBe('nine');
    await session.open(500);
    expect(session.currentCanvasId()).toBe('c-9');
  });
});

describe('where canvases live', () => {
  const SHARED = '/storage/emulated/0/MyStyle/SnCanvas';

  test('with file write access, canvases live in MyStyle, and those an earlier build kept in the plugin folder move there', async () => {
    const {store, host, logger, session} = setup({
      [canvasFile('c-9')]: 'nine',
      [thumbnail('c-9')]: 'png:nine',
      [INDEX]: indexWith({lastCanvasId: 'c-9'}),
    });
    host.fileWrite = true;
    await session.open(500);
    expect(store.shown).toBe('nine');
    expect(store.files.get(`${SHARED}/c-9.json`)).toBe('nine');
    expect(store.files.get(`${SHARED}/thumbnails/c-9.png`)).toBe('png:nine');
    expect([...store.files.keys()].some(path => path.startsWith('/plugin/Canvas/'))).toBe(false);
    expect(logger.lines).toContain(`log [SNCANVAS] moved 3 files from the plugin folder to ${SHARED}`);
    await session.saveToNote();
    expect(host.inserted).toEqual([`${SHARED}/thumbnails/c-9.png`]);
  });

  test('canvases kept in MyStyle under the old name, SnSuperCanvas, move to the new folder', async () => {
    const OLD = '/storage/emulated/0/MyStyle/SnSuperCanvas';
    const {store, host, logger, session} = setup({[`${OLD}/c-9.json`]: 'nine', [`${OLD}/links.json`]: indexWith({lastCanvasId: 'c-9'})});
    host.fileWrite = true;
    await session.open(500);
    expect(store.shown).toBe('nine');
    expect(logger.lines).toContain(`log [SNCANVAS] moved 2 files from ${OLD} to ${SHARED}`);
  });

  test('without it, canvases stay in the plugin folder, with a warning that uninstalling deletes them', async () => {
    const {store, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(store.shown).toBe('scratch');
    expect(logger.lines).toContain(
      'warn [SNCANVAS] no file write access; canvases stay in the plugin folder, which uninstalling Canvas deletes',
    );
  });

  test('file access is asked for once, on the first open, and with nothing to move nothing is said', async () => {
    const {store, host, logger, session} = setup({[`${SHARED}/default.json`]: 'shared scratch'});
    host.fileWrite = true;
    await session.open(null);
    await session.open(500);
    expect(store.shown).toBe('shared scratch');
    expect(host.accessRequests).toBe(1);
    expect(logger.lines.some(line => line.includes('moved'))).toBe(false);
  });

  test('with write access, canvases live in MyStyle even when the host has no plugin folder', async () => {
    const {store, host, session} = setup({[`${SHARED}/default.json`]: 'shared scratch'});
    host.fileWrite = true;
    host.dir = null;
    await session.open(null);
    expect(store.shown).toBe('shared scratch');
  });
});

describe('links back to a canvas', () => {
  test('Save to Note leaves a link pending, and every Open Canvas on that thumbnail claims it again', async () => {
    const first = setup({[SCRATCH]: 'drawing'});
    first.host.elements = [notePicture(5)];
    await first.session.open(null);
    await first.session.saveToNote();
    await first.session.close();
    const link = {notePath: '/note.note', page: 0, canvasId: 'c-1', knownPictureNumbers: [5]};
    expect(savedIndex(first.store).pending).toEqual([link]);
    // A later session, with another canvas saved since: the link, not recency, decides.
    const later = setup({...Object.fromEntries(first.store.files), [canvasFile('c-later')]: 'other'});
    later.host.lassoed = [lassoedPicture(42)];
    await later.session.open(501);
    expect(later.store.shown).toBe('drawing');
    // The link stays: nothing is written into the note to mark the thumbnail, so the next lasso claims it again.
    expect(savedIndex(later.store).pending).toEqual([link]);
    const again = setup(Object.fromEntries(later.store.files));
    again.host.lassoed = [lassoedPicture(42)];
    await again.session.open(501);
    expect(again.store.shown).toBe('drawing');
  });

  test('a tag an earlier build wrote still names its canvas', async () => {
    const {store, host, session} = setup({[canvasFile('c-1')]: 'drawing'});
    host.page = null;
    host.lassoed = [lassoedPicture(9, 'sncanvas:c-1')];
    await session.open(501);
    expect(store.shown).toBe('drawing');
  });

  // Every save left a link and nothing ever took one away, so links.json filled to its cap and
  // started dropping the oldest, which are the ones most likely still to have a thumbnail (#47).
  test('a link whose thumbnail is no longer on the page goes on the next save', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    host.elements = [notePicture(5)];
    await session.saveToNote();
    // The thumbnail is deleted before it is ever lassoed, so the page is back to picture 5 alone.
    await session.newCanvas();
    store.shown = 'second drawing';
    await session.saveToNote();
    await session.close();
    expect(
      savedIndex(store).pending.map((link: {canvasId: string; knownPictureNumbers: number[]}) => [
        link.canvasId,
        link.knownPictureNumbers,
      ]),
    ).toEqual([['c-2', [5]]]);
  });

  // A read that finds nothing is taken twice before it is believed, because this firmware returns no
  // pictures for pages that have them. Confirmed, it means the page really is empty, so the links
  // waiting there are waiting for thumbnails that have gone, and the new one is alone (#47).
  test('a page read that finds nothing is taken twice, and a page really empty keeps no old links', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    host.elements = [notePicture(5)];
    await session.saveToNote();
    const savesBefore = host.noteSaves;

    await session.newCanvas();
    store.shown = 'second drawing';
    host.elements = [];
    await session.saveToNote();
    await session.close();

    // Asked twice, because links were waiting on that page; each read saves the note first.
    expect(host.noteSaves).toBeGreaterThan(savesBefore + 1);
    expect(logger.lines).toContain('log [SNCANVAS][LINK] page=0 read no pictures with links waiting; read again and found 0');
    expect(savedIndex(store).pending.map((link: {canvasId: string}) => link.canvasId)).toEqual(['c-2']);
  });

  // On a page nothing is waiting on, a read that finds nothing is the ordinary first save onto a
  // blank page, and the link is the only one there, so it can only ever answer for its own thumbnail.
  test('a page read that finds nothing still leaves the first link on a blank page', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    host.elements = [];
    await session.saveToNote();
    await session.close();
    expect(savedIndex(store).pending.map((link: {canvasId: string}) => link.canvasId)).toEqual(['c-1']);
  });

  // The suppression counts links waiting on the page, not thumbnails on it. When the waiting links
  // are stale, which is the state pruning exists to clean up, an empty read is the truth and the
  // link would have been right. main gets this sequence entirely right (#47).
  test('a canvas saved after the page was emptied still opens from its own thumbnail', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    host.elements = [notePicture(1)];
    await session.saveToNote();

    // Everything on the page is deleted, c-1's thumbnail included, so the page really is empty and
    // the read that finds nothing is telling the truth.
    host.elements = [];
    await session.newCanvas();
    store.shown = 'second drawing';
    await session.saveToNote();
    // c-2's thumbnail lands as picture 3.
    host.elements = [notePicture(3)];

    await session.newCanvas();
    store.shown = 'third drawing';
    await session.saveToNote();

    host.lassoed = [lassoedPicture(3)];
    await session.open(501);
    expect(store.shown).toBe('second drawing');
  });

  test('two canvases saved to one page each keep a thumbnail that opens its own canvas (#30)', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    await session.saveToNote();
    // The note places the first thumbnail; it is in the note's file only once the note is saved.
    host.unsaved = [notePicture(1)];
    await session.newCanvas();
    store.shown = 'second drawing';
    await session.saveToNote();
    host.unsaved = [...host.unsaved, notePicture(2)];
    await session.close();
    const pending = savedIndex(store).pending.map((link: {canvasId: string; knownPictureNumbers: number[]}) => [
      link.canvasId,
      link.knownPictureNumbers,
    ]);
    expect(pending).toEqual([
      ['c-1', []],
      ['c-2', [1]],
    ]);
    host.lassoed = [lassoedPicture(1)];
    await session.open(501);
    expect(store.shown).toBe('first drawing');
    host.lassoed = [lassoedPicture(2)];
    await session.open(501);
    expect(store.shown).toBe('second drawing');
  });

  test('a canvas saved again puts an up to date thumbnail beside the one already on the page', async () => {
    const {store, host, logger, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    // The note has this canvas's thumbnail on the page already; Canvas leaves it alone and adds the new one.
    host.elements = [notePicture(3), notePicture(7, 'sncanvas:c-1')];
    store.shown = 'drawing, edited';
    expect(await session.saveToNote()).toBe('inserted');
    expect(store.files.get(thumbnail('c-1'))).toBe('png:drawing, edited');
    expect(host.inserted).toEqual([thumbnail('c-1')]);
    // What the note holds is the user's to keep or delete: nothing on the page is modified or removed.
    expect(host.elements).toEqual([notePicture(3), notePicture(7, 'sncanvas:c-1')]);
    expect(logger.lines).toContain('log [SNCANVAS][LINK] inserted thumbnail for canvas=c-1');
    // A link waits for the new thumbnail, knowing the pictures that were there before it.
    await session.close();
    expect(savedIndex(store).pending).toEqual([
      {notePath: '/note.note', page: 0, canvasId: 'c-1', knownPictureNumbers: [3, 7]},
    ]);
  });

  test('the note is saved before its page is read, so a thumbnail placed since the last save is seen', async () => {
    const {host, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    const order: string[] = [];
    host.saveNote = async () => {
      order.push('saveNote');
      return true;
    };
    host.pageElements = async () => {
      order.push('pageElements');
      return [];
    };
    await session.saveToNote();
    expect(order).toEqual(['saveNote', 'pageElements']);
  });

  test('a thumbnail of another canvas on the page is left alone; this canvas gets its own', async () => {
    const {host, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    host.elements = [notePicture(7, 'sncanvas:c-other')];
    expect(await session.saveToNote()).toBe('inserted');
    expect(host.inserted).toEqual([thumbnail('c-1')]);
  });

  test('the page is read before the thumbnail goes in, so the link knows which picture is not it', async () => {
    const {host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    const reads: string[] = [];
    host.pageElements = async () => {
      reads.push(`read ${host.inserted.length} inserted`);
      return [];
    };
    expect(await session.saveToNote()).toBe('inserted');
    await session.close();
    // Once, with nothing inserted yet: a page read afterwards could count the new thumbnail among those before it.
    expect(reads).toEqual(['read 0 inserted']);
  });

  test('two thumbnails saved on one page before either was opened each reopen their own canvas', async () => {
    const first = setup({[SCRATCH]: 'A'});
    await first.session.open(null);
    await first.session.saveToNote();
    await first.session.newCanvas();
    first.store.shown = 'B';
    // By the second save, the note has placed the first thumbnail, as picture 42.
    first.host.elements = [notePicture(42)];
    await first.session.saveToNote();
    await first.session.close();
    const files = Object.fromEntries(first.store.files);
    const reopen = async (num: number): Promise<string> => {
      const later = setup(files);
      later.host.lassoed = [lassoedPicture(num)];
      await later.session.open(501);
      return later.store.shown;
    };
    expect(await reopen(43)).toBe('B');
    expect(await reopen(42)).toBe('A');
  });

  test('a pending link waits while a picture is lassoed on another page, or with no page known', async () => {
    const first = setup({[SCRATCH]: 'drawing'});
    await first.session.open(null);
    await first.session.saveToNote();
    await first.session.close();
    const files = {...Object.fromEntries(first.store.files), [canvasFile('c-later')]: 'other'};
    const elsewhere = setup(files);
    elsewhere.host.page = {notePath: '/note.note', page: 4};
    elsewhere.host.lassoed = [lassoedPicture(42)];
    await elsewhere.session.open(501);
    expect(elsewhere.store.shown).toBe('other');
    expect(savedIndex(elsewhere.store).pending).toHaveLength(1);
    const unknown = setup(files);
    unknown.host.page = null;
    unknown.host.lassoed = [lassoedPicture(42)];
    await unknown.session.open(501);
    expect(savedIndex(unknown.store).pending).toHaveLength(1);
  });

  test('with no note page at Save to Note, the thumbnail is inserted but no link waits for it', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.page = null;
    await session.open(null);
    expect(await session.saveToNote()).toBe('inserted');
    await session.close();
    // With no note to go by, nothing is recorded against one either.
    expect(savedIndex(store)).toEqual({canvasesByNote: {}, lastByNote: {}, lastCanvasId: 'c-1', pending: []});
    expect(logger.lines).toContain(
      'warn [SNCANVAS][LINK] no note page; Open Canvas on this thumbnail will show the newest canvas',
    );
  });

  test('the sidebar reopens the canvas last open, in a later session too', async () => {
    const first = setup({[SCRATCH]: 'scratch', [canvasFile('c-9')]: 'nine'});
    first.host.lassoed = [lassoedThumbnail('c-9')];
    await first.session.open(501);
    await first.session.close();
    const later = setup({...Object.fromEntries(first.store.files), [canvasFile('c-later')]: 'other'});
    await later.session.open(500);
    expect(later.session.currentCanvasId()).toBe('c-9');
    expect(later.store.shown).toBe('nine');
  });

  test('with nothing recorded, the sidebar and an unlinked Open Canvas show the newest saved canvas, not the scratch one', async () => {
    // Canvases saved by a build without the index: the one saved last is the likeliest.
    const files = {[canvasFile('c-old')]: 'old', [canvasFile('c-new')]: 'new', [SCRATCH]: ''};
    const sidebar = setup(files);
    await sidebar.session.open(500);
    expect(sidebar.store.shown).toBe('new');
    const lasso = setup(files);
    lasso.host.lassoed = [lassoedPicture(7)];
    await lasso.session.open(501);
    expect(lasso.store.shown).toBe('new');
  });

  test('a damaged index is ignored, and written afresh', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch', [INDEX]: '{nope'});
    await session.open(500);
    expect(store.shown).toBe('scratch');
    expect(savedIndex(store)).toEqual({
      canvasesByNote: {'/note.note': ['default']},
      lastByNote: {'/note.note': 'default'},
      lastCanvasId: 'default',
      pending: [],
    });
  });
});

describe('saveToNote', () => {
  test('from the scratch canvas: moves it to a new linked canvas, inserts its thumbnail and keeps it open', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    await session.saveToNote();
    expect(store.files.get(canvasFile('c-1'))).toBe('scratch');
    expect(store.files.get(thumbnail('c-1'))).toBe('png:scratch');
    expect(host.inserted).toEqual([thumbnail('c-1')]);
    expect(store.files.has(SCRATCH)).toBe(false);
    expect(session.currentCanvasId()).toBe('c-1');
    expect(savedIndex(store).lastCanvasId).toBe('c-1');
  });

  test('from a linked canvas: re-links under the id it already has', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    await session.saveToNote();
    expect(host.inserted).toEqual([thumbnail('c-9')]);
    expect(store.files.get(canvasFile('c-9'))).toBe('nine');
    expect(session.currentCanvasId()).toBe('c-9');
  });

  test('a double tap inserts one thumbnail', async () => {
    const {host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    await Promise.all([session.saveToNote(), session.saveToNote()]);
    expect(host.inserted).toEqual([thumbnail('c-1')]);
    expect(logger.lines).toContain('log [SNCANVAS][LINK] save to note already running; tap ignored');
  });

  test('a later tap, after the first finished, links again', async () => {
    const {host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    await session.saveToNote();
    await session.saveToNote();
    expect(host.inserted).toEqual([thumbnail('c-1'), thumbnail('c-1')]);
  });

  test.each([
    ['saving the canvas under its new id', 'saveAs'],
    ['rendering the thumbnail', 'renderThumbnail'],
    ['inserting the image', 'insertImage'],
  ])('when %s fails, the scratch canvas stays as it was and no new files are left behind', async (_step, failing) => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    if (failing === 'insertImage') {
      host.insertSucceeds = false;
    } else {
      store.failing.add(failing as 'saveAs' | 'renderThumbnail');
    }
    await session.saveToNote();
    expect(session.currentCanvasId()).toBe('default');
    expect([...store.files.keys()].sort()).toEqual([INDEX, MARKER, SCRATCH].sort());
    expect(store.files.get(SCRATCH)).toBe('scratch');
    // And the view keeps the scratch canvas's file: a later save goes there.
    await session.close();
    expect(store.refused).toEqual([]);
    expect(host.inserted).toEqual([]);
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] save to note failed; canvas=default unchanged');
  });

  test('a failed re-link of a linked canvas keeps its files', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    host.insertSucceeds = false;
    await session.saveToNote();
    expect(store.files.get(canvasFile('c-9'))).toBe('nine');
    expect(session.currentCanvasId()).toBe('c-9');
  });

  test('without a plugin directory it does nothing', async () => {
    const {host, session} = setup();
    host.dir = null;
    await session.saveToNote();
    expect(host.inserted).toEqual([]);
  });
});

describe('newCanvas', () => {
  test('saves the canvas shown, then shows an empty new one, which the sidebar then reopens', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.shown = 'scratch, edited';
    await session.newCanvas();
    expect(store.files.get(SCRATCH)).toBe('scratch, edited');
    expect(store.shown).toBe('');
    expect(session.currentCanvasId()).toBe('c-1');
    expect(savedIndex(store).lastCanvasId).toBe('c-1');
  });

  test('without a plugin directory it does nothing', async () => {
    const {host, session} = setup();
    host.dir = null;
    await session.newCanvas();
    expect(session.currentCanvasId()).toBe('default');
  });
});

describe('clearCanvas', () => {
  test('keeps what was on the canvas in its own file and starts a fresh sheet (#44)', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.shown = 'a drawing';
    await session.clearCanvas();
    expect(store.files.get(SCRATCH)).toBe('a drawing');
    expect(store.shown).toBe('');
    expect(session.currentCanvasId()).toBe('c-1');
    expect(savedIndex(store).lastCanvasId).toBe('c-1');
  });

  test('the thumbnail still opens the drawing after it was cleared away (#44)', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    store.shown = 'a drawing';
    await session.clearCanvas();
    // The canvas on screen really is empty, and what was on it went to its own file rather than over it.
    expect(store.shown).toBe('');
    expect(store.files.get(canvasFile('c-9'))).toBe('a drawing');
    await session.newCanvas();
    // Open Canvas on the same thumbnail, back in the note, as the report has it.
    await session.open(501);
    expect(session.currentCanvasId()).toBe('c-9');
    expect(store.shown).toBe('a drawing');
  });

  test('the canvas cleared away is still a tap away in this note', async () => {
    const {store, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    await session.saveToNote();
    store.shown = 'first drawing, more of it';
    await session.clearCanvas();
    const listed = await session.canvasesHere();
    expect(listed.map(canvas => [canvas.canvasId, canvas.isShown])).toEqual([
      ['c-2', true],
      ['c-1', false],
    ]);
    await session.switchTo('c-1');
    expect(store.shown).toBe('first drawing, more of it');
  });

  // A view that came back without the canvas would refuse the save (#30), and the drawing would go
  // with nothing kept, which is exactly what clearing must not do.
  test('a view that is not holding the canvas is left alone rather than replaced', async () => {
    const {store, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.emptyView();
    await session.clearCanvas();
    expect(session.currentCanvasId()).toBe('default');
    expect(logger.lines).toContain('warn [SNCANVAS] the view is not holding canvas=default; it stays as it is');
  });

  test('without a plugin directory it does nothing', async () => {
    const {host, session} = setup();
    host.dir = null;
    await session.clearCanvas();
    expect(session.currentCanvasId()).toBe('default');
  });
});

describe('close', () => {
  test('saves the canvas, then closes the plugin view', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.shown = 'scratch, edited';
    await session.close();
    expect(store.files.get(SCRATCH)).toBe('scratch, edited');
    expect(host.closeCount).toBe(1);
  });

  test('after Save to Note, saves to the linked canvas, not the scratch one', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    await session.saveToNote();
    store.shown = 'linked, edited';
    await session.close();
    expect(store.files.get(canvasFile('c-1'))).toBe('linked, edited');
    expect(store.files.has(SCRATCH)).toBe(false);
  });

  test('before any canvas opened, closes without saving over one', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    await session.close();
    expect(store.files.get(SCRATCH)).toBe('scratch');
    expect(host.closeCount).toBe(1);
  });

  test('still closes when saving fails', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    store.failing.add('save');
    await session.close();
    expect(host.closeCount).toBe(1);
  });
});

test('an operation that throws is logged and does not block the ones after it', async () => {
  const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
  store.load = async () => {
    throw new Error('boom');
  };
  await session.open(null);
  await session.close();
  expect(logger.lines).toContain('error [SNCANVAS] Error: boom');
  expect(host.closeCount).toBe(1);
});

describe('links to notes', () => {
  test('picking a note gives a link to it, opened where it was last left', async () => {
    const {host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.pickedNote = '/storage/emulated/0/Note/plan.note';
    expect(await session.pickNoteLink()).toEqual({kind: 'note', target: '/storage/emulated/0/Note/plan.note', page: -1});
    expect(logger.lines).toContain('log [SNCANVAS][LINK] linking to note=/storage/emulated/0/Note/plan.note');
  });

  test('a cancelled picker links nothing', async () => {
    const {logger, session} = setup({[SCRATCH]: 'scratch'});
    expect(await session.pickNoteLink()).toBeNull();
    expect(logger.lines).toContain('log [SNCANVAS][LINK] no note picked; nothing linked');
  });

  const link = {kind: 'note', target: '/n.note', page: -1} as const;

  test('following a link saves the canvas, steps Canvas aside for the note, and leaves the back badge over it', async () => {
    const {store, host, badge, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(500);
    store.shown = 'scratch, and more';
    expect(await session.followLink(link)).toBe(true);
    expect(store.files.get(SCRATCH)).toBe('scratch, and more');
    expect(host.steps).toEqual(['close', 'open /n.note']);
    expect(badge.shown).toEqual({label: 'note', notePath: '/n.note'});
    expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, kind: 'note', canvasId: 'default', to: '/n.note'});
    expect(logger.lines).toContain('log [SNCANVAS][LINK] followed link to /n.note page=-1');
  });

  describe('links to another canvas of the same note (#2)', () => {
    /** Two canvases saved in this note, with c-one shown and c-other the one a link leads to. */
    const twoCanvases = async () => {
      const kit = setup({[canvasFile('c-one')]: 'the first drawing', [canvasFile('c-other')]: 'the other drawing'});
      await kit.session.open(500);
      await kit.session.switchTo('c-one');
      return kit;
    };

    test('following one brings the other canvas up, without leaving the note, and offers the way back', async () => {
      const {store, host, badge, logger, session} = await twoCanvases();
      const shownBefore = session.currentCanvasId();

      expect(await session.followLink({kind: 'canvas', target: 'c-other', page: -1})).toBe(true);
      expect(session.currentCanvasId()).toBe('c-other');
      expect(store.shown).toBe('the other drawing');
      // Nothing left the plugin, so no note opened and no badge went over one.
      expect(host.steps).toEqual([]);
      expect(badge.shown).toBeNull();
      expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, kind: 'canvas', canvasId: shownBefore});
      expect(logger.lines).toContain('log [SNCANVAS][LINK] switched to canvas=c-other');
    });

    test('going back brings the canvas it was followed from up again, still without leaving', async () => {
      const {store, host, session} = await twoCanvases();
      const shownBefore = session.currentCanvasId();
      await session.followLink({kind: 'canvas', target: 'c-other', page: -1});

      await session.goBack();
      expect(session.currentCanvasId()).toBe(shownBefore);
      expect(host.steps).toEqual([]);
      expect(session.backTo()).toBeNull();
      expect(store.shown).toBe('the first drawing');
    });

    // The same hazard as following a dead link, from the other end: the canvas a step goes back to can
    // be deleted while you are away from it, and showing it would record that nothing as the note's.
    // What show() records for the note, which decides what the sidebar reopens later. Both assertions
    // matter: without the first, the second would pass on a fixture that never recorded c-other.
    test('the canvas come back to is the one this note reopens, not the one the link led to', async () => {
      const {store, session} = await twoCanvases();
      await session.followLink({kind: 'canvas', target: 'c-other', page: -1});
      expect(savedIndex(store).lastByNote['/note.note']).toBe('c-other');

      await session.goBack();
      expect(savedIndex(store).lastByNote['/note.note']).toBe('c-one');
    });

    // Without a note page there is nothing to record a step against, and a step with no page would
    // leave the header offering a way back to nowhere.
    test('a canvas link followed from a page the host cannot name switches, but keeps no way back', async () => {
      const {host, store, session} = await twoCanvases();
      host.page = null;

      expect(await session.followLink({kind: 'canvas', target: 'c-other', page: -1})).toBe(true);
      expect(session.currentCanvasId()).toBe('c-other');
      expect(store.shown).toBe('the other drawing');
      expect(session.backTo()).toBeNull();
    });

    /** Three canvases, so a trail can hold more than one step and popping one is not popping all. */
    const threeCanvases = async () => {
      const kit = setup({
        [canvasFile('c-one')]: 'the first drawing',
        [canvasFile('c-other')]: 'the other drawing',
        [canvasFile('c-third')]: 'the third drawing',
      });
      await kit.session.open(500);
      await kit.session.switchTo('c-one');
      await kit.session.followLink({kind: 'canvas', target: 'c-other', page: -1});
      await kit.session.followLink({kind: 'canvas', target: 'c-third', page: -1});
      return kit;
    };

    // One step at a time, as the firmware's own Back does. With a single step on the trail, taking one
    // and taking all leave the same trail, so nothing shorter than this tells them apart.
    test('two canvas links followed come back one canvas at a time', async () => {
      const {store, session} = await threeCanvases();

      await session.goBack();
      expect(session.currentCanvasId()).toBe('c-other');
      expect(store.shown).toBe('the other drawing');
      // The way back to the canvas the first link was followed from is still there to take.
      expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, kind: 'canvas', canvasId: 'c-one'});

      await session.goBack();
      expect(session.currentCanvasId()).toBe('c-one');
      expect(store.shown).toBe('the first drawing');
      expect(session.backTo()).toBeNull();
    });

    test('a step back to a canvas that has gone drops that step and no more', async () => {
      const {store, session} = await threeCanvases();
      store.files.delete(canvasFile('c-other'));

      await session.goBack();
      // Nothing was shown for the dead step, and the step under it is still there to take.
      expect(session.currentCanvasId()).toBe('c-third');
      expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, kind: 'canvas', canvasId: 'c-one'});

      await session.goBack();
      expect(session.currentCanvasId()).toBe('c-one');
      expect(store.shown).toBe('the first drawing');
      expect(session.backTo()).toBeNull();
    });

    test('a step back to a canvas that has gone is dropped, not loaded over the note', async () => {
      const {store, session, logger} = await twoCanvases();
      await session.followLink({kind: 'canvas', target: 'c-other', page: -1});
      expect(session.currentCanvasId()).toBe('c-other');

      store.files.delete(canvasFile('c-one'));
      await session.goBack();

      // Still on the canvas it was already showing, with its drawing, and the dead step let go of.
      expect(session.currentCanvasId()).toBe('c-other');
      expect(store.shown).toBe('the other drawing');
      expect(session.backTo()).toBeNull();
      expect(logger.lines).toContain('warn [SNCANVAS][LINK] canvas=c-one is no longer here; that step back is gone');
    });

    // Showing it would load nothing, and make that nothing the note's own canvas, losing its place.
    test('a link to a canvas that is no longer saved is refused, and nothing is switched', async () => {
      const {store, session, logger} = await twoCanvases();
      const shownBefore = session.currentCanvasId();
      store.files.delete(canvasFile('c-other'));

      expect(await session.followLink({kind: 'canvas', target: 'c-other', page: -1})).toBe(false);
      expect(session.currentCanvasId()).toBe(shownBefore);
      expect(session.backTo()).toBeNull();
      expect(logger.lines).toContain('warn [SNCANVAS][LINK] could not switch to canvas=c-other');
    });

    // Taps queue and the screen is slow, so tapping a glyph twice is ordinary. The second is nothing
    // to do, not a failure: saying the canvas has gone about one on screen would be a lie.
    test('a link to the canvas already shown does nothing, and is not reported as a failure', async () => {
      const {session, logger} = await twoCanvases();
      const here = session.currentCanvasId();
      expect(await session.followLink({kind: 'canvas', target: here, page: -1})).toBe(true);
      expect(session.currentCanvasId()).toBe(here);
      expect(session.backTo()).toBeNull();
      expect(logger.lines).toContain(`log [SNCANVAS][LINK] canvas=${here} is the one shown; nothing to switch to`);
      expect(logger.lines.some(line => line.includes('could not switch to'))).toBe(false);
    });

    test('tapping the same link twice switches once and leaves one step, not two', async () => {
      const {session} = await twoCanvases();
      const from = session.currentCanvasId();
      const toOther = {kind: 'canvas', target: 'c-other', page: -1} as const;
      expect(await session.followLink(toOther)).toBe(true);
      expect(await session.followLink(toOther)).toBe(true);
      expect(session.currentCanvasId()).toBe('c-other');
      expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, kind: 'canvas', canvasId: from});
      await session.goBack();
      expect(session.currentCanvasId()).toBe(from);
      expect(session.backTo()).toBeNull();
    });
  });

  test('a note that will not open brings Canvas straight back, with no badge and no step, and says so', async () => {
    const {host, badge, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(500);
    host.openNoteSucceeds = false;
    expect(await session.followLink(link)).toBe(false);
    expect(host.steps).toEqual(['close', 'show']);
    expect(badge.shown).toBeNull();
    expect(session.backTo()).toBeNull();
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] could not follow link to /n.note page=-1');
  });

  test('a link followed before any canvas opened, or from a page the host cannot name, keeps no way back', async () => {
    const {store, host, badge, session} = setup({[SCRATCH]: 'scratch'});
    host.page = null;
    expect(await session.followLink(link)).toBe(true);
    expect(store.files.get(SCRATCH)).toBe('scratch');
    expect(host.openedNotes).toEqual([{path: '/n.note', page: -1}]);
    expect(badge.shown).toBeNull();
    expect(session.backTo()).toBeNull();
  });

  test('Canvas opened anywhere but the note the link led to takes the badge down and lets the trail go', async () => {
    const {host, badge, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(500);
    await session.followLink(link);
    host.page = {notePath: '/elsewhere.note', page: 0};
    await session.open(500);
    expect(badge.shown).toBeNull();
    expect(session.backTo()).toBeNull();
  });

  // The same guard covers a note step: its canvas can be deleted while the note is open over it.
  test('a step back whose canvas has gone is dropped rather than opening the note over nothing', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(500);
    await session.followLink(link);
    store.files.delete(SCRATCH);

    await session.goBack();
    // Nothing was opened or closed for it, and the step is let go of.
    expect(host.openedNotes).toEqual([{path: '/n.note', page: -1}]);
    expect(session.backTo()).toBeNull();
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] canvas=default is no longer here; that step back is gone');
  });

  test('with no step to take, going back does nothing but say so', async () => {
    const {host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.goBack();
    expect(host.steps).toEqual([]);
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] nothing to go back to');
  });
});

describe('the trail back along followed links', () => {
  const A = {notePath: '/Note/a.note', page: 2};
  const B = {notePath: '/Note/b.note', page: 0};
  const C = {notePath: '/Note/c.note', page: 0};
  const to = (note: {notePath: string}) => ({kind: 'note', target: note.notePath, page: -1}) as const;

  /** Note A's canvas links to B; in B, B's own canvas links to C. */
  const walkedToC = async () => {
    const context = setup({[canvasFile('c-a')]: 'drawn in A', [INDEX]: JSON.stringify({lastByNote: {[A.notePath]: 'c-a'}})});
    const {store, host, session} = context;
    host.page = A;
    await session.open(500);
    await session.followLink(to(B));
    host.page = B;
    await session.open(500);
    store.shown = 'drawn in B';
    await session.followLink(to(C));
    host.page = C;
    return context;
  };

  test('each step back reopens the note the link left, at its page, with Canvas over it showing its canvas', async () => {
    const {store, host, badge, logger, session} = await walkedToC();
    expect(badge.shown).toEqual({label: 'b', notePath: C.notePath});
    host.steps.length = 0;
    await session.goBack();
    expect(host.steps).toEqual(['close', `open ${B.notePath}`]);
    expect(badge.arrivals).toEqual([B.notePath]);
    expect(store.shown).toBe('drawn in B');
    expect(session.backTo()).toMatchObject({notePath: A.notePath, canvasId: 'c-a'});
    expect(logger.lines).toContain('log [SNCANVAS][LINK] back to /Note/b.note page=0 canvas=default');
    host.page = B;
    await session.goBack();
    expect(host.openedNotes.at(-1)).toEqual({path: A.notePath, page: 2});
    expect(store.shown).toBe('drawn in A');
    expect(store.files.get(SCRATCH)).toBe('drawn in B');
    expect(session.backTo()).toBeNull();
  });

  // From the badge, Canvas is down: there is no view to save from or load into, and the canvas the step names
  // is the one already shown. Switching to it anyway only waits out the load's five seconds for a view that
  // cannot arrive, which is the whole of the delay in coming back.
  test('a step back from the badge does not save or load the canvas it is already showing', async () => {
    const {store, host, badge, session} = await walkedToC();
    store.emptyView();
    store.refused.length = 0;
    host.steps.length = 0;
    await session.goBack();
    expect(store.refused).toEqual([]);
    expect(store.shown).toBe('');
    // The step back itself still happens: the note reopens and the trail moves on.
    expect(host.steps).toEqual(['close', `open ${B.notePath}`]);
    expect(badge.arrivals).toEqual([B.notePath]);
    expect(session.backTo()).toMatchObject({notePath: A.notePath});
  });

  test('a note that does not come back in time leaves Canvas down and the step in place, and says so', async () => {
    const {host, badge, logger, session} = await walkedToC();
    badge.arrives = false;
    host.steps.length = 0;
    await session.goBack();
    expect(host.steps).toEqual(['close', `open ${B.notePath}`]);
    expect(session.backTo()).toMatchObject({notePath: B.notePath});
    expect(logger.lines).toContain(
      'warn [SNCANVAS][LINK] could not come back in time to /Note/b.note page=0 canvas=default; Canvas stays down',
    );
  });

  test('from the badge, a note that will not reopen leaves Canvas down and the badge back over the note', async () => {
    const {host, badge, logger, session} = await walkedToC();
    host.openNoteSucceeds = false;
    host.steps.length = 0;
    await session.goBack();
    expect(host.steps).toEqual(['close']);
    expect(badge.shown).toEqual({label: 'b', notePath: C.notePath});
    expect(session.backTo()).toMatchObject({notePath: B.notePath});
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] could not go back to /Note/b.note page=0 canvas=default');
  });

  test('Canvas the way back brought up steps aside for a picker, and comes back once the pick is made', async () => {
    const {host, session} = await walkedToC();
    await session.goBack();
    host.steps.length = 0;
    host.pickedNote = '/Note/d.note';
    expect(await session.pickNoteLink()).toMatchObject({target: '/Note/d.note'});
    host.picked = null;
    await session.insertImage();
    expect(host.steps).toEqual(['close', 'pick note', 'show', 'close', 'pick image', 'show']);
  });

  test('Canvas the note brought up is left to the host for a picker, as before', async () => {
    const {host, session} = await walkedToC();
    host.page = B;
    await session.open(500);
    host.steps.length = 0;
    await session.pickNoteLink();
    expect(host.steps).toEqual(['pick note']);
  });

  test('a second tap while a step back runs is one step, not two', async () => {
    const {session} = await walkedToC();
    await Promise.all([session.goBack(), session.goBack()]);
    expect(session.backTo()).toMatchObject({notePath: A.notePath});
  });

  test('a note that will not reopen leaves Canvas on the canvas it showed, with the step still there to take', async () => {
    const {store, host, logger, session} = await walkedToC();
    await session.goBack();
    host.page = B;
    store.shown = 'drawn in B, and more';
    host.openNoteSucceeds = false;
    host.steps.length = 0;
    await session.goBack();
    expect(host.steps).toEqual(['close', 'show']);
    expect(store.shown).toBe('drawn in B, and more');
    expect(store.files.get(canvasFile('c-a'))).toBe('drawn in A');
    expect(session.backTo()).toMatchObject({notePath: A.notePath, canvasId: 'c-a'});
    expect(savedIndex(store).lastByNote[B.notePath]).toBe('default');
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] could not go back to /Note/a.note page=2 canvas=c-a');
  });

  test('a thumbnail, or New canvas, lets the trail go', async () => {
    const fromThumbnail = await walkedToC();
    await fromThumbnail.session.open(501);
    expect(fromThumbnail.session.backTo()).toBeNull();
    const fresh = await walkedToC();
    await fresh.session.newCanvas();
    expect(fresh.session.backTo()).toBeNull();
  });

  // Clearing says nothing about how this canvas was reached, so the way back outlives it (#44).
  test('clearing the canvas keeps the way back, and it still leads to a canvas with its drawing', async () => {
    const {store, session} = await walkedToC();
    await session.clearCanvas();
    expect(session.backTo()).toMatchObject({notePath: B.notePath, canvasId: 'default'});
    await session.goBack();
    expect(store.shown).toBe('drawn in B');
  });
});

describe('a canvas file only ever holds the canvas loaded from it (#30)', () => {
  test('reopening the canvas the session last showed reloads it when the view came back without it', async () => {
    const {store, session} = setup({[canvasFile('c-2')]: 'second drawing', [INDEX]: indexWith({lastCanvasId: 'c-2'})});
    await session.open(500);
    await session.close();
    // As on device at 13:20: Canvas comes back, its view empty, the session still naming c-2.
    store.emptyView();
    await session.open(500);
    expect(store.shown).toBe('second drawing');
    // Moving on saves the drawing where it came from, not an empty view over it.
    await session.newCanvas();
    expect(store.files.get(canvasFile('c-2'))).toBe('second drawing');
  });

  test('a load that never reached the view leaves the canvas it named untouched by every later save', async () => {
    const {store, session} = setup({[canvasFile('c-2')]: 'second drawing', [INDEX]: indexWith({lastCanvasId: 'c-2'})});
    store.failing.add('load');
    await session.open(500);
    store.shown = 'whatever the view happened to show';
    await session.newCanvas();
    await session.close();
    expect(store.files.get(canvasFile('c-2'))).toBe('second drawing');
    expect(store.refused).toContain(canvasFile('c-2'));
  });
});

describe("a note's canvases (#30)", () => {
  /** Two canvases saved to one page, as on device: the note keeps the first thumbnail and drops the second. */
  const secondDropped = async () => {
    const context = setup({[SCRATCH]: 'first drawing'});
    const {store, host, session} = context;
    await session.open(500);
    await session.saveToNote();
    host.unsaved = [notePicture(117)];
    await session.newCanvas();
    store.shown = 'second drawing';
    await session.saveToNote();
    return context;
  };

  test("the scratch canvas saved to a note stays that note's canvas under its new id, and reopens there", async () => {
    const {store, session} = setup({[SCRATCH]: 'first drawing'});
    await session.open(500);
    await session.saveToNote();
    await session.close();
    await session.open(500);
    expect(store.shown).toBe('first drawing');
    expect(savedIndex(store).lastByNote['/note.note']).toBe('c-1');
  });

  test("the note's canvases list the one shown first, and any of them is a tap away", async () => {
    const {store, session} = await secondDropped();
    const listed = await session.canvasesHere();
    expect(listed.map(canvas => [canvas.canvasId, canvas.isShown])).toEqual([
      ['c-2', true],
      ['c-1', false],
    ]);
    expect(listed[1]).toEqual({canvasId: 'c-1', madeAt: null, thumbnail: thumbnail('c-1'), isShown: false});
    await session.switchTo('c-1');
    expect(store.shown).toBe('first drawing');
    expect(store.files.get(canvasFile('c-2'))).toBe('second drawing');
    // A canvas deleted since is not offered.
    store.files.delete(canvasFile('c-2'));
    expect((await session.canvasesHere()).map(canvas => canvas.canvasId)).toEqual(['c-1']);
  });

  test('without a folder for canvases there is nothing to list or switch to', async () => {
    const {host, store, session} = setup();
    host.dir = null;
    expect(await session.canvasesHere()).toEqual([]);
    await session.switchTo('c-1');
    expect(store.shown).toBe('');
  });

  test('with no note to go by, the list holds the canvas shown, and a switch still shows the one picked', async () => {
    const {store, host, session} = await secondDropped();
    host.page = null;
    expect((await session.canvasesHere()).map(canvas => canvas.canvasId)).toEqual(['c-2']);
    await session.switchTo('c-1');
    expect(store.shown).toBe('first drawing');
  });

});

describe('saveToNote result', () => {
  test("is 'inserted' once the thumbnail is in the note, null when it is not", async () => {
    const {host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(await session.saveToNote()).toBe('inserted');
    host.insertSucceeds = false;
    expect(await session.saveToNote()).toBeNull();
  });

  test('is null for a tap ignored while one runs, and without a plugin directory', async () => {
    const {session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(await Promise.all([session.saveToNote(), session.saveToNote()])).toEqual(['inserted', null]);
    const {session: noDirSession, host: noDirHost} = setup();
    noDirHost.dir = null;
    expect(await noDirSession.saveToNote()).toBeNull();
  });
});
