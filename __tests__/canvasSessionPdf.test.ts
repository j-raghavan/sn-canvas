/**
 * Export to PDF (FR11): the canvas shown goes to a PDF in EXPORT, named for
 * when it was exported, only with file write access; a failed export is
 * reported, never thrown.
 */
import {createCanvasSession} from '../src/application/canvasSession';
import {createFakeBadge, createFakeHost, createFakeStore, createRecordingLogger} from './helpers/fakePorts';

// Canvas has opened since it was installed, so no test here starts a new canvas.
const MARKER = '/plugin/canvas-opened';
// 14 September 2026, 11:15:07 by the device's clock.
const AT = new Date(2026, 8, 14, 11, 15, 7);
const PDF = '/storage/emulated/0/EXPORT/Canvas-20260914-111507.pdf';

const setup = () => {
  const store = createFakeStore({[MARKER]: 'opened'});
  const host = createFakeHost();
  host.fileWrite = true;
  const logger = createRecordingLogger();
  const session = createCanvasSession({store, host, badge: createFakeBadge(), logger, newCanvasId: () => 'c-1', now: () => AT});
  return {store, host, logger, session};
};

test('the canvas shown is exported to a PDF in EXPORT, named for when it was exported', async () => {
  const {store, logger, session} = setup();
  await session.open(null);
  expect(await session.exportPdf()).toBe(PDF);
  expect(store.exported).toEqual([PDF]);
  expect(logger.lines).toContain(`log [SNCANVAS][PDF] exported ${PDF}`);
});

test('without file write access nothing is exported', async () => {
  const {store, host, logger, session} = setup();
  host.fileWriteOnly = false;
  expect(await session.exportPdf()).toBeNull();
  expect(store.exported).toEqual([]);
  expect(logger.lines).toContain('warn [SNCANVAS][PDF] no file write access; nothing exported');
});

// #17: an export writes a file and nothing else, so it asks to write and nothing else. Asking to
// delete for it showed a dialog about deleting files, and refused the export when they said no.
test('an export asks only to write, and goes ahead when deleting was refused', async () => {
  const {store, host, session} = setup();
  // Whoever said no to deleting, and so has no canvas folder to keep canvases in.
  host.fileWrite = false;
  host.fileWriteOnly = true;
  expect(await session.exportPdf()).toBe(PDF);
  expect(store.exported).toEqual([PDF]);
  expect(host.writeRequests).toBe(1);
  expect(host.accessRequests).toBe(0);
});

test('an export the canvas cannot write is reported, and no path comes back', async () => {
  const {store, logger, session} = setup();
  store.failing.add('exportPdf');
  expect(await session.exportPdf()).toBeNull();
  expect(logger.lines).toContain(`warn [SNCANVAS][PDF] could not export ${PDF}`);
});

test('without a clock given, an export is named by the real one', async () => {
  const store = createFakeStore({[MARKER]: 'opened'});
  const host = createFakeHost();
  host.fileWrite = true;
  const session = createCanvasSession({store, host, badge: createFakeBadge(), logger: createRecordingLogger(), newCanvasId: () => 'c-1'});
  expect(await session.exportPdf()).toMatch(/^\/storage\/emulated\/0\/EXPORT\/Canvas-\d{8}-\d{6}\.pdf$/);
});
