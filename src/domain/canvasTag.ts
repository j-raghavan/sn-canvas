// A thumbnail's own record of its canvas (PRD FR13): the canvas id written
// into the placed picture's userData, a free-form string the note keeps with
// the element (as sn-drafting-pen's tag shows) and hands back with every
// lasso. The lasso's copy of a picture gets a fresh uuid each time and the
// picture can be moved, so this tag, not a uuid or a position, is what "Open
// Canvas" reads.

import {isCanvasId} from './canvasLink';

const TAG_PREFIX = 'snsupercanvas:';

/** The userData that names [canvasId]. */
export function canvasTag(canvasId: string): string {
  return `${TAG_PREFIX}${canvasId}`;
}

/** The canvas a note element's userData names, or null when it names none. */
export function taggedCanvasId(element: unknown): string | null {
  const userData = (element as {userData?: unknown} | null | undefined)?.userData;
  if (typeof userData !== 'string' || !userData.startsWith(TAG_PREFIX)) {
    return null;
  }
  const canvasId = userData.slice(TAG_PREFIX.length);
  return isCanvasId(canvasId) ? canvasId : null;
}

/** The canvas the first tagged element among [elements] names, or null when none is tagged. */
export function canvasIdFromTags(elements: readonly unknown[]): string | null {
  for (const element of elements) {
    const canvasId = taggedCanvasId(element);
    if (canvasId !== null) {
      return canvasId;
    }
  }
  return null;
}

/**
 * [picture], as a lasso handed it over, ready for modifyElements to write back
 * tagged with [canvasId]: on page [page] (a lasso's copy says -1, which the SDK
 * rejects); showing the PNG at [imagePath], since the copy's own picture path
 * is a temporary one that doesn't exist for the note, which refuses a picture
 * without its PNG (error 1211, "PNG file does not exist"); and without its
 * native data accessors (angles, contoursSrc), which don't survive the trip
 * back across the bridge (sn-tables found "Cannot convert null value to
 * object"; both are optional).
 */
export function taggedPicture(picture: unknown, canvasId: string, page: number, imagePath: string): Record<string, unknown> {
  const copy = {...((picture ?? {}) as Record<string, unknown>)};
  delete copy.angles;
  delete copy.contoursSrc;
  const shown = (copy.picture ?? {}) as Record<string, unknown>;
  return {...copy, pageNum: page, userData: canvasTag(canvasId), picture: {...shown, picturePath: imagePath}};
}
