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
// Nothing drawn is left out of reach: canvases live in MyStyle/SnSuperCanvas,
// which outlasts an uninstall, the sidebar reopens the canvas last open (the
// first open after an install starts a new one instead), and
// "Open Canvas" finds a thumbnail's canvas through the link index
// (domain/canvasIndex.ts) and the tag it leaves on the picture itself
// (domain/canvasTag.ts).

import {
  claimPending,
  elementSummary,
  parseCanvasIndex,
  serializeCanvasIndex,
  withLastCanvas,
  withPending,
  type CanvasIndex,
  type NotePage,
} from '../domain/canvasIndex';
import {
  DEFAULT_CANVAS_ID,
  SHARED_CANVAS_DIR,
  canvasFilePath,
  canvasIdFromLassoedElements,
  indexPath,
  installMarkerPath,
  privateCanvasDir,
  thumbnailPath,
} from '../domain/canvasLink';
import {canvasIdFromTags} from '../domain/canvasTag';
import {BUTTON_ID_OPEN_LINKED} from '../domain/entryPoints';
import type {Logger} from '../sdk/types';

/** The pen the note writes with, in the SDK's codes (sn-plugin-lib's PenInfo). */
export type NotePen = {type: number; width: number; color: number};

/** The live canvas view's persistence, by absolute path, the canvas folder's own small files, and the pen it gives back. */
export type CanvasStorePort = {
  /** Remembers the note's pen, for the canvas to set back as it gives the pen back to the note; false when it can't. */
  rememberNotePen: (pen: NotePen) => Promise<boolean>;
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
  /** Moves the files under [from] into [to], keeping any [to] already has; how many moved. */
  adoptFolder: (from: string, to: string) => Promise<number>;
};

