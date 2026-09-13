/**
 * The native view's JS surface: commands carry their arguments, and the
 * e-ink grays come from the view manager's exported constants when the host
 * exposes them.
 */
const mockDispatch = jest.fn();
const mockGetViewManagerConfig = jest.fn();

jest.mock('react-native', () => ({
  requireNativeComponent: () => 'SuperCanvasView',
  findNodeHandle: (view: unknown) => (view ? 7 : null),
  UIManager: {
    dispatchViewManagerCommand: (...args: unknown[]) => mockDispatch(...args),
    getViewManagerConfig: (name: string) => mockGetViewManagerConfig(name),
  },
}));

import {dispatchCanvasCommand, nativeEinkGrays, type CanvasViewRef} from '../src/ui/nativeCanvasView';

const mountedView = {} as CanvasViewRef;

beforeEach(() => mockDispatch.mockClear());

test('a command carries its arguments to the mounted view', () => {
  dispatchCanvasCommand(mountedView, 'setStyle', ['color', 'red']);
  expect(mockDispatch).toHaveBeenCalledWith(7, 'setStyle', ['color', 'red']);
});

test('a command without arguments sends an empty list', () => {
  dispatchCanvasCommand(mountedView, 'undo');
  expect(mockDispatch).toHaveBeenCalledWith(7, 'undo', []);
});

test('a command before the view mounts is dropped', () => {
  dispatchCanvasCommand(null, 'undo');
  expect(mockDispatch).not.toHaveBeenCalled();
});

test('the e-ink grays are read from the view constants, or null when absent', () => {
  mockGetViewManagerConfig.mockReturnValueOnce({Constants: {einkGrays: {black: '#000000'}}});
  expect(nativeEinkGrays()).toEqual({black: '#000000'});
  expect(mockGetViewManagerConfig).toHaveBeenCalledWith('SuperCanvasView');
  mockGetViewManagerConfig.mockReturnValueOnce({});
  expect(nativeEinkGrays()).toBeNull();
  mockGetViewManagerConfig.mockReturnValueOnce(undefined);
  expect(nativeEinkGrays()).toBeNull();
});
