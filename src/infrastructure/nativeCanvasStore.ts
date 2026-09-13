// CanvasStorePort over the native SuperCanvasModule
// (android/app/src/main/java/com/snsupercanvas/canvas/SuperCanvasModule.kt).
// Like sn-copilot's CopilotOverlay facade it never rejects: a missing module,
// a rejection or a non-true result all come back as false, logged here, so
// the session branches on a boolean instead of wrapping calls in try/catch.

import {NativeModules} from 'react-native';
import type {CanvasStorePort} from '../application/canvasSession';
import type {Logger} from '../sdk/types';

type NativeMethod = 'loadCanvas' | 'saveCanvas' | 'deleteCanvas' | 'generateThumbnail';

export type NativeCanvasModule = Record<NativeMethod, (path: string) => Promise<boolean>>;

const TAG = '[SUPERCANVAS]';

export function createNativeCanvasStore(
  logger: Logger,
  native: NativeCanvasModule | undefined = (NativeModules as {SuperCanvasModule?: NativeCanvasModule}).SuperCanvasModule,
): CanvasStorePort {
  const call = async (method: NativeMethod, path: string): Promise<boolean> => {
    if (!native) {
      logger.error(`${TAG} ${method}: NativeModules.SuperCanvasModule is missing (is SuperCanvasPackage in MainApplication.kt?)`);
      return false;
    }
    try {
      return (await native[method](path)) === true;
    } catch (error) {
      logger.warn(`${TAG} ${method} failed: ${String(error)}`);
      return false;
    }
  };

  return {
    load: path => call('loadCanvas', path),
    save: path => call('saveCanvas', path),
    remove: path => call('deleteCanvas', path),
    renderThumbnail: path => call('generateThumbnail', path),
  };
}
