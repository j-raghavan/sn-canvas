/**
 * The screen with an injected fake session and button source: the toolbar
 * and its toolMode prop, the action bar and style panel following the
 * canvas-state event, native command dispatch, and how taps and button
 * presses map onto the session.
 *
 * `react-native` is mocked by picking only the named exports the screen
 * uses off the real module: spreading the whole index would evaluate every
 * lazy native-backed getter (e.g. DevMenu), which throws outside a real app.
 * UIManager/findNodeHandle are wrapped in closures so they read the `mock*`
 * fns at call time; jest hoists this factory above those assignments.
 */
const mockDispatchViewManagerCommand = jest.fn();
const mockFindNodeHandle = jest.fn((_ref: unknown): number | null => 42);

jest.mock('react-native', () => {
  const actual = jest.requireActual('react-native');
  return {
    __esModule: true,
    View: actual.View,
    Text: actual.Text,
    TextInput: actual.TextInput,
    Image: actual.Image,
    Pressable: actual.Pressable,
    StyleSheet: actual.StyleSheet,
    requireNativeComponent: actual.requireNativeComponent,
    UIManager: {
      dispatchViewManagerCommand: (...args: [number, string, unknown[]]) => mockDispatchViewManagerCommand(...args),
    },
    findNodeHandle: (...args: [unknown]) => mockFindNodeHandle(...args),
  };
});

import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import type {CanvasSession} from '../src/application/canvasSession';
import CanvasScreen, {NOTICE_MS, type ButtonEventSource} from '../src/ui/CanvasScreen';
import {CanvasNativeView} from '../src/ui/nativeCanvasView';

const createFakeSession = (): jest.Mocked<CanvasSession> => ({
  open: jest.fn().mockResolvedValue(undefined),
  saveToNote: jest.fn().mockResolvedValue('inserted'),
  newCanvas: jest.fn().mockResolvedValue(undefined),
  insertImage: jest.fn().mockResolvedValue(true),
  pickNoteLink: jest.fn().mockResolvedValue({kind: 'note', target: '/storage/emulated/0/Note/plan.note', page: -1}),
  followLink: jest.fn().mockResolvedValue(true),
  exportPdf: jest.fn().mockResolvedValue('/storage/emulated/0/EXPORT/Canvas-20260914-111507.pdf'),
  close: jest.fn().mockResolvedValue(undefined),
  currentCanvasId: jest.fn(() => 'default'),
});

const createFakeButtons = (lastButtonId: number | null = null) => {
  const listeners = new Set<(buttonId: number) => void>();
  const buttons: ButtonEventSource & {press: (buttonId: number) => void; listenerCount: () => number} = {
    lastButtonId: () => lastButtonId,
    onButton: listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    press: buttonId => listeners.forEach(listener => listener(buttonId)),
    listenerCount: () => listeners.size,
  };
  return buttons;
};

const render = async (session = createFakeSession(), buttons = createFakeButtons()) => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<CanvasScreen createSession={() => session} buttonEvents={buttons} />);
  });
  const press = async (testID: string) => {
    await act(async () => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  };
  const emitCanvasState = async (payload: unknown) => {
    await act(async () => {
      renderer.root.findByType(CanvasNativeView).props.onCanvasState({nativeEvent: payload});
    });
  };
  const isPressed = (testID: string) => Boolean(renderer.root.findByProps({testID}).props.style[1]);
  const isActive = (tool: string) => isPressed(`canvas-tool-${tool}`);
  const isDisabled = (testID: string) => renderer.root.findByProps({testID}).props.disabled;
  const shows = (text: string) => renderer.root.findAllByProps({children: text}).length > 0;
  const has = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const labelled = (label: string) => renderer.root.findAllByProps({accessibilityLabel: label}).length > 0;
  return {renderer: renderer!, session, buttons, press, emitCanvasState, isActive, isPressed, isDisabled, shows, has, labelled};
};

beforeEach(() => {
  mockDispatchViewManagerCommand.mockClear();
  mockFindNodeHandle.mockReturnValue(42);
});

