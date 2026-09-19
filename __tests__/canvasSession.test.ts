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
  test('Save to Note leaves a link pending; the first Open Canvas on the thumbnail tags it, and from then on the tag alone opens it', async () => {
    const first = setup({[SCRATCH]: 'drawing'});
    first.host.elements = [notePicture(5)];
    await first.session.open(null);
    await first.session.saveToNote();
    await first.session.close();
    expect(savedIndex(first.store).pending).toEqual([
      {notePath: '/note.note', page: 0, canvasId: 'c-1', knownPictureNumbers: [5]},
    ]);
    // A later session, with another canvas saved since: the link, not recency, decides.
    const later = setup({...Object.fromEntries(first.store.files), [canvasFile('c-later')]: 'other'});
    later.host.lassoed = [lassoedPicture(42)];
    await later.session.open(501);
    expect(later.store.shown).toBe('drawing');
    expect(later.host.tagged).toEqual([{canvasId: 'c-1', picture: lassoedPicture(42), imagePath: thumbnail('c-1')}]);
    expect(later.logger.lines).toContain('log [SNCANVAS][LINK] tagged the thumbnail for canvas=c-1');
    expect(savedIndex(later.store).pending).toEqual([]);
    // The note keeps the tag with the picture: whatever copy a lasso hands over, and wherever it moved, it names its canvas.
    const again = setup({...Object.fromEntries(later.store.files), [canvasFile('c-latest')]: 'newest'});
    again.host.page = null;
    again.host.lassoed = [lassoedPicture(9, 'sncanvas:c-1')];
    await again.session.open(501);
    expect(again.store.shown).toBe('drawing');
    expect(again.host.tagged).toEqual([]);
  });

  test('Save to Note refreshes the thumbnail of this canvas already on the page instead of adding a second', async () => {
    const {store, host, logger, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    // The note has the thumbnail of this canvas on the page, tagged by an earlier Open Canvas.
    const placed = notePicture(7, 'sncanvas:c-1');
    host.elements = [notePicture(3), placed];
    expect(await session.saveToNote()).toBe('refreshed');
    expect(store.files.get(thumbnail('c-1'))).toBe('png:drawing');
    // Modified where it sits, so it keeps the place and size it was given; nothing new inserted.
    expect(host.tagged).toEqual([{canvasId: 'c-1', picture: placed, imagePath: thumbnail('c-1')}]);
    expect(host.inserted).toEqual([]);
    // The note is saved first, or modifying its elements races its own writes.
    expect(host.noteSaves).toBe(1);
    // It is already tagged with its canvas, so nothing has to wait to claim it.
    expect(savedIndex(store).pending).toEqual([]);
    expect(logger.lines).toContain('log [SNCANVAS][LINK] refreshed thumbnail for canvas=c-1');
  });

  test('an untagged thumbnail is still recognised by the PNG it was inserted from, and refreshed', async () => {
    const {host, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    // What the device reports: tagging a lasso's copy never took, so the picture on the page carries no tag.
    const placed = {type: 200, numInPage: 103, pageNum: -1, picture: {picturePath: thumbnail('c-1')}};
    host.elements = [placed];
    expect(await session.saveToNote()).toBe('refreshed');
    expect(host.tagged).toEqual([{canvasId: 'c-1', picture: placed, imagePath: thumbnail('c-1')}]);
    expect(host.inserted).toEqual([]);
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

  test('with no note page, a linked canvas has nothing to refresh and its thumbnail is inserted', async () => {
    const {host, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    host.page = null;
    host.elements = [{type: 200, numInPage: 1, picture: {picturePath: thumbnail('c-1')}}];
    expect(await session.saveToNote()).toBe('inserted');
    expect(host.tagged).toEqual([]);
  });

  test('a thumbnail of another canvas on the page is left alone; this canvas gets its own', async () => {
    const {host, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    host.elements = [notePicture(7, 'sncanvas:c-other')];
    expect(await session.saveToNote()).toBe('inserted');
    expect(host.inserted).toEqual([thumbnail('c-1')]);
    expect(host.tagged).toEqual([]);
  });

  test('a canvas that was never linked does not wait on the page read before saving', async () => {
    const {host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    let reads = 0;
    host.pageElements = async () => {
      reads += 1;
      return [];
    };
    expect(await session.saveToNote()).toBe('inserted');
    // Nothing was read before the thumbnail went in: the save did not wait on it.
    expect(reads).toBe(0);
    // The page is read afterwards, for the link that waits to be claimed.
    await session.close();
    expect(reads).toBe(1);
  });

  test('when the thumbnail on the page cannot be refreshed, nothing is inserted in its place', async () => {
    const {host, logger, session} = setup({[canvasFile('c-1')]: 'drawing', [INDEX]: indexWith({lastCanvasId: 'c-1'})});
    await session.open(null);
    host.elements = [notePicture(7, 'sncanvas:c-1')];
    host.tagSucceeds = false;
    expect(await session.saveToNote()).toBeNull();
    expect(host.inserted).toEqual([]);
    expect(logger.lines).toContain('warn [SNCANVAS][LINK] could not refresh the thumbnail for canvas=c-1 on page=0');
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

  test('when the thumbnail cannot be tagged, its canvas still opens and its link keeps waiting', async () => {
    const first = setup({[SCRATCH]: 'drawing'});
    await first.session.open(null);
    await first.session.saveToNote();
    await first.session.close();
    const later = setup(Object.fromEntries(first.store.files));
    later.host.tagSucceeds = false;
    later.host.lassoed = [lassoedPicture(42)];
    await later.session.open(501);
    expect(later.store.shown).toBe('drawing');
    expect(savedIndex(later.store).pending).toHaveLength(1);
    expect(later.logger.lines).toContain(
      'warn [SNCANVAS][LINK] could not tag the thumbnail for canvas=c-1; its link stays pending',
    );
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
    expect(savedIndex(store)).toEqual({lastByNote: {}, lastCanvasId: 'c-1', pending: []});
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
    expect(savedIndex(store)).toEqual({lastByNote: {'/note.note': 'default'}, lastCanvasId: 'default', pending: []});
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
    ['saving the canvas', 'save'],
    ['rendering the thumbnail', 'renderThumbnail'],
    ['inserting the image', 'insertImage'],
  ])('when %s fails, the scratch canvas stays as it was and no new files are left behind', async (_step, failing) => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    if (failing === 'insertImage') {
      host.insertSucceeds = false;
    } else {
      store.failing.add(failing as 'save' | 'renderThumbnail');
    }
    await session.saveToNote();
    expect(session.currentCanvasId()).toBe('default');
    expect([...store.files.keys()]).toEqual([MARKER, SCRATCH, INDEX]);
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
    expect(session.backTo()).toEqual({notePath: '/note.note', page: 0, canvasId: 'default', to: '/n.note'});
    expect(logger.lines).toContain('log [SNCANVAS][LINK] followed link to /n.note page=-1');
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
