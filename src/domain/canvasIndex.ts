// Which canvas each "Save to Note" thumbnail opens, and which canvas the
// sidebar reopens (PRD FR12/FR13). The note keeps nothing of an inserted
// picture's path: it hands a lassoed picture back as a temporary copy named by
// the time. What lasts is the element's uuid, so "Save to Note" reads it back
// with PluginFileAPI.getLastElement (as sn-tables does for its tables) and
// records it here, and "Open Canvas" matches lassoed uuids against it. Kept as
// JSON beside the canvases (canvasLink.indexPath) and read defensively: a
// missing or damaged index is an empty one, and only canvas ids get through.

import {isCanvasId} from './canvasLink';

export type CanvasIndex = {
  /** Note element uuid → the canvas its thumbnail opens. */
  readonly links: Readonly<Record<string, string>>;
  /** The canvas the sidebar reopens; null before one was recorded. */
  readonly lastCanvasId: string | null;
};

export const EMPTY_INDEX: CanvasIndex = {links: {}, lastCanvasId: null};

/** The index saved as [json]; the empty index for no file or anything unreadable. */
export function parseCanvasIndex(json: string | null): CanvasIndex {
  if (json === null) {
    return EMPTY_INDEX;
  }
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    return EMPTY_INDEX;
  }
  const saved = (raw ?? {}) as {links?: unknown; lastCanvasId?: unknown};
  const links: Record<string, string> = {};
  if (saved.links && typeof saved.links === 'object') {
    for (const [uuid, canvasId] of Object.entries(saved.links as Record<string, unknown>)) {
      if (isCanvasId(canvasId)) {
        links[uuid] = canvasId;
      }
    }
  }
  return {links, lastCanvasId: isCanvasId(saved.lastCanvasId) ? saved.lastCanvasId : null};
}

export function serializeCanvasIndex(index: CanvasIndex): string {
  return JSON.stringify(index);
}

/** [index] with the note element [uuid] opening [canvasId]. */
export function withLink(index: CanvasIndex, uuid: string, canvasId: string): CanvasIndex {
  return {...index, links: {...index.links, [uuid]: canvasId}};
}

/** [index] with [canvasId] as the canvas the sidebar reopens. */
export function withLastCanvas(index: CanvasIndex, canvasId: string): CanvasIndex {
  return {...index, lastCanvasId: canvasId};
}

/** A note element's uuid (sn-plugin-lib `Element.uuid`), or null when it has none. */
export function uuidOf(element: unknown): string | null {
  const uuid = (element as {uuid?: unknown} | null | undefined)?.uuid;
  return typeof uuid === 'string' && uuid !== '' ? uuid : null;
}

/** The canvas opened by the first of [elements] the index links, or null when none is linked. */
export function linkedCanvasIdOf(elements: readonly unknown[], index: CanvasIndex): string | null {
  for (const element of elements) {
    const uuid = uuidOf(element);
    if (uuid !== null && Object.prototype.hasOwnProperty.call(index.links, uuid)) {
      return index.links[uuid];
    }
  }
  return null;
}
