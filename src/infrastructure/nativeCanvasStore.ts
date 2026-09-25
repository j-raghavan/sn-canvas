// CanvasStorePort over the native CanvasModule
// (android/app/src/main/java/com/sncanvas/canvas/CanvasModule.kt).
// Like sn-copilot's CopilotOverlay facade it never rejects: a missing module,
// a rejection or a malformed result all come back as false, null, zero or
// empty, logged here, so the session branches on plain values instead of
// wrapping calls in try/catch.

import {NativeModules} from 'react-native';
import type {CanvasStorePort} from '../application/canvasSession';
import {isCanvasId} from '../domain/canvasLink';
import {TAG} from '../diagnostics/log';
import type {Logger} from '../sdk/types';
import {neverThrows} from './neverThrows';

type PathMethod = 'saveCanvas' | 'bindCanvas' | 'writeCanvasTo' | 'holdsCanvas' | 'deleteCanvas' | 'generateThumbnail' | 'exportPdf';

export type NativeCanvasModule = Record<PathMethod, (path: string) => Promise<boolean>> & {
  loadCanvas: (path: string, imageDir: string) => Promise<boolean>;
  importImage: (source: string, imageDir: string) => Promise<boolean>;
  readText: (path: string) => Promise<string | null>;
  writeText: (path: string, text: string) => Promise<boolean>;
  /** The `*.json` file names in a folder, most recently saved first. */
  listCanvasFiles: (dir: string) => Promise<string[]>;
  adoptFolder: (from: string, to: string) => Promise<number>;
  setNotePen: (type: number, width: number, color: number) => Promise<boolean>;
};


export function createNativeCanvasStore(
  logger: Logger,
  native: NativeCanvasModule | undefined = (NativeModules as {CanvasModule?: NativeCanvasModule}).CanvasModule,
): CanvasStorePort {
  const attempt = neverThrows(logger);
  const invoke = <T>(method: string, fallback: T, run: (module: NativeCanvasModule) => Promise<T>): Promise<T> => {
    if (!native) {
      logger.error(`${TAG} ${method}: NativeModules.CanvasModule is missing (is CanvasPackage in MainApplication.kt?)`);
      return Promise.resolve(fallback);
    }
    return attempt(method, fallback, () => run(native));
  };

  const call = (method: PathMethod, path: string): Promise<boolean> =>
    invoke(method, false, async module => (await module[method](path)) === true);

  return {
    rememberNotePen: pen =>
      invoke('setNotePen', false, async module => (await module.setNotePen(pen.type, pen.width, pen.color)) === true),
    load: (path, imageDir) => invoke('loadCanvas', false, async module => (await module.loadCanvas(path, imageDir)) === true),
    importImage: (source, imageDir) =>
      invoke('importImage', false, async module => (await module.importImage(source, imageDir)) === true),
    save: path => call('saveCanvas', path),
    writeTo: path => call('writeCanvasTo', path),
    bindTo: path => call('bindCanvas', path),
    holds: path => call('holdsCanvas', path),
    remove: path => call('deleteCanvas', path),
    renderThumbnail: path => call('generateThumbnail', path),
    exportPdf: path => call('exportPdf', path),
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
    adoptFolder: (from, to) =>
      invoke('adoptFolder', 0, async module => {
        const moved: unknown = await module.adoptFolder(from, to);
        return typeof moved === 'number' ? moved : 0;
      }),
  };
}
