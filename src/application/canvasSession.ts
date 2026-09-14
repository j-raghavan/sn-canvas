// The canvas session: which canvas the mounted view shows, and what a user
// does with it — open one (from a button press), start a new one, save it into
// the note as a linked thumbnail, and close (PRD FR11-FR13).
//
// Pure orchestration over two ports, tested with in-memory fakes;
// infrastructure/ holds the real adapters and wiring.ts plugs them in. Ports
// never reject: a failed step reports false/null, and the session logs and
// stops, so no user action can crash the plugin.
//
// One session per mounted canvas view (see ui/SuperCanvasScreen.tsx). That is
// what makes "save the current canvas before switching" safe: the view is
// known to hold the session's canvas, never a freshly mounted, empty one.
//
// Nothing drawn is left out of reach: the sidebar reopens the canvas last
// open, and "Open Canvas" finds a thumbnail's canvas through the link index
// (domain/canvasIndex.ts).

import {
  linkedCanvasIdOf,
  parseCanvasIndex,
  serializeCanvasIndex,
  uuidOf,
  withLastCanvas,
  withLink,
  type CanvasIndex,
} from '../domain/canvasIndex';
import {
  DEFAULT_CANVAS_ID,
  canvasDirPath,
  canvasFilePath,
  canvasIdFromLassoedElements,
  indexPath,
  thumbnailPath,
} from '../domain/canvasLink';
import {BUTTON_ID_OPEN_LINKED} from '../domain/entryPoints';
import type {Logger} from '../sdk/types';

/** The live canvas view's persistence, by absolute path, and the plugin's own small files. */
export type CanvasStorePort = {
  /** Shows the canvas saved at [path], replacing the view's content; a missing file shows an empty canvas and reports false. */
  load: (path: string) => Promise<boolean>;
  save: (path: string) => Promise<boolean>;
  remove: (path: string) => Promise<boolean>;
  renderThumbnail: (path: string) => Promise<boolean>;
  /** The text file at [path]; null when there is none or it can't be read. */
  readText: (path: string) => Promise<string | null>;
  writeText: (path: string, text: string) => Promise<boolean>;
  /** The canvases saved in [canvasDir], by id, most recently saved first. */
  savedCanvasIds: (canvasDir: string) => Promise<string[]>;
};

/** What the session needs from the Supernote host. */
export type HostPort = {
  pluginDir: () => Promise<string | null>;
  lassoedElements: () => Promise<unknown[]>;
  insertImage: (path: string) => Promise<boolean>;
  /** The uuid of the element last added to the current note page (so, the image just inserted); null when unknown. */
  lastElementUuid: () => Promise<string | null>;
  closeView: () => void;
};

export type CanvasSessionDeps = {
  store: CanvasStorePort;
  host: HostPort;
  newCanvasId: () => string;
  logger: Logger;
};

export type CanvasSession = {
  /** Shows the canvas a button press asks for: Open Canvas the lassoed thumbnail's, any other press (or none yet) the last one open. */
  open: (buttonId: number | null) => Promise<void>;
  /** Saves the canvas shown, then shows a new, empty one. */
  newCanvas: () => Promise<void>;
  /** Inserts the canvas into the note as a thumbnail that links back to it; true once inserted. Taps while one runs are ignored. */
  saveToNote: () => Promise<boolean>;
  /** Saves the canvas, then closes the plugin view whether or not the save worked. */
  close: () => Promise<void>;
  currentCanvasId: () => string;
};

const TAG = '[SUPERCANVAS]';

