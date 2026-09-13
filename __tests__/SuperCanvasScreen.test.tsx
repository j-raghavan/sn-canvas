/**
 * Tests for the v1a tool palette in SuperCanvasScreen: active-tool state,
 * the toolMode prop reaching the native view, and command dispatch
 * (deleteSelected/undo/redo) via UIManager.dispatchViewManagerCommand.
 *
 * `react-native` is mocked by picking only the specific named exports this
 * screen actually uses off the real module, rather than spreading the whole
 * thing — spreading/enumerating the real `react-native` index object
 * eagerly evaluates every one of its lazy `get x() { return require(...) }`
 * exports, including native-module-backed ones (e.g. `DevMenu`) that throw
 * outside a real app, and re-`requireActual`-ing the real `UIManager`
 * submodule directly hits a similar problem in its own top-level
 * `getConstants()` call. Picking named properties off `actual` one at a
 * time (each triggering only that one getter) avoids both.
 *
 * `UIManager.dispatchViewManagerCommand`/`findNodeHandle` are wrapped in
 * closures that dereference the outer `mock*` jest.fn()s at *call* time
 * rather than embedded directly as the export values: babel-plugin-jest-hoist
 * hoists this factory (and the require() behind `import ... from 'react-native'`)
 * above the `const mock... = jest.fn()` assignments below, so a factory that
 * reads `mockFindNodeHandle`'s value immediately (as the export itself)
 * captures it before that assignment has run — reproducibly `undefined` at
 * both module-load and call time, confirmed by isolated repro. A closure
 * only reads the variable when actually invoked, by which point the
 * assignment has long since executed.
 */
const mockDispatchViewManagerCommand = jest.fn();
const mockFindNodeHandle = jest.fn((_ref: unknown): number | null => 42);
const mockLoadCanvas = jest.fn().mockResolvedValue(true);
const mockSaveCanvas = jest.fn().mockResolvedValue(true);
const mockGenerateThumbnail = jest.fn().mockResolvedValue(true);
const mockGetPluginDirPath = jest.fn().mockResolvedValue('/plugin/dir');
const mockClosePluginView = jest.fn().mockResolvedValue(true);
// v1d (FR12/FR13) mocks — same envelope shape sn-plugin-lib actually uses:
// {success, result, error?}, per BUILD-FACTS.md's documented API facts.
const mockGetLastButtonEvent = jest.fn((): {id: number} | null => null);
const mockGetLassoElements = jest.fn().mockResolvedValue({success: true, result: []});
const mockInsertImage = jest.fn().mockResolvedValue({success: true, result: true});
const mockGetLastElement = jest.fn().mockResolvedValue({success: true, result: {id: 'el-1', userData: ''}});
const mockGetCurrentFilePath = jest.fn().mockResolvedValue({success: true, result: '/note/test.note'});
const mockGetCurrentPageNum = jest.fn().mockResolvedValue({success: true, result: 0});
const mockModifyElements = jest.fn().mockResolvedValue({success: true, result: [0]});

jest.mock('react-native', () => {
  const actual = jest.requireActual('react-native');
  return {
    __esModule: true,
    View: actual.View,
    Text: actual.Text,
    Image: actual.Image,
    Pressable: actual.Pressable,
    StyleSheet: actual.StyleSheet,
    requireNativeComponent: actual.requireNativeComponent,
    UIManager: {
      dispatchViewManagerCommand: (...args: [number, string, unknown[]]) =>
        mockDispatchViewManagerCommand(...args),
    },
    findNodeHandle: (...args: [unknown]) => mockFindNodeHandle(...args),
    NativeModules: {
      SuperCanvasModule: {
        loadCanvas: (...args: [string]) => mockLoadCanvas(...args),
        saveCanvas: (...args: [string]) => mockSaveCanvas(...args),
        generateThumbnail: (...args: [string]) => mockGenerateThumbnail(...args),
      },
    },
  };
});

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    closePluginView: (...args: []) => mockClosePluginView(...args),
    getPluginDirPath: (...args: []) => mockGetPluginDirPath(...args),
  },
  PluginCommAPI: {
    getLassoElements: (...args: []) => mockGetLassoElements(...args),
    getCurrentFilePath: (...args: []) => mockGetCurrentFilePath(...args),
    getCurrentPageNum: (...args: []) => mockGetCurrentPageNum(...args),
  },
  PluginNoteAPI: {
    insertImage: (...args: [string]) => mockInsertImage(...args),
  },
  PluginFileAPI: {
    getLastElement: (...args: []) => mockGetLastElement(...args),
    modifyElements: (...args: [string, number, unknown[]]) => mockModifyElements(...args),
  },
}));

jest.mock('../src/pluginRouter', () => ({
  getLastButtonEvent: () => mockGetLastButtonEvent(),
}));

import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import SuperCanvasScreen from '../src/SuperCanvasScreen';

