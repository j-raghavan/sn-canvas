/**
 * The back badge's adapter over NativeBackBadgeModule: calls pass through, taps
 * arrive from the module's events, and without the module nothing is shown.
 */
const mockAddListener = jest.fn();
const mockRemove = jest.fn();

jest.mock('react-native', () => ({
  NativeModules: {},
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: (...args: unknown[]) => {
      mockAddListener(...args);
      return {remove: mockRemove};
    },
  })),
}));

import {BACK_BADGE_TAPPED, createNativeBackBadge, type NativeBackBadgeModule} from '../src/infrastructure/nativeBackBadge';
import {createRecordingLogger} from './helpers/fakePorts';

const createNative = () =>
  ({show: jest.fn(), hide: jest.fn(), addListener: jest.fn(), removeListeners: jest.fn()}) as unknown as jest.Mocked<
    NativeBackBadgeModule
  >;

test('shows and hides the native badge, and hears its taps until unsubscribed', () => {
  const native = createNative();
  const badge = createNativeBackBadge(createRecordingLogger(), native);
  badge.show('Canvas', '/n.note');
  badge.hide();
  expect(native.show).toHaveBeenCalledWith('Canvas', '/n.note');
  expect(native.hide).toHaveBeenCalledTimes(1);
  const listener = jest.fn();
  const unsubscribe = badge.onTapped(listener);
  expect(mockAddListener).toHaveBeenCalledWith(BACK_BADGE_TAPPED, listener);
  unsubscribe();
  expect(mockRemove).toHaveBeenCalledTimes(1);
});

test('without the native module the badge is never shown, and that is said once', () => {
  const logger = createRecordingLogger();
  const badge = createNativeBackBadge(logger);
  badge.show('Canvas', '/n.note');
  badge.hide();
  badge.onTapped(jest.fn())();
  expect(logger.lines).toEqual([
    'warn [SNCANVAS] NativeModules.BackBadge is missing; no way back from a followed link but the sidebar',
  ]);
});
