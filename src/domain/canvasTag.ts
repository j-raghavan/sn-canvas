// A thumbnail's own record of its canvas (PRD FR13): a canvas id in the
// picture's userData, which a lasso hands back. Canvas no longer writes one:
// the write (modifyElements) does not take on this firmware, and the page's
// pictures disappear on the note's next reload (#30, seen on device). Tags
// written by earlier builds are still read, so the thumbnails they marked
// still open their canvas.

import {isCanvasId} from './canvasLink';

const TAG_PREFIX = 'sncanvas:';

// Tags written before the plugin was renamed from SuperCanvas: still read, never written.
const LEGACY_TAG_PREFIX = 'snsupercanvas:';

/** The userData that names [canvasId]. */
export function canvasTag(canvasId: string): string {
  return `${TAG_PREFIX}${canvasId}`;
}

/** The canvas a note element's userData names, or null when it names none. */
export function taggedCanvasId(element: unknown): string | null {
  const userData = (element as {userData?: unknown} | null | undefined)?.userData;
  if (typeof userData !== 'string') {
    return null;
  }
  const prefix = [TAG_PREFIX, LEGACY_TAG_PREFIX].find(candidate => userData.startsWith(candidate));
  const canvasId = prefix === undefined ? null : userData.slice(prefix.length);
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
