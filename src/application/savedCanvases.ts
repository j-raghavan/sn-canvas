// The canvases on disk, as the session and the note thumbnails both ask about
// them.

import {DEFAULT_CANVAS_ID} from '../domain/canvasLink';
import type {CanvasStorePort} from './ports';

/**
 * The newest saved canvas in [dir] besides the scratch one that [mayHave] accepts: what to show
 * when nothing recorded says which.
 *
 * It is a guess, so it has to be asked whether the guess is allowed. Taking the newest file on disk
 * without asking hands over whatever happens to be newest, which may be another note's drawing
 * (#46) or one drawn when nothing knew whose it was (#49). [mayHave] is not optional for that
 * reason: there is no caller for whom any canvas will do.
 */
export async function newestCanvas(
  store: CanvasStorePort,
  dir: string,
  mayHave: (canvasId: string) => boolean,
): Promise<string | null> {
  return (await store.savedCanvasIds(dir)).find(id => id !== DEFAULT_CANVAS_ID && mayHave(id)) ?? null;
}
