// How a canvas is identified and where its files live (PRD FR12/FR13).
//
// A canvas is a JSON file in the canvas folder, beside its PNG thumbnail and
// the link index (domain/canvasIndex.ts) that says which note thumbnail opens
// which canvas. The folder is MyStyle/SnCanvas in shared storage, so
// canvases outlast an uninstall (which deletes the plugin's own directory) and
// sync with the rest of MyStyle; without file write access they stay in the
// plugin's own directory instead. "Open Canvas" still reads an id from a
// lassoed picture's path when it names one, but this firmware hands a lassoed
// picture back as a renamed temporary copy, so the element's uuid, recorded in
// the index, is the link that holds.

/** The scratch canvas a first sidebar open shows. "Save to Note" moves its content to a canvas id of its own. */
export const DEFAULT_CANVAS_ID = 'default';

/** Where canvases live with file write access: beside the plugin file in MyStyle, outlasting an uninstall. */
export const SHARED_CANVAS_DIR = '/storage/emulated/0/MyStyle/SnCanvas';

/** Where canvases lived before the plugin was renamed from SuperCanvas; the first open with write access moves them in. */
export const LEGACY_SHARED_CANVAS_DIR = '/storage/emulated/0/MyStyle/SnSuperCanvas';

// The canvas folder inside the plugin's own directory.
const PRIVATE_CANVAS_DIR = 'Canvas';

// Ids are only ever minted by mintCanvasId (or are DEFAULT_CANVAS_ID). Anything
// else is rejected, so neither a lassoed picture nor a stray file can steer a load outside the canvas folder.
const CANVAS_ID = /^(default|c-[a-z0-9-]+)$/;
const THUMBNAIL_PATH = /\/thumbnails\/([A-Za-z0-9-]+)\.png$/;

/** A fresh canvas id such as `c-lx2k3j9a-0f3q`; the clock and randomness are injected so tests are deterministic. */
export function mintCanvasId(now: number, random: () => number): string {
  const suffix = Math.floor(random() * 36 ** 4)
    .toString(36)
    .padStart(4, '0');
  return `c-${now.toString(36)}-${suffix}`;
}

/** True for a canvas id: the scratch canvas's, or one [mintCanvasId] made. */
export function isCanvasId(value: unknown): value is string {
  return typeof value === 'string' && CANVAS_ID.test(value);
}

/**
 * The canvas folder inside the plugin's own directory: where canvases stay
 * without file write access, and where builds before shared storage kept
 * them. Uninstalling Canvas deletes it.
 */
export function privateCanvasDir(pluginDir: string): string {
  return `${pluginDir}/${PRIVATE_CANVAS_DIR}`;
}

/**
 * A marker left in the plugin's own directory once Canvas has opened. Installing
 * or reinstalling the plugin replaces that directory, marker and all, so a
 * missing marker means the first open since an install. It sits outside the
 * canvas folder, so moving old canvases to MyStyle never carries it along.
 */
export function installMarkerPath(pluginDir: string): string {
  return `${pluginDir}/canvas-opened`;
}

export function canvasFilePath(canvasDir: string, canvasId: string): string {
  return `${canvasDir}/${canvasId}.json`;
}

export function thumbnailPath(canvasDir: string, canvasId: string): string {
  return `${canvasDir}/thumbnails/${canvasId}.png`;
}

/** The folder a canvas folder's images are copied into (FR22); an image element names its file in it. */
export function imagesPath(canvasDir: string): string {
  return `${canvasDir}/images`;
}

/** Where "Export to PDF" writes (FR11): the folder Supernote keeps exported files in. */
export const EXPORT_DIR = '/storage/emulated/0/EXPORT';

/** The PDF a canvas exported at [at] is saved as, named by the device's local time: `Canvas-20260914-111507.pdf`. */
export function pdfPath(at: Date): string {
  const two = (value: number) => String(value).padStart(2, '0');
  const date = `${at.getFullYear()}${two(at.getMonth() + 1)}${two(at.getDate())}`;
  const time = `${two(at.getHours())}${two(at.getMinutes())}${two(at.getSeconds())}`;
  return `${EXPORT_DIR}/Canvas-${date}-${time}.pdf`;
}

/** The link index (domain/canvasIndex.ts); `links` is not a canvas id, so it never lists as a canvas. */
export function indexPath(canvasDir: string): string {
  return `${canvasDir}/links.json`;
}

/** The canvas id a thumbnail path names, or null when [path] is not a Canvas thumbnail. */
export function canvasIdFromThumbnailPath(path: unknown): string | null {
  if (typeof path !== 'string') {
    return null;
  }
  const match = THUMBNAIL_PATH.exec(path);
  return match && isCanvasId(match[1]) ? match[1] : null;
}

/**
 * The follow-link event body (FR7), validated at the bridge: a link this build
 * knows, with a target, or null. The canvas only ever sends links it stored,
 * but the bridge is a boundary and anything crossing it is checked.
 */
export function parseElementLink(payload: unknown): {kind: 'note'; target: string; page: number} | null {
  const body = (payload ?? {}) as {kind?: unknown; target?: unknown; page?: unknown};
  if (body.kind !== 'note' || typeof body.target !== 'string' || body.target === '') {
    return null;
  }
  return {kind: 'note', target: body.target, page: Number.isInteger(body.page) ? (body.page as number) : -1};
}

/** A lassoed note element's picture path (sn-plugin-lib `Element.picture.picturePath`), if it has one. */
export function picturePathOf(element: unknown): unknown {
  return (element as {picture?: {picturePath?: unknown} | null} | null)?.picture?.picturePath;
}

/** The canvas behind the first Canvas thumbnail among lassoed note elements, or null if none is one. */
export function canvasIdFromLassoedElements(elements: readonly unknown[]): string | null {
  for (const element of elements) {
    const canvasId = canvasIdFromThumbnailPath(picturePathOf(element));
    if (canvasId) {
      return canvasId;
    }
  }
  return null;
}
