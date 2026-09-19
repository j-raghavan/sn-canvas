/**
 * The canvas session's images (FR22): a canvas loads with the images folder
 * beside it, a picked image is copied there onto the canvas shown, and a
 * cancelled picker or a missing canvas folder adds nothing.
 */
import {createCanvasSession} from '../src/application/canvasSession';
import {createFakeBadge, createFakeHost, createFakeStore, createRecordingLogger} from './helpers/fakePorts';

// Canvas has opened since it was installed, so no test here starts a new canvas.
const MARKER = '/plugin/canvas-opened';
const IMAGES = '/plugin/Canvas/images';

const setup = () => {
  const store = createFakeStore({[MARKER]: 'opened'});
  const host = createFakeHost();
  const logger = createRecordingLogger();
  const session = createCanvasSession({store, host, badge: createFakeBadge(), logger, newCanvasId: () => 'c-1'});
  return {store, host, logger, session};
};

test('a canvas loads with the images folder of its canvas folder', async () => {
  const {store, session} = setup();
  await session.open(null);
  expect(store.imageDir).toBe(IMAGES);
});

test('a picked image is copied into the images folder, onto the canvas shown', async () => {
  const {store, host, logger, session} = setup();
  await session.open(null);
  host.picked = '/sdcard/Pictures/photo.jpg';
  expect(await session.insertImage()).toBe(true);
  expect(store.imported).toEqual([{source: '/sdcard/Pictures/photo.jpg', imageDir: IMAGES}]);
  expect(logger.lines).toContain('log [SNCANVAS][IMAGE] inserted /sdcard/Pictures/photo.jpg');
});

test('cancelling the picker inserts nothing', async () => {
  const {store, session} = setup();
  expect(await session.insertImage()).toBe(false);
  expect(store.imported).toEqual([]);
});

test('an image the canvas cannot take is reported, and nothing is inserted', async () => {
  const {store, host, logger, session} = setup();
  host.picked = '/sdcard/notes.pdf';
  store.failing.add('importImage');
  expect(await session.insertImage()).toBe(false);
  expect(logger.lines).toContain('warn [SNCANVAS][IMAGE] could not insert /sdcard/notes.pdf');
});

test('a picker that never answers holds up nothing: Close still saves and closes', async () => {
  const {host, session} = setup();
  await session.open(null);
  host.pickImage = () => new Promise(() => {});
  const picking = session.insertImage();
  await session.close();
  expect(picking).toBeInstanceOf(Promise);
  expect(host.closeCount).toBe(1);
});

test('without a canvas folder there is nowhere to copy an image to', async () => {
  const {store, host, session} = setup();
  host.dir = null;
  host.picked = '/sdcard/a.png';
  expect(await session.insertImage()).toBe(false);
  expect(store.imported).toEqual([]);
});
