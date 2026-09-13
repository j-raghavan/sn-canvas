/**
 * The composition root: a built session drives the real adapters (here over
 * mocked host and native modules), and button presses reach it by id.
 */
type ButtonListenerShape = {onButtonPress: (event: {id: number}) => void};

const mockListeners: ButtonListenerShape[] = [];
const mockLoadCanvas = jest.fn().mockResolvedValue(true);
const mockGenerateThumbnail = jest.fn().mockResolvedValue(true);
const mockInsertImage = jest.fn().mockResolvedValue({success: true, result: true});

jest.mock('react-native', () => ({
  NativeModules: {
    SuperCanvasModule: {
      loadCanvas: (path: string) => mockLoadCanvas(path),
      saveCanvas: jest.fn().mockResolvedValue(true),
      deleteCanvas: jest.fn().mockResolvedValue(true),
      generateThumbnail: (path: string) => mockGenerateThumbnail(path),
    },
  },
}));

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    getPluginDirPath: jest.fn().mockResolvedValue('/plugin'),
    registerButtonListener: (listener: ButtonListenerShape) => mockListeners.push(listener),
  },
  PluginCommAPI: {getLassoElements: jest.fn()},
  PluginNoteAPI: {insertImage: (path: string) => mockInsertImage(path)},
}));

import {buildCanvasSession, hostButtonEvents} from '../src/wiring';
import {installPluginRouter} from '../src/infrastructure/pluginRouter';

test('a built session loads through the native module and logs what it opened', async () => {
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    await buildCanvasSession().open(null);
    expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/SuperCanvas/default.json');
    expect(warn).toHaveBeenCalledWith('[SUPERCANVAS] button=null opened canvas=default');
  } finally {
    warn.mockRestore();
  }
});

test('Save to Note links the scratch canvas under a freshly minted id', async () => {
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    const session = buildCanvasSession();
    await session.open(null);
    await session.saveToNote();
    expect(session.currentCanvasId()).toMatch(/^c-[a-z0-9]+-[a-z0-9]{4}$/);
    expect(mockInsertImage).toHaveBeenCalledWith(`/plugin/SuperCanvas/thumbnails/${session.currentCanvasId()}.png`);
  } finally {
    warn.mockRestore();
  }
});

test('button presses reach the screen by id, and the last one is remembered', () => {
  const log = jest.spyOn(console, 'log').mockImplementation(() => {});
  try {
    installPluginRouter();
    expect(hostButtonEvents.lastButtonId()).toBeNull();
    const listener = jest.fn();
    const unsubscribe = hostButtonEvents.onButton(listener);
    mockListeners[0].onButtonPress({id: 501});
    expect(listener).toHaveBeenCalledWith(501);
    expect(hostButtonEvents.lastButtonId()).toBe(501);
    unsubscribe();
  } finally {
    log.mockRestore();
  }
});

test('warnings and errors reach the console', async () => {
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  const error = jest.spyOn(console, 'error').mockImplementation(() => {});
  const {NativeModules} = jest.requireMock('react-native');
  const {PluginManager} = jest.requireMock('sn-plugin-lib');
  const native = NativeModules.SuperCanvasModule;
  try {
    delete NativeModules.SuperCanvasModule;
    await buildCanvasSession().open(null);
    expect(error).toHaveBeenCalledWith(expect.stringContaining('NativeModules.SuperCanvasModule is missing'));
    PluginManager.getPluginDirPath.mockResolvedValueOnce(null);
    await buildCanvasSession().open(null);
    expect(warn).toHaveBeenCalledWith('[SUPERCANVAS] no plugin directory; canvas not loaded or saved');
  } finally {
    NativeModules.SuperCanvasModule = native;
    warn.mockRestore();
    error.mockRestore();
  }
});
