// Deleting canvases out of "Canvases in this note" (#74): taking a saved
// canvas's file and thumbnail off disk and its id out of the link index, so
// no note goes on listing or reopening it. The canvas on screen is never
// offered: the native view has nothing to put in its place once it is gone,
// so a delete that named it would have to close or replace what is shown,
// which is a different feature from this one.

import {canvasFilePath, thumbnailPath} from '../domain/canvasLink';
import {withoutCanvas, type CanvasIndex} from '../domain/canvasIndex';
import type {Logger} from '../sdk/types';
import type {CanvasStorePort} from './ports';

const TAG = '[SNCANVAS]';

export type CanvasDeletionDeps = {
  store: CanvasStorePort;
  logger: Logger;
  /** The folder canvases live in, settled by the session; null without one. */
  canvasDir: () => Promise<string | null>;
  index: {
    load: (dir: string) => Promise<CanvasIndex>;
    update: (dir: string, change: (current: CanvasIndex) => CanvasIndex) => Promise<void>;
  };
  /** The canvas shown, which a delete never takes, since the session has nothing to put in its place. */
  shown: {id: () => string};
  serially: (task: () => Promise<void>) => Promise<void>;
};

export type CanvasDeletion = {
  /** Deletes every id in [canvasIds] besides the one shown; the ids actually deleted, in no particular order. */
  deleteCanvases: (canvasIds: readonly string[]) => Promise<readonly string[]>;
};

export function createCanvasDeletion({store, logger, canvasDir, index, shown, serially}: CanvasDeletionDeps): CanvasDeletion {
  const deleteCanvases = async (canvasIds: readonly string[]): Promise<readonly string[]> => {
    let removed: readonly string[] = [];
    await serially(async () => {
      const dir = await canvasDir();
      if (dir === null) {
        return;
      }
      const targets = [...new Set(canvasIds)].filter(id => id !== shown.id());
      if (targets.length === 0) {
        return;
      }
      await Promise.all(targets.flatMap(id => [store.remove(canvasFilePath(dir, id)), store.remove(thumbnailPath(dir, id))]));
      await index.update(dir, current => targets.reduce(withoutCanvas, current));
      removed = targets;
      logger.log(`${TAG} deleted canvases=${targets.join(',')}`);
    });
    return removed;
  };

  return {deleteCanvases};
}
