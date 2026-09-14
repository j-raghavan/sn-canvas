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
  expect(await createFileAccess(logger)()).toBe(true);
  expect(mockRequestPermission.mock.calls.map(([name]) => name)).toEqual([FILE_READ, FILE_WRITE, FILE_DELETE]);
  expect(logger.lines).toContain(`log [SUPERCANVAS][PERM] ${FILE_WRITE} -> 2`);
});

test('does not ask for what is granted already', async () => {
  mockHasPermission.mockResolvedValue(1);
  expect(await createFileAccess(createRecordingLogger())()).toBe(true);
  expect(mockRequestPermission).not.toHaveBeenCalled();
});

test('is false when write or delete is refused, a request that fails counting as refused', async () => {
  const logger = createRecordingLogger();
  const requestFileAccess = createFileAccess(logger);
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
  expect(logger.lines).toContain(`warn [SUPERCANVAS][PERM] ${FILE_WRITE} failed: Error: This permission has not been declared.`);
});

test('calls while a request is in flight share it, so each dialog shows once; a call after it asks again', async () => {
  const requestFileAccess = createFileAccess(createRecordingLogger());
  mockHasPermission.mockResolvedValue(0);
  mockRequestPermission.mockResolvedValue(0);
  expect(await Promise.all([requestFileAccess(), requestFileAccess()])).toEqual([false, false]);
  expect(mockRequestPermission).toHaveBeenCalledTimes(3);
  await requestFileAccess();
  expect(mockRequestPermission).toHaveBeenCalledTimes(6);
});
