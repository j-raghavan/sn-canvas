/**
 * The native canvas facade never rejects: each port call maps to its
 * SuperCanvasModule method, and a missing module, a rejection or a malformed
 * result all come back as false, null or empty.
 */
import {createNativeCanvasStore, type NativeCanvasModule} from '../src/infrastructure/nativeCanvasStore';
import {createRecordingLogger} from './helpers/fakePorts';

const createNative = (): jest.Mocked<NativeCanvasModule> => ({
  loadCanvas: jest.fn().mockResolvedValue(true),
  saveCanvas: jest.fn().mockResolvedValue(true),
  deleteCanvas: jest.fn().mockResolvedValue(true),
  generateThumbnail: jest.fn().mockResolvedValue(true),
  readText: jest.fn().mockResolvedValue(null),
  writeText: jest.fn().mockResolvedValue(true),
  listCanvasFiles: jest.fn().mockResolvedValue([]),
  adoptFolder: jest.fn().mockResolvedValue(0),
  setNotePen: jest.fn().mockResolvedValue(true),
  importImage: jest.fn().mockResolvedValue(true),
});

test('a picked image reaches the native view with the folder to copy it into, and only a native true is true', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  expect(await store.importImage('/sdcard/a.png', '/c/images')).toBe(true);
  expect(native.importImage).toHaveBeenCalledWith('/sdcard/a.png', '/c/images');
  native.importImage.mockResolvedValueOnce(false);
  expect(await store.importImage('/sdcard/a.pdf', '/c/images')).toBe(false);
});

test("the note's pen reaches the native view as its three codes, and only a native true is true", async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  expect(await store.rememberNotePen({type: 14, width: 700, color: 0})).toBe(true);
  expect(native.setNotePen).toHaveBeenCalledWith(14, 700, 0);
  native.setNotePen.mockResolvedValueOnce(false);
  expect(await store.rememberNotePen({type: 99, width: 700, color: 0})).toBe(false);
});

test('each port call reaches its native method with the path', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  expect(await store.load('/a.json', '/images')).toBe(true);
  expect(await store.save('/b.json')).toBe(true);
  expect(await store.remove('/c.json')).toBe(true);
  expect(await store.renderThumbnail('/d.png')).toBe(true);
  expect(native.loadCanvas).toHaveBeenCalledWith('/a.json', '/images');
  expect(native.saveCanvas).toHaveBeenCalledWith('/b.json');
  expect(native.deleteCanvas).toHaveBeenCalledWith('/c.json');
  expect(native.generateThumbnail).toHaveBeenCalledWith('/d.png');
});

test('a native false (e.g. loading a canvas never saved) is false', async () => {
  const native = createNative();
  native.loadCanvas.mockResolvedValue(false);
  expect(await createNativeCanvasStore(createRecordingLogger(), native).load('/new.json', '/images')).toBe(false);
});

test('the link index reads and writes as text, and anything but a string reads as no file', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  native.readText.mockResolvedValueOnce('{"links":{}}');
  expect(await store.readText('/links.json')).toBe('{"links":{}}');
  expect(await store.readText('/links.json')).toBeNull();
  native.readText.mockResolvedValueOnce(42 as unknown as string);
  expect(await store.readText('/links.json')).toBeNull();
  expect(await store.writeText('/links.json', '{}')).toBe(true);
  expect(native.writeText).toHaveBeenCalledWith('/links.json', '{}');
});

test('saved canvases list by id, newest first, leaving out the index and anything that is not a canvas', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  native.listCanvasFiles.mockResolvedValueOnce(['c-2.json', 'links.json', 'default.json', 'c-1.json', 'Notes.json']);
  expect(await store.savedCanvasIds('/p/SuperCanvas')).toEqual(['c-2', 'default', 'c-1']);
  expect(native.listCanvasFiles).toHaveBeenCalledWith('/p/SuperCanvas');
  native.listCanvasFiles.mockResolvedValueOnce('nope' as unknown as string[]);
  expect(await store.savedCanvasIds('/p/SuperCanvas')).toEqual([]);
});

test('adopting a folder reports how many files moved, and anything but a number as none', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  native.adoptFolder.mockResolvedValueOnce(3);
  expect(await store.adoptFolder('/plugin/SuperCanvas', '/shared')).toBe(3);
  expect(native.adoptFolder).toHaveBeenCalledWith('/plugin/SuperCanvas', '/shared');
  native.adoptFolder.mockResolvedValueOnce(null as unknown as number);
  expect(await store.adoptFolder('/plugin/SuperCanvas', '/shared')).toBe(0);
});

test('a native rejection is logged and reported as false', async () => {
  const native = createNative();
  native.saveCanvas.mockRejectedValue(new Error('E_NO_ACTIVE_VIEW'));
  const logger = createRecordingLogger();
  expect(await createNativeCanvasStore(logger, native).save('/a.json')).toBe(false);
  expect(logger.lines).toEqual(['warn [SUPERCANVAS] saveCanvas failed: Error: E_NO_ACTIVE_VIEW']);
});

test('without the native module every call falls back, with an error saying why', async () => {
  const logger = createRecordingLogger();
  const store = createNativeCanvasStore(logger);
  expect(await store.load('/a.json', '/images')).toBe(false);
  expect(await store.readText('/links.json')).toBeNull();
  expect(await store.writeText('/links.json', '{}')).toBe(false);
  expect(await store.savedCanvasIds('/p/SuperCanvas')).toEqual([]);
  expect(await store.adoptFolder('/a', '/b')).toBe(0);
  expect(logger.lines[0]).toMatch(/^error \[SUPERCANVAS\] loadCanvas: NativeModules\.SuperCanvasModule is missing/);
});
