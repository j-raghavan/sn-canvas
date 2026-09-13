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
import SuperCanvasScreen, {NOTICE_MS, type ButtonEventSource} from '../src/ui/SuperCanvasScreen';
import {SuperCanvasNativeView} from '../src/ui/nativeCanvasView';

const createFakeSession = (): jest.Mocked<CanvasSession> => ({
  open: jest.fn().mockResolvedValue(undefined),
  saveToNote: jest.fn().mockResolvedValue(true),
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
    renderer = ReactTestRenderer.create(<SuperCanvasScreen createSession={() => session} buttonEvents={buttons} />);
  });
  const press = async (testID: string) => {
    await act(async () => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  };
  const emitCanvasState = async (payload: unknown) => {
    await act(async () => {
      renderer.root.findByType(SuperCanvasNativeView).props.onCanvasState({nativeEvent: payload});
    });
  };
  const isActive = (tool: string) => Boolean(renderer.root.findByProps({testID: `supercanvas-tool-${tool}`}).props.style[1]);
  const isDisabled = (testID: string) => renderer.root.findByProps({testID}).props.disabled;
  const shows = (text: string) => renderer.root.findAllByProps({children: text}).length > 0;
  return {renderer: renderer!, session, buttons, press, emitCanvasState, isActive, isDisabled, shows};
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
      await press(tool === 'select' ? 'supercanvas-tool-rectangle' : 'supercanvas-tool-select');
      await press(`supercanvas-tool-${tool}`);
      expect(isActive(tool)).toBe(true);
      expect(renderer.root.findByType(SuperCanvasNativeView).props.toolMode).toBe(tool);
    },
  );
});

describe('action bar', () => {
  const ACTIONS = ['supercanvas-undo', 'supercanvas-redo', 'supercanvas-delete', 'supercanvas-duplicate'];

  test('its actions stay disabled until the canvas reports they apply', async () => {
    const {emitCanvasState, isDisabled} = await render();
    expect(ACTIONS.map(isDisabled)).toEqual([true, true, true, true]);
    await emitCanvasState({canUndo: true, canRedo: false, hasSelection: true});
    expect(ACTIONS.map(isDisabled)).toEqual([false, true, false, false]);
  });

  test.each([
    ['supercanvas-undo', 'undo'],
    ['supercanvas-redo', 'redo'],
    ['supercanvas-delete', 'deleteSelected'],
    ['supercanvas-duplicate', 'duplicateSelected'],
  ])('%s dispatches %s to the mounted canvas view', async (testID, command) => {
    const {press, emitCanvasState} = await render();
    await emitCanvasState({canUndo: true, canRedo: true, hasSelection: true});
    await press(testID);
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, command, []);
  });

  test('the more menu dispatches its choice', async () => {
    const {press} = await render();
    await press('supercanvas-more');
    await press('supercanvas-menu-zoomToFit');
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, 'zoomToFit', []);
  });

  test('commands are skipped while the native view has no handle', async () => {
    mockFindNodeHandle.mockReturnValue(null);
    const {press} = await render();
    await press('supercanvas-more');
    await press('supercanvas-menu-zoomTo100');
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
      renderer = ReactTestRenderer.create(<SuperCanvasScreen createSession={createSession} buttonEvents={buttons} />);
    });
    await act(async () => {
      renderer.update(<SuperCanvasScreen createSession={createSession} buttonEvents={buttons} />);
    });
    expect(createSession).toHaveBeenCalledTimes(1);
  });

  test('Save to Note confirms an inserted thumbnail, and the notice then clears', async () => {
    jest.useFakeTimers();
    try {
      const {press, shows} = await render();
      await press('supercanvas-save-to-note');
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
    session.saveToNote.mockResolvedValue(false);
    const {press, shows} = await render(session);
    await press('supercanvas-save-to-note');
    expect(session.saveToNote).toHaveBeenCalledTimes(1);
    expect(shows('Added to note')).toBe(false);
  });

  test('Close goes to the session', async () => {
    const {session, press} = await render();
    await press('supercanvas-close');
    expect(session.close).toHaveBeenCalledTimes(1);
  });
});
