/**
 * The sn-plugin-lib adapter unwraps {success, result} envelopes into plain
 * values and never rejects: every SDK failure becomes a logged fallback.
 */
const mockGetPluginDirPath = jest.fn();
const mockClosePluginView = jest.fn();
const mockGetLassoElements = jest.fn();
const mockInsertImage = jest.fn();
const mockGetLastElement = jest.fn();

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    getPluginDirPath: () => mockGetPluginDirPath(),
    closePluginView: () => mockClosePluginView(),
  },
  PluginCommAPI: {getLassoElements: () => mockGetLassoElements()},
  PluginNoteAPI: {insertImage: (path: string) => mockInsertImage(path)},
  PluginFileAPI: {getLastElement: () => mockGetLastElement()},
}));

import {createHostSdk} from '../src/infrastructure/hostSdk';
import {createRecordingLogger} from './helpers/fakePorts';

const failure = () => Promise.reject(new Error('host said no'));

test('pluginDir is the host path, or null when it has none or the call fails', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger);
  mockGetPluginDirPath.mockResolvedValueOnce('/plugin');
  expect(await host.pluginDir()).toBe('/plugin');
  mockGetPluginDirPath.mockResolvedValueOnce('');
  expect(await host.pluginDir()).toBeNull();
  mockGetPluginDirPath.mockImplementationOnce(failure);
  expect(await host.pluginDir()).toBeNull();
  expect(logger.lines).toEqual(['warn [SUPERCANVAS] getPluginDirPath failed: Error: host said no']);
});

test('lassoedElements unwraps the element list, and is empty for anything else', async () => {
  const host = createHostSdk(createRecordingLogger());
  mockGetLassoElements.mockResolvedValueOnce({success: true, result: [{id: 1}]});
  expect(await host.lassoedElements()).toEqual([{id: 1}]);
  mockGetLassoElements.mockResolvedValueOnce({success: false, error: {code: 1, message: 'no lasso'}});
  expect(await host.lassoedElements()).toEqual([]);
  mockGetLassoElements.mockResolvedValueOnce({success: true, result: 'not a list'});
  expect(await host.lassoedElements()).toEqual([]);
  mockGetLassoElements.mockImplementationOnce(failure);
  expect(await host.lassoedElements()).toEqual([]);
});

test('insertImage is true only when the host reports success', async () => {
  const host = createHostSdk(createRecordingLogger());
  mockInsertImage.mockResolvedValueOnce({success: true, result: true});
  expect(await host.insertImage('/t.png')).toBe(true);
  expect(mockInsertImage).toHaveBeenCalledWith('/t.png');
  mockInsertImage.mockResolvedValueOnce({success: false});
  expect(await host.insertImage('/t.png')).toBe(false);
  mockInsertImage.mockResolvedValueOnce(null);
  expect(await host.insertImage('/t.png')).toBe(false);
  mockInsertImage.mockImplementationOnce(failure);
  expect(await host.insertImage('/t.png')).toBe(false);
});

test('lastElementUuid is the uuid of the last element on the page, or null when there is none to read', async () => {
  const host = createHostSdk(createRecordingLogger());
  mockGetLastElement.mockResolvedValueOnce({success: true, result: {uuid: 'u-1', type: 200}});
  expect(await host.lastElementUuid()).toBe('u-1');
  mockGetLastElement.mockResolvedValueOnce({success: true, result: {}});
  expect(await host.lastElementUuid()).toBeNull();
  mockGetLastElement.mockResolvedValueOnce({success: false});
  expect(await host.lastElementUuid()).toBeNull();
  mockGetLastElement.mockImplementationOnce(failure);
  expect(await host.lastElementUuid()).toBeNull();
});

test('closeView closes the plugin view, and a failure to close is only logged', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger);
  mockClosePluginView.mockImplementationOnce(failure);
  host.closeView();
  await new Promise(resolve => setImmediate(resolve));
  expect(mockClosePluginView).toHaveBeenCalledTimes(1);
  expect(logger.lines).toEqual(['warn [SUPERCANVAS] closePluginView failed: Error: host said no']);
});
