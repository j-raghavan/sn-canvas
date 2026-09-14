/**
 * Tests for src/infrastructure/pluginRouter. Validate that:
 *   - installPluginRouter registers exactly one PluginManager listener
 *     regardless of how many times it's called (idempotent).
 *   - onButtonPress updates getLastButtonEvent and fans out to every active
 *     subscriber.
 *   - Subscribers can unsubscribe via the returned handle.
 *   - A throwing subscriber doesn't block other subscribers from being
 *     invoked (one badly-written consumer shouldn't silently eat events for
 *     everyone else).
 *
 * Adapted from sn-shapes' __tests__/pluginRouter.test.ts.
 */
type ButtonListenerShape = {
  onButtonPress: (event: unknown) => void;
};

const registeredListeners: ButtonListenerShape[] = [];

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    registerButtonListener: jest.fn((listener: ButtonListenerShape) => {
      registeredListeners.push(listener);
      return {id: registeredListeners.length - 1, listener, remove: jest.fn()};
    }),
  },
}));

import {
  installPluginRouter,
  subscribeToButtonEvents,
  getLastButtonEvent,
  __testing__,
} from '../src/infrastructure/pluginRouter';

const press = (id: number) => ({id, pressEvent: 3, name: 'Canvas', icon: '', color: 0, bgColor: 0});

let log: jest.SpyInstance;
beforeEach(() => {
  registeredListeners.length = 0;
  __testing__.reset();
  log = jest.spyOn(console, 'log').mockImplementation(() => {});
});
afterEach(() => log.mockRestore());

describe('pluginRouter', () => {
  it('installs a single listener on first call', () => {
    installPluginRouter();
    expect(registeredListeners).toHaveLength(1);
    expect(__testing__.isInstalled()).toBe(true);
  });

  it('is idempotent across repeated calls', () => {
    installPluginRouter();
    installPluginRouter();
    installPluginRouter();
    expect(registeredListeners).toHaveLength(1);
  });

  it('records the last button event for synchronous reads', () => {
    installPluginRouter();
    expect(getLastButtonEvent()).toBeNull();
    registeredListeners[0].onButtonPress(press(500));
    expect(getLastButtonEvent()).toEqual(press(500));
  });

  it('fans events out to subscribers', () => {
    installPluginRouter();
    const a = jest.fn();
    const b = jest.fn();
    subscribeToButtonEvents(a);
    subscribeToButtonEvents(b);
    registeredListeners[0].onButtonPress(press(501));
    expect(a).toHaveBeenCalledWith(press(501));
    expect(b).toHaveBeenCalledWith(press(501));
  });

  it('removes subscribers via returned unsubscribe handle', () => {
    installPluginRouter();
    const fn = jest.fn();
    const unsubscribe = subscribeToButtonEvents(fn);
    expect(__testing__.getSubscriberCount()).toBe(1);
    unsubscribe();
    expect(__testing__.getSubscriberCount()).toBe(0);
    registeredListeners[0].onButtonPress(press(500));
    expect(fn).not.toHaveBeenCalled();
  });

  it('isolates subscriber exceptions so other subscribers still fire', () => {
    installPluginRouter();
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const thrower = jest.fn(() => {
        throw new Error('boom');
      });
      const healthy = jest.fn();
      subscribeToButtonEvents(thrower);
      subscribeToButtonEvents(healthy);
      registeredListeners[0].onButtonPress(press(500));
      expect(thrower).toHaveBeenCalled();
      expect(healthy).toHaveBeenCalled();
    } finally {
      errorSpy.mockRestore();
    }
  });
});
