// How a canvas is identified and where its files live (PRD FR12/FR13).
//
// A canvas is a JSON file in the plugin's private directory. "Save to Note"
// inserts a PNG thumbnail named after its canvas, so the thumbnail's own
// picture path is the link back: lassoing it and tapping "Open Canvas" reads
// the canvas id from that path. (The first design stamped the id into the
// inserted element's userData, but the host never hands back the element it
// just inserted, so there was nothing to stamp.)

/** The scratch canvas the sidebar opens. "Save to Note" moves its content to a canvas id of its own. */
export const DEFAULT_CANVAS_ID = 'default';

const CANVAS_DIR = 'SuperCanvas';

// Ids are only ever minted by mintCanvasId (or are DEFAULT_CANVAS_ID). Anything
// else is rejected, so a lassoed picture can never steer a load outside CANVAS_DIR.
const THUMBNAIL_PATH = new RegExp(`/${CANVAS_DIR}/thumbnails/([A-Za-z0-9-]+)\\.png$`);

/** A fresh canvas id such as `c-lx2k3j9a-0f3q`; the clock and randomness are injected so tests are deterministic. */
export function mintCanvasId(now: number, random: () => number): string {
  const suffix = Math.floor(random() * 36 ** 4)
    .toString(36)
    .padStart(4, '0');
  return `c-${now.toString(36)}-${suffix}`;
}

export function canvasFilePath(pluginDir: string, canvasId: string): string {
  return `${pluginDir}/${CANVAS_DIR}/${canvasId}.json`;
}

export function thumbnailPath(pluginDir: string, canvasId: string): string {
  return `${pluginDir}/${CANVAS_DIR}/thumbnails/${canvasId}.png`;
}

/** The canvas id a thumbnail path names, or null when [path] is not a SuperCanvas thumbnail. */
export function canvasIdFromThumbnailPath(path: unknown): string | null {
  if (typeof path !== 'string') {
    return null;
  }
  const match = THUMBNAIL_PATH.exec(path);
  return match ? match[1] : null;
}

/** A lassoed note element's picture path (sn-plugin-lib `Element.picture.picturePath`), if it has one. */
export function picturePathOf(element: unknown): unknown {
  return (element as {picture?: {picturePath?: unknown} | null} | null)?.picture?.picturePath;
}

/** The canvas behind the first SuperCanvas thumbnail among lassoed note elements, or null if none is one. */
export function canvasIdFromLassoedElements(elements: readonly unknown[]): string | null {
  for (const element of elements) {
    const canvasId = canvasIdFromThumbnailPath(picturePathOf(element));
    if (canvasId) {
      return canvasId;
    }
  }
  return null;
}
