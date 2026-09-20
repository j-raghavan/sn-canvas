/**
 * Canvas's file permissions: read, write and delete, each asked for only when
 * not granted yet, one dialog at a time, and one request shared by everyone
 * who asks while it is in flight (index.js at load, the session at open).
 */
const mockHasPermission = jest.fn();
const mockRequestPermission = jest.fn();

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    hasPermission: (name: string) => mockHasPermission(name),
    requestPermission: (name: string) => mockRequestPermission(name),
  },
}));

import {FILE_DELETE, FILE_READ, FILE_WRITE, createFileAccess} from '../src/infrastructure/filePermissions';
import {createRecordingLogger} from './helpers/fakePorts';

beforeEach(() => {
  mockHasPermission.mockReset();
  mockRequestPermission.mockReset();
});

test('asks for read, write and delete in turn, and is true once write and delete are granted', async () => {
  const logger = createRecordingLogger();
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockResolvedValue(2);
  expect(await createFileAccess(logger).forCanvases()).toBe(true);
  expect(mockRequestPermission.mock.calls.map(([name]) => name)).toEqual([FILE_READ, FILE_WRITE, FILE_DELETE]);
  expect(logger.lines).toContain(`log [SNCANVAS][PERM] ${FILE_WRITE} -> 2`);
});

test('does not ask for what is granted already', async () => {
  mockHasPermission.mockResolvedValue(1);
  expect(await createFileAccess(createRecordingLogger()).forCanvases()).toBe(true);
  expect(mockRequestPermission).not.toHaveBeenCalled();
});

test('is false when write or delete is refused, a request that fails counting as refused', async () => {
  const logger = createRecordingLogger();
  const requestFileAccess = createFileAccess(logger).forCanvases;
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockImplementation(async (name: string) => (name === FILE_DELETE ? 0 : 1));
  expect(await requestFileAccess()).toBe(false);
  mockRequestPermission.mockImplementation(async (name: string) => {
    if (name === FILE_WRITE) {
      throw new Error('This permission has not been declared.');
    }
    return 1;
  });
  expect(await requestFileAccess()).toBe(false);
  expect(logger.lines).toContain(`warn [SNCANVAS][PERM] ${FILE_WRITE} failed: Error: This permission has not been declared.`);
});

// #17: writing a PDF into EXPORT writes a file and nothing else. Asking to delete for it put a
// dialog about deleting files in front of the user, and refused the export when they said no.
test('the request for an export asks to write, and asks nothing about reading or deleting', async () => {
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockResolvedValue(2);
  expect(await createFileAccess(createRecordingLogger()).toWrite()).toBe(true);
  expect(mockRequestPermission.mock.calls.map(([name]) => name)).toEqual([FILE_WRITE]);
});

test('an export goes ahead with write alone, whatever was said about deleting', async () => {
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockImplementation(async (name: string) => (name === FILE_WRITE ? 1 : 0));
  const access = createFileAccess(createRecordingLogger());
  expect(await access.toWrite()).toBe(true);
  // The canvas folder still needs all three, so it is still refused.
  expect(await access.forCanvases()).toBe(false);
});

// Two things wanting the same permission while the dialog is up wait on that one dialog. Once it
// has been answered the firmware remembers, and hasPermission answers for it without asking again.
test('two exports while the write dialog is up wait on the one dialog', async () => {
  mockHasPermission.mockResolvedValue(0);
  let answer = (_granted: number) => {};
  mockRequestPermission.mockImplementation(
    (name: string) => (name === FILE_WRITE ? new Promise(resolve => (answer = resolve)) : Promise.resolve(1)),
  );
  const access = createFileAccess(createRecordingLogger());
  const first = access.toWrite();
  const second = access.toWrite();
  // hasPermission settles first, so the dialog is only up after a turn of the loop.
  await new Promise(resolve => setTimeout(resolve, 0));
  answer(1);
  expect(await Promise.all([first, second])).toEqual([true, true]);
  expect(mockRequestPermission.mock.calls.filter(([name]) => name === FILE_WRITE)).toHaveLength(1);
});

// The two callers do not start together: an export puts the write dialog up, and the canvas folder
// is asked for while it is still there. Its first dialog has to wait rather than appear beside it.
test('a second caller arriving partway through does not put a dialog up beside the one already open', async () => {
  // As the firmware behaves: once a dialog is answered, hasPermission answers for it from then on.
  const granted = new Set<string>();
  const answered: string[] = [];
  let answerWrite = (_grant: number) => {};
  mockHasPermission.mockImplementation(async (name: string) => (granted.has(name) ? 1 : 0));
  mockRequestPermission.mockImplementation((name: string) => {
    answered.push(name);
    if (name !== FILE_WRITE) {
      granted.add(name);
      return Promise.resolve(1);
    }
    return new Promise(resolve => {
      answerWrite = grant => {
        granted.add(name);
        resolve(grant);
      };
    });
  });
  const access = createFileAccess(createRecordingLogger());

  const exporting = access.toWrite();
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(answered).toEqual([FILE_WRITE]);

  const canvases = access.forCanvases();
  await new Promise(resolve => setTimeout(resolve, 0));
  // Still only the write dialog: read waits for it rather than opening alongside.
  expect(answered).toEqual([FILE_WRITE]);

  answerWrite(1);
  await Promise.all([exporting, canvases]);
  expect(answered).toEqual([FILE_WRITE, FILE_READ, FILE_DELETE]);
});

test('calls while a request is in flight share it, so each dialog shows once; a call after it asks again', async () => {
  const requestFileAccess = createFileAccess(createRecordingLogger()).forCanvases;
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockResolvedValue(0);
  expect(await Promise.all([requestFileAccess(), requestFileAccess()])).toEqual([false, false]);
  expect(mockRequestPermission).toHaveBeenCalledTimes(3);
  await requestFileAccess();
  expect(mockRequestPermission).toHaveBeenCalledTimes(6);
});