describe('toolbar', () => {
  test('select is the active tool at first', async () => {
    const {isActive} = await render();
    expect(['select', 'rectangle', 'ellipse', 'line', 'arrow'].map(isActive)).toEqual([true, false, false, false, false]);
  });

  test.each(['rectangle', 'ellipse', 'line', 'arrow', 'select'])(
    'tapping %s makes it the active tool and the native toolMode',
    async tool => {
      const {renderer, press, isActive} = await render();
      await press(tool === 'select' ? 'canvas-tool-rectangle' : 'canvas-tool-select');
      await press(`canvas-tool-${tool}`);
      expect(isActive(tool)).toBe(true);
      expect(renderer.root.findByType(CanvasNativeView).props.toolMode).toBe(tool);
    },
  );
});

describe('help hints', () => {
  const CAPTIONS = [
    'Export, save to note & close',
    'Colours & styles',
    'Pick a tool & start drawing!',
    'Tap again to clear the canvas',
    'Show or hide these hints',
  ];

  /** An empty canvas finished opening: what the canvas reports when it has nothing on it. */
  const openedEmpty = (emit: (payload: unknown) => Promise<void>) => emit({});

  test('show on an empty canvas, one for each control, and the help button reflects that', async () => {
    const {has, labelled, isPressed, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    expect(has('canvas-hints')).toBe(true);
    expect(CAPTIONS.map(labelled)).toEqual([true, true, true, true, true]);
    expect(isPressed('canvas-help')).toBe(true);
  });

  test('a tool press dismisses them', async () => {
    const {press, has, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    await press('canvas-tool-rectangle');
    expect(has('canvas-hints')).toBe(false);
  });

  test('inserting an image dismisses them', async () => {
    const {press, has, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    await press('canvas-insert-image');
    expect(has('canvas-hints')).toBe(false);
  });

  test('a touch on the canvas, by pen or finger, dismisses them', async () => {
    const {renderer, has, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    await act(async () => {
      renderer.root.findByType(CanvasNativeView).props.onCanvasTouch({nativeEvent: {}});
    });
    expect(has('canvas-hints')).toBe(false);
  });

  test('the help button toggles them back on, and off again', async () => {
    const {press, has, isPressed, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    await press('canvas-tool-rectangle');
    expect(has('canvas-hints')).toBe(false);
    await press('canvas-help');
    expect(has('canvas-hints')).toBe(true);
    expect(isPressed('canvas-help')).toBe(true);
    await press('canvas-help');
    expect(has('canvas-hints')).toBe(false);
    expect(isPressed('canvas-help')).toBe(false);
  });

  test('a canvas with work on it opens without them, so they never cover the drawing', async () => {
    const {has, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    expect(has('canvas-hints')).toBe(false);
  });

  test('a canvas cleared since is empty when it opens again, so they come back', async () => {
    const {has, buttons, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    expect(has('canvas-hints')).toBe(false);
    // Reopened after a Clear canvas: nothing on it, so the hints have the room again.
    await act(async () => buttons.press(500));
    await openedEmpty(emitCanvasState);
    expect(has('canvas-hints')).toBe(true);
  });

  test('a state that arrives without an open behind it leaves them as they are', async () => {
    const {has, emitCanvasState} = await render();
    await openedEmpty(emitCanvasState);
    expect(has('canvas-hints')).toBe(true);
    // A selection changing is not an open; drawing is what puts them away.
    await emitCanvasState({hasSelection: true, hasContent: true});
    expect(has('canvas-hints')).toBe(true);
  });
});

describe('action bar', () => {
  const ACTIONS = ['canvas-undo', 'canvas-redo', 'canvas-delete', 'canvas-duplicate'];

  test('its actions stay disabled until the canvas reports they apply', async () => {
    const {emitCanvasState, isDisabled} = await render();
    expect(ACTIONS.map(isDisabled)).toEqual([true, true, true, true]);
    await emitCanvasState({canUndo: true, canRedo: false, hasSelection: true});
    expect(ACTIONS.map(isDisabled)).toEqual([false, true, false, false]);
  });

  test.each([
    ['canvas-undo', 'undo'],
    ['canvas-redo', 'redo'],
    ['canvas-delete', 'deleteSelected'],
    ['canvas-duplicate', 'duplicateSelected'],
  ])('%s dispatches %s to the mounted canvas view', async (testID, command) => {
    const {press, emitCanvasState} = await render();
    await emitCanvasState({canUndo: true, canRedo: true, hasSelection: true});
    await press(testID);
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, command, []);
  });

  test('the more menu dispatches its choice', async () => {
    const {press} = await render();
    await press('canvas-more');
    await press('canvas-menu-zoomToFit');
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'zoomToFit', []);
  });

  test('commands are skipped while the native view has no handle', async () => {
    mockFindNodeHandle.mockReturnValue(null);
    const {press} = await render();
    await press('canvas-more');
    await press('canvas-menu-zoomTo100');
    expect(mockDispatchViewManagerCommand).not.toHaveBeenCalled();
  });
});

describe('style panel', () => {
  test('shows the style the canvas reports', async () => {
    const {renderer, press, emitCanvasState, shows} = await render();
    await press('style-toggle');
    await emitCanvasState({style: {color: 'red', opacity: 0.5, fill: 'semi', dash: 'dotted', size: 'xl'}});
    const redSwatch = renderer.root.find(node => node.props.testID === 'style-color-red' && typeof node.type === 'string');
    expect(redSwatch.props.accessibilityState.selected).toBe(true);
    expect(shows('Red')).toBe(true);
  });

  test('a change goes to the canvas as setStyle', async () => {
    const {press} = await render();
    await press('style-toggle');
    await press('style-size-l');
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'setStyle', ['size', 'l']);
  });

  test('swatches fall back to true colour when the canvas exports no e-ink grays', async () => {
    const {renderer, press} = await render();
    await press('style-toggle');
    expect(JSON.stringify(renderer.toJSON())).toContain('#e03131');
  });
});

describe('session', () => {
  test('opens with the press that launched the plugin', async () => {
    const {session} = await render(createFakeSession(), createFakeButtons(501));
    expect(session.open).toHaveBeenCalledWith(501);
  });

  test('opens again on every later press, and stops listening once unmounted', async () => {
    const {renderer, session, buttons} = await render();
    await act(async () => buttons.press(500));
    expect(session.open).toHaveBeenLastCalledWith(500);
    await act(async () => renderer.unmount());
    expect(buttons.listenerCount()).toBe(0);
  });

  test('is created once per mount, not on every render', async () => {
    const createSession = jest.fn(createFakeSession);
    const buttons = createFakeButtons();
    let renderer: ReactTestRenderer.ReactTestRenderer;
    await act(async () => {
      renderer = ReactTestRenderer.create(<CanvasScreen createSession={createSession} buttonEvents={buttons} />);
    });
    await act(async () => {
      renderer.update(<CanvasScreen createSession={createSession} buttonEvents={buttons} />);
    });
    expect(createSession).toHaveBeenCalledTimes(1);
  });

  test('Save to Note confirms an inserted thumbnail, and the notice then clears', async () => {
    jest.useFakeTimers();
    try {
      const {press, shows, emitCanvasState} = await render();
      await emitCanvasState({hasContent: true});
      await press('canvas-save-to-note');
      expect(shows('Added to note')).toBe(true);
      await act(async () => {
        jest.advanceTimersByTime(NOTICE_MS);
      });
      expect(shows('Added to note')).toBe(false);
    } finally {
      jest.useRealTimers();
    }
  });

  test('Save to Note shows nothing when no thumbnail was inserted', async () => {
    const session = createFakeSession();
    session.saveToNote.mockResolvedValue(null);
    const {press, shows, emitCanvasState} = await render(session);
    await emitCanvasState({hasContent: true});
    await press('canvas-save-to-note');
    expect(session.saveToNote).toHaveBeenCalledTimes(1);
    expect(shows('Added to note')).toBe(false);
  });

  test('a refreshed thumbnail says so, rather than claiming one was added', async () => {
    const session = createFakeSession();
    session.saveToNote.mockResolvedValue('refreshed');
    const {press, shows, emitCanvasState} = await render(session);
    await emitCanvasState({hasContent: true});
    await press('canvas-save-to-note');
    expect(shows('Thumbnail updated')).toBe(true);
    expect(shows('Added to note')).toBe(false);
  });

  test('Close goes to the session', async () => {
    const {session, press} = await render();
    await press('canvas-close');
    expect(session.close).toHaveBeenCalledTimes(1);
  });
});

describe('text editing', () => {
  const request = {elementId: 't', cellIndex: -1, text: 'hi', left: 1, top: 2, width: 300, height: 40, fontSize: 24, isNote: false};
  const isEditorOpen = (renderer: ReactTestRenderer.ReactTestRenderer) =>
    renderer.root.findAllByProps({testID: 'text-editor-input'}).length > 0;

  test('the editor opens where the canvas asks, and Done sends the text back and closes it', async () => {
    const {renderer, press} = await render();
    await act(async () => {
      renderer.root.findByType(CanvasNativeView).props.onEditText({nativeEvent: request});
    });
    expect(isEditorOpen(renderer)).toBe(true);
    await act(async () => {
      renderer.root.findByProps({testID: 'text-editor-input'}).props.onChangeText('hello');
    });
    await press('text-editor-done');
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'setText', ['hello']);
    expect(isEditorOpen(renderer)).toBe(false);
  });

  test('an edit event that cannot place an editor opens nothing', async () => {
    const {renderer} = await render();
    await act(async () => {
      renderer.root.findByType(CanvasNativeView).props.onEditText({nativeEvent: {elementId: ''}});
    });
    expect(isEditorOpen(renderer)).toBe(false);
  });
});

test('New canvas in the ⋮ menu asks the session for one', async () => {
  const {session, press} = await render();
  await press('canvas-more');
  await press('canvas-menu-newCanvas');
  expect(session.newCanvas).toHaveBeenCalledTimes(1);
});

describe('clear canvas', () => {
  const openEraserOptions = async (press: (testID: string) => Promise<void>) => {
    await press('canvas-tool-eraser');
    await press('canvas-tool-eraser');
  };

  test('the eraser opens its options only once it is the tool', async () => {
    const {press, has, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    await press('canvas-tool-eraser');
    expect(has('canvas-eraser-clear')).toBe(false);
    await press('canvas-tool-eraser');
    expect(has('canvas-eraser-clear')).toBe(true);
    await press('canvas-tool-eraser');
    expect(has('canvas-eraser-clear')).toBe(false);
  });

  test('picking another tool closes the options', async () => {
    const {press, has, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    await openEraserOptions(press);
    await press('canvas-tool-draw');
    expect(has('canvas-eraser-clear')).toBe(false);
  });

  test("the eraser's Clear canvas applies only to a canvas with something on it", async () => {
    const {press, isDisabled, emitCanvasState} = await render();
    await openEraserOptions(press);
    expect(isDisabled('canvas-eraser-clear')).toBe(true);
    await emitCanvasState({hasContent: true});
    expect(isDisabled('canvas-eraser-clear')).toBe(false);
  });

  test.each([
    ['the eraser', async (press: (testID: string) => Promise<void>) => openEraserOptions(press).then(() => press('canvas-eraser-clear'))],
    [
      'the ⋮ menu',
      async (press: (testID: string) => Promise<void>) => press('canvas-more').then(() => press('canvas-menu-clearCanvas')),
    ],
  ])('%s asks before clearing, and cancelling sends nothing', async (_name, open) => {
    const {press, has, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    await open(press);
    expect(has('canvas-clear-confirm')).toBe(true);
    expect(mockDispatchViewManagerCommand).not.toHaveBeenCalledWith(42, 'clearCanvas', []);
    await press('canvas-clear-cancel');
    expect(has('canvas-clear-confirm')).toBe(false);
    expect(mockDispatchViewManagerCommand).not.toHaveBeenCalledWith(42, 'clearCanvas', []);
  });

  test('confirming dispatches clearCanvas to the canvas and closes the question', async () => {
    const {press, has, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    await openEraserOptions(press);
    await press('canvas-eraser-clear');
    await press('canvas-clear-confirm-action');
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'clearCanvas', []);
    expect(has('canvas-clear-confirm')).toBe(false);
  });
});

describe('links', () => {
  test('Link to note stores what the picker named, on whatever is selected', async () => {
    const {session, press, shows, emitCanvasState} = await render();
    await emitCanvasState({hasSelection: true, hasContent: true});
    await press('canvas-more');
    await press('canvas-menu-linkToNote');
    expect(session.pickNoteLink).toHaveBeenCalledTimes(1);
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'linkSelected', [
      'note',
      '/storage/emulated/0/Note/plan.note',
      '-1',
    ]);
    expect(shows('Linked to the note')).toBe(true);
  });

  test('a cancelled picker links nothing', async () => {
    const session = createFakeSession();
    session.pickNoteLink.mockResolvedValue(null);
    const {press, shows, emitCanvasState} = await render(session);
    await emitCanvasState({hasSelection: true, hasContent: true});
    await press('canvas-more');
    await press('canvas-menu-linkToNote');
    expect(mockDispatchViewManagerCommand).not.toHaveBeenCalledWith(42, 'linkSelected', expect.anything());
    expect(shows('Linked to the note')).toBe(false);
  });

  test('a tap on a glyph follows the link the canvas reports', async () => {
    const {session, renderer} = await render();
    await act(async () => {
      renderer.root
        .findByType(CanvasNativeView)
        .props.onFollowLink({nativeEvent: {kind: 'note', target: '/n.note', page: 2}});
    });
    expect(session.followLink).toHaveBeenCalledWith({kind: 'note', target: '/n.note', page: 2});
  });

  test('a link that will not open says so, and one the bridge cannot read is ignored', async () => {
    const session = createFakeSession();
    session.followLink.mockResolvedValue(false);
    const {renderer, shows} = await render(session);
    const follow = (payload: unknown) =>
      act(async () => {
        renderer.root.findByType(CanvasNativeView).props.onFollowLink({nativeEvent: payload});
      });
    await follow({kind: 'note', target: '/n.note', page: -1});
    expect(shows('Could not open that note')).toBe(true);
    await follow({kind: 'canvas', target: ''});
    expect(session.followLink).toHaveBeenCalledTimes(1);
  });
});

describe('Export to PDF', () => {
  test('exports through the session and says where the PDF went', async () => {
    const {session, press, shows, emitCanvasState} = await render();
    await emitCanvasState({hasContent: true});
    await press('canvas-export-pdf');
    expect(session.exportPdf).toHaveBeenCalledTimes(1);
    expect(shows('Saved to EXPORT/Canvas-20260914-111507.pdf')).toBe(true);
  });

  test('says so when nothing was exported', async () => {
    const session = createFakeSession();
    session.exportPdf.mockResolvedValue(null);
    const {press, shows, emitCanvasState} = await render(session);
    await emitCanvasState({hasContent: true});
    await press('canvas-export-pdf');
    expect(shows('Could not export the PDF')).toBe(true);
  });

  test('Export to PDF and Save to Note apply only to a canvas with something on it', async () => {
    const {emitCanvasState, isDisabled} = await render();
    expect([isDisabled('canvas-export-pdf'), isDisabled('canvas-save-to-note')]).toEqual([true, true]);
    // Close is never disabled: a blank canvas still has to be closable.
    expect(isDisabled('canvas-close')).toBeFalsy();
    await emitCanvasState({hasContent: true});
    expect([isDisabled('canvas-export-pdf'), isDisabled('canvas-save-to-note')]).toEqual([false, false]);
  });
});

describe('image', () => {
  test('the image button asks the session for one, and select becomes the tool that moves it', async () => {
    const {session, press, isActive} = await render();
    await press('canvas-tool-rectangle');
    await press('canvas-insert-image');
    expect(session.insertImage).toHaveBeenCalledTimes(1);
    expect(isActive('select')).toBe(true);
  });

  test('a cancelled pick leaves the tool as it was', async () => {
    const session = createFakeSession();
    session.insertImage.mockResolvedValue(false);
    const {press, isActive} = await render(session);
    await press('canvas-tool-rectangle');
    await press('canvas-insert-image');
    expect(isActive('rectangle')).toBe(true);
  });

  test('with an image selected, the style panel offers no outline', async () => {
    const {renderer, press, emitCanvasState} = await render();
    await emitCanvasState({hasSelection: true, selectedType: 'image', style: {dash: 'none'}});
    await press('style-toggle');
    expect(renderer.root.findAllByProps({testID: 'style-dash-none'}).length).toBeGreaterThan(0);
  });
});
