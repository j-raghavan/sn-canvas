// The canvas session: which canvas the mounted view shows, and the three
// things a user does with it — open one (from a button press), save it into
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

import {BUTTON_ID_OPEN_LINKED} from '../domain/entryPoints';
import {
  DEFAULT_CANVAS_ID,
  canvasFilePath,
  canvasIdFromLassoedElements,
  picturePathOf,
  thumbnailPath,
} from '../domain/canvasLink';
import type {Logger} from '../sdk/types';

/** The live canvas view's persistence, by absolute path. */
export type CanvasStorePort = {
  /** Shows the canvas saved at [path], replacing the view's content; a missing file shows an empty canvas and reports false. */
  load: (path: string) => Promise<boolean>;
  save: (path: string) => Promise<boolean>;
  remove: (path: string) => Promise<boolean>;
  renderThumbnail: (path: string) => Promise<boolean>;
};

/** What the session needs from the Supernote host. */
export type HostPort = {
  pluginDir: () => Promise<string | null>;
  lassoedElements: () => Promise<unknown[]>;
  insertImage: (path: string) => Promise<boolean>;
  closeView: () => void;
};

export type CanvasSessionDeps = {
  store: CanvasStorePort;
  host: HostPort;
  newCanvasId: () => string;
  logger: Logger;
};

export type CanvasSession = {
  /** Shows the canvas a button press asks for; null (no press seen yet) means the scratch canvas. */
  open: (buttonId: number | null) => Promise<void>;
  /** Inserts the canvas into the note as a thumbnail that links back to it; true once inserted. Taps while one runs are ignored. */
  saveToNote: () => Promise<boolean>;
  /** Saves the canvas, then closes the plugin view whether or not the save worked. */
  close: () => Promise<void>;
  currentCanvasId: () => string;
};

const TAG = '[SUPERCANVAS]';

// Just a path's last two segments: enough to see where the host keeps a picture, without logging note paths.
const pathTail = (path: unknown): string =>
  typeof path === 'string' ? path.split('/').slice(-2).join('/') : String(path);

export function createCanvasSession({store, host, newCanvasId, logger}: CanvasSessionDeps): CanvasSession {
  let canvasId = DEFAULT_CANVAS_ID;
  let hasOpened = false;
  let saveToNotePending = false;
  let pluginDir: string | null = null;
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

  const linkedCanvasId = async (): Promise<string | null> => {
    const elements = await host.lassoedElements();
    const linkedId = canvasIdFromLassoedElements(elements);
    const pictures = elements.map(element => pathTail(picturePathOf(element)));
    logger.log(`${TAG}[LINK] lassoed=${elements.length} pictures=${JSON.stringify(pictures)} canvas=${linkedId}`);
    return linkedId;
  };

  const open = (buttonId: number | null): Promise<void> =>
    serially(async () => {
      const dir = await resolvePluginDir();
      if (!dir) {
        return;
      }
      const target = buttonId === BUTTON_ID_OPEN_LINKED ? (await linkedCanvasId()) ?? DEFAULT_CANVAS_ID : DEFAULT_CANVAS_ID;
      if (hasOpened && target === canvasId) {
        return;
      }
      if (hasOpened) {
        await store.save(canvasFilePath(dir, canvasId));
      }
      canvasId = target;
      hasOpened = true;
      await store.load(canvasFilePath(dir, canvasId));
      logger.log(`${TAG} button=${buttonId} opened canvas=${canvasId}`);
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
    if (fromScratch) {
      // The scratch content lives on as the linked canvas, so the next sidebar open starts blank.
      canvasId = linkedId;
      await store.remove(canvasFilePath(dir, DEFAULT_CANVAS_ID));
    }
    logger.log(`${TAG}[LINK] inserted thumbnail for canvas=${linkedId}`);
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

  return {open, saveToNote, close, currentCanvasId: () => canvasId};
}
