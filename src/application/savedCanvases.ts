// The canvases on disk, as the session and the note thumbnails both ask about
// them.

import {DEFAULT_CANVAS_ID} from '../domain/canvasLink';
import type {CanvasStorePort} from './ports';

/** The newest saved canvas in [dir] besides the scratch one: what to show when nothing recorded says which. */
export async function newestCanvas(store: CanvasStorePort, dir: string): Promise<string | null> {
  return (await store.savedCanvasIds(dir)).find(id => id !== DEFAULT_CANVAS_ID) ?? null;
}
