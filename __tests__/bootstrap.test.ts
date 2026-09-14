/**
 * Tests for the JS-side bootstrap in index.js. Pins:
 *   1. AppRegistry registers the App component under the app.json name.
 *   2. PluginManager.init runs and the plugin router is installed once.
 *   3. The NOTE sidebar button is registered as a persistent full-screen
 *      entry point (id 500, regionType 3).
 *   4. The lasso-toolbar "Open Canvas" button is registered (id 501),
 *      scoped to image selections so it only appears on a lassoed
 *      Canvas note thumbnail.
 *   5. File access is asked for as soon as the plugin loads (at install),
 *      as in sn-shapes and sn-mindmap.
 *
 * App is mocked: this file is about host registration, not rendering
 * (CanvasScreen has its own tests).
 */
const mockRegisterComponent = jest.fn();
const mockInit = jest.fn();
const mockRegisterButton = jest.fn();
const mockRegisterButtonListener = jest.fn();
const mockHasPermission = jest.fn().mockResolvedValue(0);
const mockRequestPermission = jest.fn().mockResolvedValue(2);

jest.mock('react-native', () => ({
  AppRegistry: {
    registerComponent: (...args: unknown[]) => mockRegisterComponent(...args),
  },
  Image: {resolveAssetSource: () => ({uri: 'file:///icon.png'})},
}));

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    init: () => mockInit(),
    registerButton: (...args: unknown[]) => mockRegisterButton(...args),
    registerButtonListener: (...args: unknown[]) =>
      mockRegisterButtonListener(...args),
    hasPermission: (name: string) => mockHasPermission(name),
    requestPermission: (name: string) => mockRequestPermission(name),
  },
}));

jest.mock('../App', () => ({__esModule: true, default: () => null}));

let warn: jest.SpyInstance;

beforeAll(async () => {
  warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  require('../index');
  // The file-permission request runs asynchronously.
  await new Promise(resolve => setImmediate(resolve));
});

afterAll(() => {
  warn.mockRestore();
});

test('registers the app component under the app.json name', () => {
  expect(mockRegisterComponent).toHaveBeenCalledWith(
    'SnCanvas',
    expect.any(Function),
  );
});

test('the registered component factory returns the App component', () => {
  const factory = mockRegisterComponent.mock.calls[0][1] as () => unknown;
  expect(factory()).toBe(require('../App').default);
});

test('initialises the plugin manager and installs the button router exactly once', () => {
  expect(mockInit).toHaveBeenCalledTimes(1);
  expect(mockRegisterButtonListener).toHaveBeenCalledTimes(1);
});

test('registers the NOTE sidebar button as a persistent full-screen entry point', () => {
  expect(mockRegisterButton).toHaveBeenCalledWith(1, ['NOTE'], {
    id: 500,
    name: 'Canvas',
    icon: 'file:///icon.png',
    showType: 1,
    regionType: 3,
  });
});

test('registers the lasso "Open Canvas" button, scoped to image selections', () => {
  expect(mockRegisterButton).toHaveBeenCalledWith(2, ['NOTE'], {
    id: 501,
    name: 'Open Canvas',
    icon: 'file:///icon.png',
    showType: 1,
    editDataTypes: [2],
    regionType: 3,
  });
});

test('asks for read, write and delete access as soon as the plugin loads, as sn-shapes and sn-mindmap do', () => {
  expect(mockRequestPermission.mock.calls.map(([name]) => name)).toEqual([
    'plugin.permission.FILE:READ',
    'plugin.permission.FILE:WRITE',
    'plugin.permission.FILE:DELETE',
  ]);
});