beforeEach(() => {
  mockDispatchViewManagerCommand.mockClear();
  mockFindNodeHandle.mockClear();
  mockFindNodeHandle.mockReturnValue(42);
  mockLoadCanvas.mockClear();
  mockLoadCanvas.mockResolvedValue(true);
  mockSaveCanvas.mockClear();
  mockSaveCanvas.mockResolvedValue(true);
  mockGetPluginDirPath.mockClear();
  mockGetPluginDirPath.mockResolvedValue('/plugin/dir');
  mockClosePluginView.mockClear();
  mockClosePluginView.mockResolvedValue(true);
  mockGenerateThumbnail.mockClear();
  mockGenerateThumbnail.mockResolvedValue(true);
  mockGetLastButtonEvent.mockClear();
  mockGetLastButtonEvent.mockReturnValue(null);
  mockGetLassoElements.mockClear();
  mockGetLassoElements.mockResolvedValue({success: true, result: []});
  mockInsertImage.mockClear();
  mockInsertImage.mockResolvedValue({success: true, result: true});
  mockGetLastElement.mockClear();
  mockGetLastElement.mockResolvedValue({success: true, result: {id: 'el-1', userData: ''}});
  mockGetCurrentFilePath.mockClear();
  mockGetCurrentFilePath.mockResolvedValue({success: true, result: '/note/test.note'});
  mockGetCurrentPageNum.mockClear();
  mockGetCurrentPageNum.mockResolvedValue({success: true, result: 0});
  mockModifyElements.mockClear();
  mockModifyElements.mockResolvedValue({success: true, result: [0]});
});

test('select tool is active by default, others are not', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  const selectButton = renderer!.root.findByProps({testID: 'supercanvas-tool-select'});
  const rectButton = renderer!.root.findByProps({testID: 'supercanvas-tool-rectangle'});
  const ellipseButton = renderer!.root.findByProps({testID: 'supercanvas-tool-ellipse'});
  expect(selectButton.props.style[1]).toBeTruthy();
  expect(rectButton.props.style[1]).toBeFalsy();
  expect(ellipseButton.props.style[1]).toBeFalsy();
});

test('tapping a tool button switches the active tool and the toolMode prop reaching the native view', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  const rectButton = renderer!.root.findByProps({testID: 'supercanvas-tool-rectangle'});
  await act(async () => {
    rectButton.props.onPress();
  });
  expect(renderer!.root.findByProps({testID: 'supercanvas-tool-rectangle'}).props.style[1]).toBeTruthy();
  expect(renderer!.root.findByProps({testID: 'supercanvas-tool-select'}).props.style[1]).toBeFalsy();
  expect(renderer!.root.findByProps({toolMode: 'rectangle'})).toBeDefined();
});

test('v1b Line/Arrow tool buttons switch the active tool and reach the native view', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });

  const lineButton = renderer!.root.findByProps({testID: 'supercanvas-tool-line'});
  await act(async () => {
    lineButton.props.onPress();
  });
  expect(renderer!.root.findByProps({testID: 'supercanvas-tool-line'}).props.style[1]).toBeTruthy();
  expect(renderer!.root.findByProps({toolMode: 'line'})).toBeDefined();

  const arrowButton = renderer!.root.findByProps({testID: 'supercanvas-tool-arrow'});
  await act(async () => {
    arrowButton.props.onPress();
  });
  expect(renderer!.root.findByProps({testID: 'supercanvas-tool-arrow'}).props.style[1]).toBeTruthy();
  expect(renderer!.root.findByProps({testID: 'supercanvas-tool-line'}).props.style[1]).toBeFalsy();
  expect(renderer!.root.findByProps({toolMode: 'arrow'})).toBeDefined();
});

test('Delete/Undo/Redo dispatch the matching native command with the mounted view handle', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });

  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-delete'}).props.onPress();
  });
  expect(mockDispatchViewManagerCommand).toHaveBeenLastCalledWith(42, 'deleteSelected', []);

  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-undo'}).props.onPress();
  });
  expect(mockDispatchViewManagerCommand).toHaveBeenLastCalledWith(42, 'undo', []);

  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-redo'}).props.onPress();
  });
  expect(mockDispatchViewManagerCommand).toHaveBeenLastCalledWith(42, 'redo', []);
});

test('command dispatch is skipped when the native view handle is unavailable', async () => {
  mockFindNodeHandle.mockReturnValue(null);
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-undo'}).props.onPress();
  });
  expect(mockDispatchViewManagerCommand).not.toHaveBeenCalled();
});

// --- v1c: persistence (load-on-mount, save-on-close) ------------------------------

test('loads the canvas from the plugin-private JSON file on mount', async () => {
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  expect(mockGetPluginDirPath).toHaveBeenCalled();
  expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/default.json');
});

test('unmounting before the plugin dir resolves skips the load', async () => {
  let resolveDir: (dir: string) => void = () => {};
  mockGetPluginDirPath.mockReturnValue(
    new Promise<string>(resolve => {
      resolveDir = resolve;
    }),
  );
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.unmount();
  });
  await act(async () => {
    resolveDir('/plugin/dir');
  });
  expect(mockLoadCanvas).not.toHaveBeenCalled();
});

