/**
 * The canvas session against in-memory ports: which canvas each button press
 * opens, the save-before-switch rule, "Save to Note" linking (FR12/FR13) and
 * its failure handling, the link index that brings a thumbnail's canvas back,
 * the double-tap guard, new canvases, and close.
 */
import {createCanvasSession} from '../src/application/canvasSession';
import {createFakeHost, createFakeStore, createRecordingLogger} from './helpers/fakePorts';

const SCRATCH = '/plugin/SuperCanvas/default.json';
const INDEX = '/plugin/SuperCanvas/links.json';
const canvasFile = (id: string) => `/plugin/SuperCanvas/${id}.json`;
const thumbnail = (id: string) => `/plugin/SuperCanvas/thumbnails/${id}.png`;
const lassoedThumbnail = (id: string) => ({picture: {picturePath: thumbnail(id)}});
// What this firmware hands back for a lassoed thumbnail: its uuid, and a temporary copy of the picture.
const lassoedPicture = (uuid: string) => ({uuid, picture: {picturePath: 'plugin/1789355421616.png'}});
const indexWith = (index: {links?: Record<string, string>; lastCanvasId?: string}) =>
  JSON.stringify({links: {}, lastCanvasId: null, ...index});
const savedIndex = (store: {files: Map<string, string>}) => JSON.parse(String(store.files.get(INDEX)));

const setup = (files: Record<string, string> = {}) => {
  const store = createFakeStore(files);
  const host = createFakeHost();
  const logger = createRecordingLogger();
  let minted = 0;
  const session = createCanvasSession({
    store,
    host,
    logger,
    newCanvasId: () => {
      minted += 1;
      return `c-${minted}`;
    },
  });
  return {store, host, logger, session};
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

  test('Open Canvas on a lassoed SuperCanvas thumbnail shows its linked canvas', async () => {
    const {store, host, logger, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [{}, lassoedThumbnail('c-9')];
    await session.open(501);
    expect(store.shown).toBe('nine');
    expect(session.currentCanvasId()).toBe('c-9');
    expect(logger.lines).toContain('log [SUPERCANVAS][LINK] lassoed=2 uuids=[null,null] canvas=c-9');
  });

  test('Open Canvas on any other picture, with no other canvas saved, falls back to the scratch canvas', async () => {
    const {store, host, session} = setup({[SCRATCH]: 'scratch'});
    host.lassoed = [{picture: {picturePath: '/note/images/photo.png'}}];
    await session.open(501);
    expect(store.shown).toBe('scratch');
    expect(session.currentCanvasId()).toBe('default');
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
    expect(logger.lines).toContain('warn [SUPERCANVAS] no plugin directory; canvas not loaded or saved');
    host.dir = '/plugin';
    await session.open(500);
    expect(store.shown).toBe('scratch');
  });
});

describe('links back to a canvas', () => {
  test('Save to Note links the thumbnail by its uuid, so Open Canvas on it finds its canvas again later', async () => {
    const first = setup({[SCRATCH]: 'drawing'});
    first.host.lastUuid = 'u-7';
    await first.session.open(null);
    await first.session.saveToNote();
    // A later session, with another canvas saved since: the link, not recency, decides.
    const later = setup({...Object.fromEntries(first.store.files), [canvasFile('c-later')]: 'other'});
    later.host.lassoed = [lassoedPicture('u-7')];
    await later.session.open(501);
    expect(later.store.shown).toBe('drawing');
    expect(later.session.currentCanvasId()).toBe('c-1');
    expect(later.logger.lines).toContain('log [SUPERCANVAS][LINK] lassoed=1 uuids=["u-7"] canvas=c-1');
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
    lasso.host.lassoed = [lassoedPicture('u-unknown')];
    await lasso.session.open(501);
    expect(lasso.store.shown).toBe('new');
  });

  test('a thumbnail whose uuid the host cannot report is still inserted, just not linked', async () => {
    const {store, host, logger, session} = setup({[SCRATCH]: 'scratch'});
    host.lastUuid = null;
    await session.open(null);
    expect(await session.saveToNote()).toBe(true);
    expect(savedIndex(store)).toEqual({links: {}, lastCanvasId: 'c-1'});
    expect(logger.lines).toContain(
      'warn [SUPERCANVAS][LINK] no uuid for the inserted thumbnail; Open Canvas on it will show the newest canvas',
    );
  });

  test('a damaged index is ignored, and written afresh', async () => {
    const {store, session} = setup({[SCRATCH]: 'scratch', [INDEX]: '{nope'});
    await session.open(500);
    expect(store.shown).toBe('scratch');
    expect(savedIndex(store)).toEqual({links: {}, lastCanvasId: 'default'});
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
    expect(savedIndex(store)).toEqual({links: {'u-1': 'c-1'}, lastCanvasId: 'c-1'});
  });

  test('from a linked canvas: re-links under the id it already has', async () => {
    const {store, host, session} = setup({[canvasFile('c-9')]: 'nine'});
    host.lassoed = [lassoedThumbnail('c-9')];
    await session.open(501);
    await session.saveToNote();
    expect(host.inserted).toEqual([thumbnail('c-9')]);
    expect(store.files.get(canvasFile('c-9'))).toBe('nine');
    expect(session.currentCanvasId()).toBe('c-9');
    expect(savedIndex(store).links).toEqual({'u-1': 'c-9'});
  });

  test('a double tap inserts one thumbnail', async () => {
    const {host, logger, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    await Promise.all([session.saveToNote(), session.saveToNote()]);
    expect(host.inserted).toEqual([thumbnail('c-1')]);
    expect(logger.lines).toContain('log [SUPERCANVAS][LINK] save to note already running; tap ignored');
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
    expect([...store.files.keys()]).toEqual([SCRATCH, INDEX]);
    expect(host.inserted).toEqual([]);
    expect(logger.lines).toContain('warn [SUPERCANVAS][LINK] save to note failed; canvas=default unchanged');
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
  expect(logger.lines).toContain('error [SUPERCANVAS] Error: boom');
  expect(host.closeCount).toBe(1);
});

describe('saveToNote result', () => {
  test('is true once the thumbnail is inserted, false when it is not', async () => {
    const {host, session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(await session.saveToNote()).toBe(true);
    host.insertSucceeds = false;
    expect(await session.saveToNote()).toBe(false);
  });

  test('is false for a tap ignored while one runs, and without a plugin directory', async () => {
    const {session} = setup({[SCRATCH]: 'scratch'});
    await session.open(null);
    expect(await Promise.all([session.saveToNote(), session.saveToNote()])).toEqual([true, false]);
    const {session: noDirSession, host: noDirHost} = setup();
    noDirHost.dir = null;
    expect(await noDirSession.saveToNote()).toBe(false);
  });
});
