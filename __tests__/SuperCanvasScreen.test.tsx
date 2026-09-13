/**
 * The screen with an injected fake session and button source: the tool
 * palette and its toolMode prop, native command dispatch, and how taps and
 * button presses map onto the session.
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
import SuperCanvasScreen, {type ButtonEventSource} from '../src/ui/SuperCanvasScreen';
import {SuperCanvasNativeView} from '../src/ui/nativeCanvasView';

const createFakeSession = (): jest.Mocked<CanvasSession> => ({
  open: jest.fn().mockResolvedValue(undefined),
  saveToNote: jest.fn().mockResolvedValue(undefined),
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
  const isActive = (tool: string) => Boolean(renderer.root.findByProps({testID: `supercanvas-tool-${tool}`}).props.style[1]);
  return {renderer: renderer!, session, buttons, press, isActive};
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

  test.each([
    ['supercanvas-delete', 'deleteSelected'],
    ['supercanvas-undo', 'undo'],
    ['supercanvas-redo', 'redo'],
  ])('%s dispatches %s to the mounted canvas view', async (testID, command) => {
    const {press} = await render();
    await press(testID);
    expect(mockDispatchViewManagerCommand).toHaveBeenCalledWith(42, command, []);
  });

  test('commands are skipped while the native view has no handle', async () => {
    mockFindNodeHandle.mockReturnValue(null);
    const {press} = await render();
    await press('supercanvas-undo');
    expect(mockDispatchViewManagerCommand).not.toHaveBeenCalled();
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

  test('Save to Note and Close go to the session', async () => {
    const {session, press} = await render();
    await press('supercanvas-save-to-note');
    await press('supercanvas-close');
    expect(session.saveToNote).toHaveBeenCalledTimes(1);
    expect(session.close).toHaveBeenCalledTimes(1);
  });
});
