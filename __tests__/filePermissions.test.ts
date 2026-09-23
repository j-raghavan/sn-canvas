/**
 * Canvas's file permissions, asked for by purpose: keeping canvases needs read,
 * write and delete, an export only write (#17). Each is asked for only when not
 * granted yet, dialogs go up one at a time, and everyone wanting a permission
 * already being asked for waits on that dialog rather than opening another.
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

/**
 * A firmware that remembers what it was asked, and holds the write dialog open until the test
 * answers it. [isUp] settles when that dialog is really on screen, so nothing waits on a clock:
 * a slow hasPermission would make a timing-based test hang rather than fail.
 */
const firmwareHoldingTheWriteDialog = () => {
  const granted = new Set<string>();
  const asked: string[] = [];
  let answerWrite = (_grant: number) => {};
  let dialogIsUp = () => {};
  const isUp = new Promise<void>(resolve => (dialogIsUp = resolve));
  mockHasPermission.mockImplementation(async (name: string) => (granted.has(name) ? 1 : 0));
  mockRequestPermission.mockImplementation((name: string) => {
    asked.push(name);
    if (name !== FILE_WRITE) {
      granted.add(name);
      return Promise.resolve(1);
    }
    return new Promise<number>(resolve => {
      answerWrite = grant => {
        granted.add(name);
        resolve(grant);
      };
      dialogIsUp();
    });
  });
  return {asked, isUp, answer: (grant: number) => answerWrite(grant)};
};

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
  expect(await createFileAccess(createRecordingLogger()).forExports()).toBe(true);
  expect(mockRequestPermission.mock.calls.map(([name]) => name)).toEqual([FILE_WRITE]);
});

test('an export goes ahead with write alone, whatever was said about deleting', async () => {
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockImplementation(async (name: string) => (name === FILE_WRITE ? 1 : 0));
  const access = createFileAccess(createRecordingLogger());
  expect(await access.forExports()).toBe(true);
  // The canvas folder still needs all three, so it is still refused.
  expect(await access.forCanvases()).toBe(false);
});

// Two things wanting the same permission while the dialog is up wait on that one dialog. Once it
// has been answered the firmware remembers, and hasPermission answers for it without asking again.
test('two exports while the write dialog is up wait on the one dialog', async () => {
  const firmware = firmwareHoldingTheWriteDialog();
  const access = createFileAccess(createRecordingLogger());
  const first = access.forExports();
  await firmware.isUp;
  const second = access.forExports();
  firmware.answer(1);
  expect(await Promise.all([first, second])).toEqual([true, true]);
  expect(firmware.asked.filter(name => name === FILE_WRITE)).toHaveLength(1);
});

// An export puts the write dialog up, and the canvas folder is asked for while it is still there.
// It has to share that dialog rather than open a second one for the same permission.
test('a canvas folder asked for while an export holds the write dialog shares it', async () => {
  const firmware = firmwareHoldingTheWriteDialog();
  const access = createFileAccess(createRecordingLogger());
  const exporting = access.forExports();
  await firmware.isUp;
  const canvases = access.forCanvases();
  firmware.answer(1);
  expect(await exporting).toBe(true);
  expect(await canvases).toBe(true);
  expect(firmware.asked.filter(name => name === FILE_WRITE)).toHaveLength(1);
});

// The two callers do not start together: an export puts the write dialog up, and the canvas folder
// is asked for while it is still there. Its first dialog has to wait rather than appear beside it.
test('a second caller arriving partway through does not put a dialog up beside the one already open', async () => {
  const firmware = firmwareHoldingTheWriteDialog();
  const access = createFileAccess(createRecordingLogger());

  const exporting = access.forExports();
  await firmware.isUp;
  expect(firmware.asked).toEqual([FILE_WRITE]);

  const canvases = access.forCanvases();
  await Promise.resolve();
  // Still only the write dialog: read waits for it rather than opening alongside.
  expect(firmware.asked).toEqual([FILE_WRITE]);

  firmware.answer(1);
  await Promise.all([exporting, canvases]);
  expect(firmware.asked).toEqual([FILE_WRITE, FILE_READ, FILE_DELETE]);
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
