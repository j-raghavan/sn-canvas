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
    CanvasModule: {
      loadCanvas: (path: string) => mockLoadCanvas(path),
      saveCanvas: jest.fn().mockResolvedValue(true),
      deleteCanvas: jest.fn().mockResolvedValue(true),
      generateThumbnail: (path: string) => mockGenerateThumbnail(path),
      readText: jest.fn().mockResolvedValue(null),
      writeText: jest.fn().mockResolvedValue(true),
      listCanvasFiles: jest.fn().mockResolvedValue([]),
      adoptFolder: jest.fn().mockResolvedValue(0),
      setNotePen: jest.fn().mockResolvedValue(true),
    },
  },
}));

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    getPluginDirPath: jest.fn().mockResolvedValue('/plugin'),
    registerButtonListener: (listener: ButtonListenerShape) => mockListeners.push(listener),
    hasPermission: jest.fn().mockResolvedValue(1),
    requestPermission: jest.fn().mockResolvedValue(0),
  },
  PluginCommAPI: {
    getPenInfo: jest.fn().mockResolvedValue({success: true, result: {type: 10, width: 500, color: 0}}),
    getLassoElements: jest.fn(),
    getCurrentFilePath: jest.fn().mockResolvedValue({success: true, result: '/note.note'}),
    getCurrentPageNum: jest.fn().mockResolvedValue({success: true, result: 0}),
  },
  PluginNoteAPI: {insertImage: (path: string) => mockInsertImage(path)},
  PluginFileAPI: {getElements: jest.fn().mockResolvedValue({success: true, result: []})},
}));

import {buildCanvasSession, hostButtonEvents} from '../src/wiring';
import {installPluginRouter} from '../src/infrastructure/pluginRouter';

test('a built session loads through the native module and logs what it opened', async () => {
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    await buildCanvasSession().open(null);
    // No marker in the plugin folder yet: the first open since install starts a new canvas.
    expect(mockLoadCanvas).toHaveBeenCalledWith(
      expect.stringMatching(/^\/storage\/emulated\/0\/MyStyle\/SnCanvas\/c-[a-z0-9]+-[a-z0-9]{4}\.json$/),
    );
    expect(warn).toHaveBeenCalledWith(expect.stringMatching(/^\[SNCANVAS\] button=null opened canvas=c-/));
  } finally {
    warn.mockRestore();
  }
});

test('Save to Note links the canvas under a minted id', async () => {
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    const session = buildCanvasSession();
    await session.open(null);
    await session.saveToNote();
    expect(session.currentCanvasId()).toMatch(/^c-[a-z0-9]+-[a-z0-9]{4}$/);
    expect(mockInsertImage).toHaveBeenCalledWith(
      `/storage/emulated/0/MyStyle/SnCanvas/thumbnails/${session.currentCanvasId()}.png`,
    );
    // The pending link is left after the insert, queued behind it: the next operation waits for it.
    await session.close();
    expect(warn).toHaveBeenCalledWith(`[SNCANVAS][LINK] pending link for canvas=${session.currentCanvasId()} page=0 knownPictures=0`);
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
  const native = NativeModules.CanvasModule;
  try {
    delete NativeModules.CanvasModule;
    await buildCanvasSession().open(null);
    expect(error).toHaveBeenCalledWith(expect.stringContaining('NativeModules.CanvasModule is missing'));
    // Without write access canvases would go in the plugin folder; with no plugin folder either, nothing loads.
    PluginManager.hasPermission.mockResolvedValue(0);
    PluginManager.getPluginDirPath.mockResolvedValueOnce(null);
    await buildCanvasSession().open(null);
    expect(warn).toHaveBeenCalledWith('[SNCANVAS] no plugin directory; canvas not loaded or saved');
  } finally {
    NativeModules.CanvasModule = native;
    PluginManager.hasPermission.mockResolvedValue(1);
    warn.mockRestore();
    error.mockRestore();
  }
});