export function createCanvasSession({store, host, newCanvasId, logger}: CanvasSessionDeps): CanvasSession {
  let canvasId = DEFAULT_CANVAS_ID;
  let hasOpened = false;
  let saveToNotePending = false;
  let pluginDir: string | null = null;
  let index: CanvasIndex | null = null;
  // Each operation starts after the previous one settles, so a button press
  // can never switch canvases halfway through a save.
  let tail: Promise<void> = Promise.resolve();

  const serially = (task: () => Promise<void>): Promise<void> => {
    tail = tail.then(task).catch(error => logger.error(`${TAG} ${String(error)}`));
    return tail;
  };

  const resolvePluginDir = async (): Promise<string | null> => {
    if (pluginDir === null) {
      pluginDir = await host.pluginDir();
    }
    if (pluginDir === null) {
      logger.warn(`${TAG} no plugin directory; canvas not loaded or saved`);
    }
    return pluginDir;
  };

  const loadIndex = async (dir: string): Promise<CanvasIndex> => {
    if (index === null) {
      index = parseCanvasIndex(await store.readText(indexPath(dir)));
    }
    return index;
  };

  const updateIndex = async (dir: string, change: (current: CanvasIndex) => CanvasIndex): Promise<void> => {
    index = change(await loadIndex(dir));
    await store.writeText(indexPath(dir), serializeCanvasIndex(index));
  };

  /** The newest saved canvas besides the scratch one: what to show when nothing recorded says which. */
  const newestCanvas = async (dir: string): Promise<string | null> =>
    (await store.savedCanvasIds(canvasDirPath(dir))).find(id => id !== DEFAULT_CANVAS_ID) ?? null;

  /** The canvas last open; with none recorded (a first open, or canvases saved by a build without the index), the newest. */
  const lastCanvasId = async (dir: string): Promise<string> =>
    (await loadIndex(dir)).lastCanvasId ?? (await newestCanvas(dir)) ?? DEFAULT_CANVAS_ID;

  const linkedCanvasId = async (dir: string): Promise<string> => {
    const elements = await host.lassoedElements();
    const linkedId = linkedCanvasIdOf(elements, await loadIndex(dir)) ?? canvasIdFromLassoedElements(elements);
    logger.log(`${TAG}[LINK] lassoed=${elements.length} uuids=${JSON.stringify(elements.map(uuidOf))} canvas=${linkedId}`);
    // A thumbnail inserted before links were recorded has none: the newest canvas is the best guess.
    return linkedId ?? (await newestCanvas(dir)) ?? DEFAULT_CANVAS_ID;
  };

  /** Shows [target], saving the canvas shown first, and records it as the one the sidebar reopens. */
  const show = async (dir: string, target: string): Promise<void> => {
    if (hasOpened && target === canvasId) {
      return;
    }
    if (hasOpened) {
      await store.save(canvasFilePath(dir, canvasId));
    }
    canvasId = target;
    hasOpened = true;
    await store.load(canvasFilePath(dir, canvasId));
    await updateIndex(dir, current => withLastCanvas(current, canvasId));
  };

  const open = (buttonId: number | null): Promise<void> =>
    serially(async () => {
      const dir = await resolvePluginDir();
      if (!dir) {
        return;
      }
      await show(dir, buttonId === BUTTON_ID_OPEN_LINKED ? await linkedCanvasId(dir) : await lastCanvasId(dir));
      logger.log(`${TAG} button=${buttonId} opened canvas=${canvasId}`);
    });

  const newCanvas = (): Promise<void> =>
    serially(async () => {
      const dir = await resolvePluginDir();
      if (!dir) {
        return;
      }
      await show(dir, newCanvasId());
      logger.log(`${TAG} new canvas=${canvasId}`);
    });

  const linkIntoNote = async (): Promise<boolean> => {
    const dir = await resolvePluginDir();
    if (!dir) {
      return false;
    }
    // The scratch canvas gets an id of its own; a linked canvas re-links under the id it has.
    const fromScratch = canvasId === DEFAULT_CANVAS_ID;
    const linkedId = fromScratch ? newCanvasId() : canvasId;
    const canvasFile = canvasFilePath(dir, linkedId);
    const thumbnail = thumbnailPath(dir, linkedId);
    const inserted =
      (await store.save(canvasFile)) && (await store.renderThumbnail(thumbnail)) && (await host.insertImage(thumbnail));
    if (!inserted) {
      logger.warn(`${TAG}[LINK] save to note failed; canvas=${canvasId} unchanged`);
      if (fromScratch) {
        await store.remove(canvasFile);
        await store.remove(thumbnail);
      }
      return false;
    }
    // The note keeps nothing of the thumbnail's path: the new element's uuid is the link back.
    const uuid = await host.lastElementUuid();
    if (fromScratch) {
      // The scratch content lives on as the linked canvas, which the sidebar now reopens.
      canvasId = linkedId;
      await store.remove(canvasFilePath(dir, DEFAULT_CANVAS_ID));
    }
    await updateIndex(dir, current => withLastCanvas(uuid === null ? current : withLink(current, uuid, linkedId), linkedId));
    if (uuid === null) {
      logger.warn(`${TAG}[LINK] no uuid for the inserted thumbnail; Open Canvas on it will show the newest canvas`);
    }
    logger.log(`${TAG}[LINK] inserted thumbnail for canvas=${linkedId} uuid=${uuid}`);
    return true;
  };

  const saveToNote = (): Promise<boolean> => {
    if (saveToNotePending) {
      logger.log(`${TAG}[LINK] save to note already running; tap ignored`);
      return Promise.resolve(false);
    }
    saveToNotePending = true;
    let inserted = false;
    return serially(async () => {
      try {
        inserted = await linkIntoNote();
      } finally {
        saveToNotePending = false;
      }
    }).then(() => inserted);
  };

  const close = (): Promise<void> =>
    serially(async () => {
      // Never saved before a canvas was opened: the view would not hold it, and saving would overwrite it.
      const dir = hasOpened ? await resolvePluginDir() : null;
      if (dir) {
        await store.save(canvasFilePath(dir, canvasId));
      }
      host.closeView();
    });

  return {open, newCanvas, saveToNote, close, currentCanvasId: () => canvasId};
}
