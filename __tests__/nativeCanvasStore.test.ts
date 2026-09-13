/**
 * The native canvas facade never rejects: each port call maps to its
 * SuperCanvasModule method, and a missing module, a rejection or a non-true
 * result all come back as false.
 */
import {createNativeCanvasStore, type NativeCanvasModule} from '../src/infrastructure/nativeCanvasStore';
import {createRecordingLogger} from './helpers/fakePorts';

const createNative = (): jest.Mocked<NativeCanvasModule> => ({
  loadCanvas: jest.fn().mockResolvedValue(true),
  saveCanvas: jest.fn().mockResolvedValue(true),
  deleteCanvas: jest.fn().mockResolvedValue(true),
  generateThumbnail: jest.fn().mockResolvedValue(true),
});

test('each port call reaches its native method with the path', async () => {
  const native = createNative();
  const store = createNativeCanvasStore(createRecordingLogger(), native);
  expect(await store.load('/a.json')).toBe(true);
  expect(await store.save('/b.json')).toBe(true);
  expect(await store.remove('/c.json')).toBe(true);
  expect(await store.renderThumbnail('/d.png')).toBe(true);
  expect(native.loadCanvas).toHaveBeenCalledWith('/a.json');
  expect(native.saveCanvas).toHaveBeenCalledWith('/b.json');
  expect(native.deleteCanvas).toHaveBeenCalledWith('/c.json');
  expect(native.generateThumbnail).toHaveBeenCalledWith('/d.png');
});

test('a native false (e.g. loading a canvas never saved) is false', async () => {
  const native = createNative();
  native.loadCanvas.mockResolvedValue(false);
  expect(await createNativeCanvasStore(createRecordingLogger(), native).load('/new.json')).toBe(false);
});

test('a native rejection is logged and reported as false', async () => {
  const native = createNative();
  native.saveCanvas.mockRejectedValue(new Error('E_NO_ACTIVE_VIEW'));
  const logger = createRecordingLogger();
  expect(await createNativeCanvasStore(logger, native).save('/a.json')).toBe(false);
  expect(logger.lines).toEqual(['warn [SUPERCANVAS] saveCanvas failed: Error: E_NO_ACTIVE_VIEW']);
});

test('without the native module every call is false, with an error saying why', async () => {
  const logger = createRecordingLogger();
  const store = createNativeCanvasStore(logger);
  expect(await store.load('/a.json')).toBe(false);
  expect(logger.lines[0]).toMatch(/^error \[SUPERCANVAS\] loadCanvas: NativeModules\.SuperCanvasModule is missing/);
});
