// CanvasStorePort over the native SuperCanvasModule
// (android/app/src/main/java/com/snsupercanvas/canvas/SuperCanvasModule.kt).
// Like sn-copilot's CopilotOverlay facade it never rejects: a missing module,
// a rejection or a malformed result all come back as false, null or empty,
// logged here, so the session branches on plain values instead of wrapping
// calls in try/catch.

import {NativeModules} from 'react-native';
import type {CanvasStorePort} from '../application/canvasSession';
import {isCanvasId} from '../domain/canvasLink';
import type {Logger} from '../sdk/types';

type PathMethod = 'loadCanvas' | 'saveCanvas' | 'deleteCanvas' | 'generateThumbnail';

export type NativeCanvasModule = Record<PathMethod, (path: string) => Promise<boolean>> & {
  readText: (path: string) => Promise<string | null>;
  writeText: (path: string, text: string) => Promise<boolean>;
  /** The `*.json` file names in a folder, most recently saved first. */
  listCanvasFiles: (dir: string) => Promise<string[]>;
};

const TAG = '[SUPERCANVAS]';

export function createNativeCanvasStore(
  logger: Logger,
  native: NativeCanvasModule | undefined = (NativeModules as {SuperCanvasModule?: NativeCanvasModule}).SuperCanvasModule,
): CanvasStorePort {
  const invoke = async <T>(method: string, fallback: T, run: (module: NativeCanvasModule) => Promise<T>): Promise<T> => {
    if (!native) {
      logger.error(`${TAG} ${method}: NativeModules.SuperCanvasModule is missing (is SuperCanvasPackage in MainApplication.kt?)`);
      return fallback;
    }
    try {
      return await run(native);
    } catch (error) {
      logger.warn(`${TAG} ${method} failed: ${String(error)}`);
      return fallback;
    }
  };

  const call = (method: PathMethod, path: string): Promise<boolean> =>
    invoke(method, false, async module => (await module[method](path)) === true);

  return {
    load: path => call('loadCanvas', path),
    save: path => call('saveCanvas', path),
    remove: path => call('deleteCanvas', path),
    renderThumbnail: path => call('generateThumbnail', path),
    readText: path =>
      invoke('readText', null, async module => {
        const text = await module.readText(path);
        return typeof text === 'string' ? text : null;
      }),
    writeText: (path, text) => invoke('writeText', false, async module => (await module.writeText(path, text)) === true),
    // Only canvas ids get through: the link index and anything stray in the folder are not canvases.
    savedCanvasIds: canvasDir =>
      invoke('listCanvasFiles', [], async module => {
        const names: unknown = await module.listCanvasFiles(canvasDir);
        return (Array.isArray(names) ? names : []).map(name => String(name).replace(/\.json$/, '')).filter(isCanvasId);
      }),
  };
}