test('does not attempt to load when getPluginDirPath resolves null (first run / no plugin dir yet)', async () => {
  mockGetPluginDirPath.mockResolvedValue(null);
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  expect(mockLoadCanvas).not.toHaveBeenCalled();
});

test('a failed load does not crash mount — an empty/fresh canvas is an acceptable fallback', async () => {
  mockLoadCanvas.mockRejectedValue(new Error('boom'));
  await expect(
    act(async () => {
      ReactTestRenderer.create(<SuperCanvasScreen />);
    }),
  ).resolves.not.toThrow();
});

test('saves the canvas to the same resolved path before closing the plugin view', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-close'}).props.onPress();
  });
  expect(mockSaveCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/default.json');
  expect(mockClosePluginView).toHaveBeenCalled();
});

test('still closes the plugin view even if saving fails', async () => {
  mockSaveCanvas.mockRejectedValue(new Error('disk full'));
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-close'}).props.onPress();
  });
  expect(mockClosePluginView).toHaveBeenCalled();
});

test('closes the plugin view without attempting to save when no path was ever resolved', async () => {
  mockGetPluginDirPath.mockResolvedValue(null);
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-close'}).props.onPress();
  });
  expect(mockSaveCanvas).not.toHaveBeenCalled();
  expect(mockClosePluginView).toHaveBeenCalled();
});

// --- v1d: canvasId resolution (FR13, opened via the lasso reopen button) ---------

test('loads the default canvas when opened via the normal (id 500) sidebar button', async () => {
  mockGetLastButtonEvent.mockReturnValue({id: 500});
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  expect(mockGetLassoElements).not.toHaveBeenCalled();
  expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/default.json');
});

test('resolves canvasId from a lassoed thumbnail\'s userData when opened via the reopen (id 501) button', async () => {
  mockGetLastButtonEvent.mockReturnValue({id: 501});
  mockGetLassoElements.mockResolvedValue({
    success: true,
    result: [{id: 'pic-1', userData: JSON.stringify({snSuperCanvasId: 'notebook-42'})}],
  });
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/notebook-42.json');
});

test('falls back to the default canvas when opened via id 501 but no lassoed element has valid userData', async () => {
  mockGetLastButtonEvent.mockReturnValue({id: 501});
  mockGetLassoElements.mockResolvedValue({success: true, result: [{id: 'pic-1', userData: 'not json'}]});
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/default.json');
});

test('falls back to the default canvas when getLassoElements itself rejects', async () => {
  mockGetLastButtonEvent.mockReturnValue({id: 501});
  mockGetLassoElements.mockRejectedValue(new Error('no active lasso'));
  await act(async () => {
    ReactTestRenderer.create(<SuperCanvasScreen />);
    // A rejected (vs resolved) awaited promise needs one extra microtask
    // tick to drain here — plain `await act(async () => { create() })` is
    // enough for the resolving-lasso case above, but not this one.
    await Promise.resolve();
    await Promise.resolve();
  });
  expect(mockLoadCanvas).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/default.json');
});

// --- v1d: "Save to Note" (FR12) ----------------------------------------------------

test('Save to Note generates a thumbnail, inserts it, and stamps userData onto the inserted element', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-save-to-note'}).props.onPress();
  });
  expect(mockGenerateThumbnail).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/thumbnails/default.png');
  expect(mockInsertImage).toHaveBeenCalledWith('/plugin/dir/SuperCanvas/thumbnails/default.png');
  expect(mockModifyElements).toHaveBeenCalledWith('/note/test.note', 0, [
    {id: 'el-1', userData: JSON.stringify({snSuperCanvasId: 'default'})},
  ]);
});

test('Save to Note is a no-op that does not throw when getPluginDirPath resolves null', async () => {
  mockGetPluginDirPath.mockResolvedValue(null);
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-save-to-note'}).props.onPress();
  });
  expect(mockGenerateThumbnail).not.toHaveBeenCalled();
});

test('Save to Note stops short of modifyElements when no last element is returned', async () => {
  mockGetLastElement.mockResolvedValue({success: true, result: null});
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-save-to-note'}).props.onPress();
  });
  expect(mockModifyElements).not.toHaveBeenCalled();
});

test('Save to Note stops short of modifyElements when the note path/page cannot be resolved', async () => {
  mockGetCurrentFilePath.mockResolvedValue({success: false, result: null});
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-save-to-note'}).props.onPress();
  });
  expect(mockModifyElements).not.toHaveBeenCalled();
});

test('a failed Save to Note (e.g. generateThumbnail rejects) does not throw or crash', async () => {
  mockGenerateThumbnail.mockRejectedValue(new Error('disk full'));
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<SuperCanvasScreen />);
  });
  await expect(
    act(async () => {
      renderer!.root.findByProps({testID: 'supercanvas-save-to-note'}).props.onPress();
    }),
  ).resolves.not.toThrow();
  expect(mockInsertImage).not.toHaveBeenCalled();
});