/** What the session needs from the Supernote host. */
export type HostPort = {
  pluginDir: () => Promise<string | null>;
  /** Asks for the file permissions Canvas uses, if not granted already; true when it may write and delete in shared storage. */
  requestFileAccess: () => Promise<boolean>;
  /** The pen the note writes with; null when the host can't say. */
  notePen: () => Promise<NotePen | null>;
  lassoedElements: () => Promise<unknown[]>;
  insertImage: (path: string) => Promise<boolean>;
  /** The note page the user is on; null when the host can't say. */
  currentPage: () => Promise<NotePage | null>;
  /** The numbers in the page of the pictures on page [at], in page order; empty when it can't be read. Slow: seconds, not milliseconds. */
  pagePictureNumbers: (at: NotePage) => Promise<number[]>;
  /**
   * Writes [canvasId] into the placed [picture]'s userData on page [at], showing
   * the PNG at [imagePath], so every later lasso of it names its canvas; true once written.
   */
  tagPicture: (picture: unknown, canvasId: string, at: NotePage, imagePath: string) => Promise<boolean>;
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
  let canvasDir: string | null = null;
  let pluginDirPath: string | null = null;
  let checkedInstall = false;
  let index: CanvasIndex | null = null;
  // Each operation starts after the previous one settles, so a button press
  // can never switch canvases halfway through a save.
  let tail: Promise<void> = Promise.resolve();

  const serially = (task: () => Promise<void>): Promise<void> => {
    tail = tail.then(task).catch(error => logger.error(`${TAG} ${String(error)}`));
    return tail;
  };

  /**
   * The folder canvases live in, settled on first use: MyStyle/SnSuperCanvas
   * with file write access, taking in the canvases an earlier build kept in
   * the plugin's own folder; without it, that plugin folder. File access is
   * asked for here, before any canvas is read or written, so no save can race
   * the permission dialog into the wrong folder.
   */
  const resolveCanvasDir = async (): Promise<string | null> => {
    if (canvasDir !== null) {
      return canvasDir;
    }
    const pluginDir = await host.pluginDir();
    pluginDirPath = pluginDir;
    if (await host.requestFileAccess()) {
      canvasDir = SHARED_CANVAS_DIR;
      if (pluginDir !== null) {
        const moved = await store.adoptFolder(privateCanvasDir(pluginDir), SHARED_CANVAS_DIR);
        if (moved > 0) {
          logger.log(`${TAG} moved ${moved} files from the plugin folder to ${SHARED_CANVAS_DIR}`);
        }
      }
    } else if (pluginDir !== null) {
      canvasDir = privateCanvasDir(pluginDir);
      logger.warn(`${TAG} no file write access; canvases stay in the plugin folder, which uninstalling Canvas deletes`);
    } else {
      logger.warn(`${TAG} no plugin directory; canvas not loaded or saved`);
    }
    return canvasDir;
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
    (await store.savedCanvasIds(dir)).find(id => id !== DEFAULT_CANVAS_ID) ?? null;

  /** The canvas last open; with none recorded (a first open, or canvases saved by a build without the index), the newest. */
  const lastCanvasId = async (dir: string): Promise<string> =>
    (await loadIndex(dir)).lastCanvasId ?? (await newestCanvas(dir)) ?? DEFAULT_CANVAS_ID;

  /**
   * A lassoed thumbnail not tagged yet: the canvas of the pending link it
   * claims, if one waits for it on this page. The picture is then tagged with
   * that canvas, and the claim settled; if the tag can't be written, the canvas
   * still opens and its link keeps waiting.
   */
  const claimLassoed = async (dir: string, elements: readonly unknown[]): Promise<string | null> => {
    const current = await loadIndex(dir);
    const at = current.pending.length > 0 ? await host.currentPage() : null;
    const claim = at === null ? null : claimPending(current, elements, at);
    if (at === null || claim === null) {
      return null;
    }
    // The picture shows the canvas's saved thumbnail: the lasso's copy points at a PNG the note doesn't have.
    if (await host.tagPicture(claim.picture, claim.canvasId, at, thumbnailPath(dir, claim.canvasId))) {
      await updateIndex(dir, () => claim.index);
      logger.log(`${TAG}[LINK] tagged the thumbnail for canvas=${claim.canvasId}`);
    } else {
      logger.warn(`${TAG}[LINK] could not tag the thumbnail for canvas=${claim.canvasId}; its link stays pending`);
    }
    return claim.canvasId;
  };

  const linkedCanvasId = async (dir: string): Promise<string> => {
    const elements = await host.lassoedElements();
    const linkedId =
      canvasIdFromTags(elements) ??
      canvasIdFromLassoedElements(elements) ??
      (await claimLassoed(dir, elements));
    logger.log(
      `${TAG}[LINK] lassoed=${elements.length} elements=${JSON.stringify(elements.map(elementSummary))} canvas=${linkedId}`,
    );
    // Nothing links it (a thumbnail from a build without links, say): the newest canvas is the best guess.
    return linkedId ?? (await newestCanvas(dir)) ?? DEFAULT_CANVAS_ID;
  };

  /**
   * True the first time Canvas opens after it was installed or reinstalled:
   * installing replaces the plugin's own folder, and with it the marker left
   * there (canvasLink.installMarkerPath). Canvases in MyStyle are untouched.
   */
  const isFirstOpenSinceInstall = async (): Promise<boolean> => {
    if (checkedInstall || pluginDirPath === null) {
      return false;
    }
    checkedInstall = true;
    const marker = installMarkerPath(pluginDirPath);
    if ((await store.readText(marker)) !== null) {
      return false;
    }
    await store.writeText(marker, 'opened');
    return true;
  };

  /**
   * The canvas a button press opens: Open Canvas the lassoed thumbnail's; any
   * other press the canvas last open, or a new, empty one on the first open
   * since an install.
   */
  const targetFor = async (dir: string, buttonId: number | null): Promise<string> => {
    const firstSinceInstall = await isFirstOpenSinceInstall();
    if (buttonId === BUTTON_ID_OPEN_LINKED) {
      return linkedCanvasId(dir);
    }
    if (firstSinceInstall) {
      logger.log(`${TAG} first open since install: a new canvas`);
      return newCanvasId();
    }
    return lastCanvasId(dir);
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

  /**
   * Hands the canvas the pen the note writes with, to set back as the canvas
   * closes: its pencil changes the firmware's pen, and the note sends its own
   * again only when one of its tools is tapped.
   */
  const rememberNotePen = async (): Promise<void> => {
    const pen = await host.notePen();
    if (pen !== null && !(await store.rememberNotePen(pen))) {
      logger.warn(`${TAG}[PEN] the note's pen ${JSON.stringify(pen)} is not one the canvas can set back`);
    }
  };

  const open = (buttonId: number | null): Promise<void> =>
    serially(async () => {
      await rememberNotePen();
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      await show(dir, await targetFor(dir, buttonId));
      logger.log(`${TAG} button=${buttonId} opened canvas=${canvasId}`);
    });

  const newCanvas = (): Promise<void> =>
    serially(async () => {
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      await show(dir, newCanvasId());
      logger.log(`${TAG} new canvas=${canvasId}`);
    });

  /** Inserts the thumbnail; the canvas it links to (and its folder), or null when it wasn't inserted. */
  const linkIntoNote = async (): Promise<{dir: string; linkedId: string} | null> => {
    const dir = await resolveCanvasDir();
    if (!dir) {
      return null;
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
      return null;
    }
    if (fromScratch) {
      // The scratch content lives on as the linked canvas, which the sidebar now reopens.
      canvasId = linkedId;
      await store.remove(canvasFilePath(dir, DEFAULT_CANVAS_ID));
    }
    await updateIndex(dir, current => withLastCanvas(current, linkedId));
    logger.log(`${TAG}[LINK] inserted thumbnail for canvas=${linkedId}`);
    return {dir, linkedId};
  };

  /**
   * After an insert: a pending link that remembers the pictures on the page,
   * so the thumbnail can be told apart once the note places it.
   */
  const leavePendingLink = async (dir: string, linkedId: string): Promise<void> => {
    const at = await host.currentPage();
    if (at === null) {
      logger.warn(`${TAG}[LINK] no note page; Open Canvas on this thumbnail will show the newest canvas`);
      return;
    }
    const knownPictureNumbers = await host.pagePictureNumbers(at);
    await updateIndex(dir, current => withPending(current, {...at, canvasId: linkedId, knownPictureNumbers}));
    logger.log(`${TAG}[LINK] pending link for canvas=${linkedId} page=${at.page} knownPictures=${knownPictureNumbers.length}`);
  };

  const saveToNote = (): Promise<boolean> => {
    if (saveToNotePending) {
      logger.log(`${TAG}[LINK] save to note already running; tap ignored`);
      return Promise.resolve(false);
    }
    saveToNotePending = true;
    let linked: {dir: string; linkedId: string} | null = null;
    return serially(async () => {
      try {
        linked = await linkIntoNote();
      } finally {
        saveToNotePending = false;
      }
    }).then(() => {
      const done = linked;
      if (done !== null) {
        // Queued, not awaited: reading the page takes seconds, and the note places the thumbnail only later anyway.
        serially(() => leavePendingLink(done.dir, done.linkedId));
      }
      return done !== null;
    });
  };

  const close = (): Promise<void> =>
    serially(async () => {
      // Never saved before a canvas was opened: the view would not hold it, and saving would overwrite it.
      const dir = hasOpened ? await resolveCanvasDir() : null;
      if (dir) {
        await store.save(canvasFilePath(dir, canvasId));
      }
      host.closeView();
    });

  return {open, newCanvas, saveToNote, close, currentCanvasId: () => canvasId};
}
