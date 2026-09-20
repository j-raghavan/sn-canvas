// The canvas session: which canvas the mounted view shows, and what a user
// does with it. It opens one (from a button press), starts a new one, switches
// to another of the note's canvases, exports, and closes (PRD FR11-FR13), and
// hands the rest to the modules beside it: the canvas in the note
// (noteThumbnails.ts), following links and stepping back (linkNavigation.ts),
// and whether Canvas is on screen at all (pluginView.ts). The ports they all
// talk through are in ports.ts.
//
// Pure orchestration, tested with in-memory fakes; infrastructure/ holds the
// real adapters and wiring.ts plugs them in. Ports never reject: a failed step
// reports false/null, and the session logs and stops, so no user action can
// crash the plugin.
//
// One session per mounted canvas view (see ui/CanvasScreen.tsx), and a canvas
// file only ever holds the canvas loaded from it: the view is asked whether it
// still holds one before it is saved, and a save the view refuses is a save
// that never happens (#30).
//
// Nothing drawn is left out of reach: canvases live in MyStyle/SnCanvas, which
// outlasts an uninstall; the sidebar reopens the canvas the note it is opened
// in was last left showing, each note keeping its own (the first open after an
// install starts a new one instead); every canvas a note has ever shown is
// listed under "Canvases in this note"; and "Open Canvas" finds a thumbnail's
// canvas through the link index (domain/canvasIndex.ts).

import {
  canvasesIn,
  parseCanvasIndex,
  serializeCanvasIndex,
  lastCanvasFor,
  withLastCanvas,
  type CanvasIndex,
  type NotePage,
} from '../domain/canvasIndex';
import {
  DEFAULT_CANVAS_ID,
  LEGACY_SHARED_CANVAS_DIR,
  SHARED_CANVAS_DIR,
  canvasFilePath,
  canvasMadeAt,
  imagesPath,
  indexPath,
  installMarkerPath,
  pdfPath,
  privateCanvasDir,
  thumbnailPath,
} from '../domain/canvasLink';
import type {TrailStep} from '../domain/linkTrail';
import {BUTTON_ID_OPEN_LINKED} from '../domain/entryPoints';
import {createLinkNavigation} from './linkNavigation';
import {createNoteThumbnails} from './noteThumbnails';
import {createPluginView} from './pluginView';
import {newestCanvas} from './savedCanvases';
import type {Logger} from '../sdk/types';
import {
  LINK_LAST_PAGE,
  type BackBadgePort,
  type BackBadgeTaps,
  type CanvasStorePort,
  type ElementLink,
  type HostPort,
  type NoteCanvas,
  type NotePen,
  type SaveToNoteResult,
} from './ports';

export type {BackBadgePort, BackBadgeTaps, CanvasStorePort, ElementLink, HostPort, NoteCanvas, NotePen, SaveToNoteResult};
export {LINK_LAST_PAGE};

export type CanvasSessionDeps = {
  store: CanvasStorePort;
  host: HostPort;
  badge: BackBadgePort;
  newCanvasId: () => string;
  logger: Logger;
  /** The device's clock, which names each PDF export; the real one unless given. */
  now?: () => Date;
};

export type CanvasSession = {
  /** Shows the canvas a button press asks for: Open Canvas the lassoed thumbnail's, any other press (or none yet) the open note's own. */
  open: (buttonId: number | null) => Promise<void>;
  /** Saves the canvas shown, then shows a new, empty one. */
  newCanvas: () => Promise<void>;
  /**
   * Starts a fresh sheet: the canvas shown keeps what is on it, in its own
   * file, and an empty one takes its place. Clearing never empties the canvas
   * a note's thumbnail points at (#44); the old one stays in the note's list.
   */
  clearCanvas: () => Promise<void>;
  /** Puts an image the user picks on the canvas shown (FR22), copied into the canvas folder; true once it is there. */
  insertImage: () => Promise<boolean>;
  /** Asks for a note to link the selected element to (FR7); the link once one was picked, or null when the picker was cancelled. */
  pickNoteLink: () => Promise<ElementLink | null>;
  /**
   * Follows [link]: saves the canvas, steps Canvas aside for the note it names,
   * leaves a step on the trail back, and puts the back badge over that note;
   * false when it would not open, and Canvas stays.
   */
  followLink: (link: ElementLink) => Promise<boolean>;
  /**
   * The canvases made in the open note, the one shown first, then the most
   * recently shown: each stays within reach whatever became of its thumbnail.
   */
  canvasesHere: () => Promise<NoteCanvas[]>;
  /** Saves the canvas shown, then shows [canvasId]. */
  switchTo: (canvasId: string) => Promise<void>;
  /** Where one step back goes (the last link's canvas and note page), or null with no link to go back along. */
  backTo: () => TrailStep | null;
  /**
   * Takes one step back along the trail: that note, at its page, with Canvas
   * over it showing that canvas. One at a time: a call while one runs is ignored.
   */
  goBack: () => Promise<void>;
  /**
   * Puts the canvas into the note as a thumbnail that links back to it, always
   * as a new picture: what a note holds is the user's to keep or delete, and
   * Canvas only ever adds to it. Editing a canvas and saving it again leaves an
   * up to date thumbnail beside the old one. Null when nothing went in. Taps
   * while one runs are ignored.
   */
  saveToNote: () => Promise<SaveToNoteResult>;
  /** Exports the canvas shown to a PDF in EXPORT, fitted to its content (FR11); its path, or null when none was written. */
  exportPdf: () => Promise<string | null>;
  /** Saves the canvas, then closes the plugin view whether or not the save worked. */
  close: () => Promise<void>;
  currentCanvasId: () => string;
};

const TAG = '[SNCANVAS]';

export function createCanvasSession({
  store,
  host,
  badge,
  newCanvasId,
  logger,
  now = () => new Date(),
}: CanvasSessionDeps): CanvasSession {
  let canvasId = DEFAULT_CANVAS_ID;
  let hasOpened = false;
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

  /** Logs [message] as news when [ok], and as a warning when not. */
  const report = (ok: boolean, message: string): void => (ok ? logger.log(message) : logger.warn(message));

  /** Saves the canvas shown. Never before one was opened: the view would not hold it, and saving would overwrite it. */
  const saveShown = async (): Promise<void> => {
    const dir = hasOpened ? await resolveCanvasDir() : null;
    if (dir) {
      await store.save(canvasFilePath(dir, canvasId));
    }
  };

  /** Moves the canvas files in [from] into MyStyle/SnCanvas, keeping any it has already; logs how many moved. */
  const takeIn = async (from: string, what: string): Promise<void> => {
    const moved = await store.adoptFolder(from, SHARED_CANVAS_DIR);
    if (moved > 0) {
      logger.log(`${TAG} moved ${moved} files from ${what} to ${SHARED_CANVAS_DIR}`);
    }
  };

  /**
   * The folder canvases live in, settled on first use: MyStyle/SnCanvas
   * with file write access, taking in the canvases kept in the plugin's own
   * folder and those in MyStyle under the plugin's old name (SnSuperCanvas);
   * without it, that plugin folder. File access is asked for here, before any
   * canvas is read or written, so no save can race the permission dialog into
   * the wrong folder.
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
        await takeIn(privateCanvasDir(pluginDir), 'the plugin folder');
      }
      await takeIn(LEGACY_SHARED_CANVAS_DIR, LEGACY_SHARED_CANVAS_DIR);
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

/**
   * The canvas the note at [at] reopens: the one it was last left showing. A
   * note that has none of its own gets a new, empty canvas, so opening Canvas
   * in a note never shows another note's work. With no note to go by (the host
   * could not say which is open) the canvas last open anywhere is the best
   * guess there is.
   */
  const canvasForNote = async (dir: string, at: NotePage | null): Promise<string> => {
    const saved = await loadIndex(dir);
    const recorded = lastCanvasFor(saved, at?.notePath ?? null);
    if (recorded !== null) {
      return recorded;
    }
    // Nothing recorded at all is a build from before the index: the canvas saved last is the likeliest, and goes to
    // the first note that asks, as a recorded last canvas does, so upgrading never shows an empty canvas over saved work.
    const nothingRecorded = saved.lastCanvasId === null && Object.keys(saved.lastByNote).length === 0;
    if (at === null || nothingRecorded) {
      const newest = await newestCanvas(store, dir);
      if (newest !== null || at === null) {
        return newest ?? DEFAULT_CANVAS_ID;
      }
    }
    // The scratch canvas goes to the first note to ask for it, and is free again once Save to Note gives it an
    // id of its own. Any other note gets a canvas of its own rather than being shown the first note's work.
    const spokenFor = Object.values((await loadIndex(dir)).lastByNote);
    if (!spokenFor.includes(DEFAULT_CANVAS_ID)) {
      return DEFAULT_CANVAS_ID;
    }
    logger.log(`${TAG} no canvas for this note yet: a new one`);
    return newCanvasId();
  };

  // Canvas on screen or stepped aside, and the links followed out of it.
  const view = createPluginView(host);
  const links = createLinkNavigation({
    host,
    badge,
    logger,
    view,
    canvasDir: resolveCanvasDir,
    shown: {id: () => canvasId, show: (dir, target, at) => show(dir, target, at)},
    saveShown,
    serially,
    report,
  });

  // Save to Note, and which canvas a lassoed thumbnail shows (application/noteThumbnails.ts).
  const thumbnails = createNoteThumbnails({
    store,
    host,
    logger,
    newCanvasId,
    canvasDir: resolveCanvasDir,
    index: {load: loadIndex, update: updateIndex},
    shown: {
      id: () => canvasId,
      rename: id => {
        canvasId = id;
      },
    },
    serially,
  });

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
   * other press the note's own canvas, or a new, empty one on the first open
   * since an install for a note that has none yet.
   */
  const targetFor = async (dir: string, buttonId: number | null, at: NotePage | null): Promise<string> => {
    const firstSinceInstall = await isFirstOpenSinceInstall();
    if (buttonId === BUTTON_ID_OPEN_LINKED) {
      return thumbnails.lassoedCanvasId(dir);
    }
    // A note's own canvas outlasts a reinstall (an update is one): only a note with none starts afresh.
    const own = at === null ? undefined : (await loadIndex(dir)).lastByNote[at.notePath];
    if (firstSinceInstall && own === undefined) {
      logger.log(`${TAG} first open since install: a new canvas`);
      return newCanvasId();
    }
    return own ?? canvasForNote(dir, at);
  };

  /** Shows [target], saving the canvas shown first, and records it as the canvas the note at [at] reopens. */
  const show = async (dir: string, target: string, at: NotePage | null): Promise<void> => {
    // Only when the view does hold it: one Canvas came back to without it, told it did, would be saved over it (#30).
    if (hasOpened && target === canvasId && (await store.holds(canvasFilePath(dir, canvasId)))) {
      return;
    }
    await saveShown();
    canvasId = target;
    hasOpened = true;
    await store.load(canvasFilePath(dir, canvasId), imagesPath(dir));
    await updateIndex(dir, current => withLastCanvas(current, canvasId, at?.notePath ?? null));
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
      // Canvas is back by another way than the badge, which has nothing left to do.
      badge.hide();
      view.openedByNote();
      await rememberNotePen();
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      // The note this open belongs to: which canvas it shows, and which note that canvas is recorded against.
      const at = await host.currentPage();
      await show(dir, await targetFor(dir, buttonId, at), at);
      links.keepTrailAt(buttonId === BUTTON_ID_OPEN_LINKED ? null : at);
      logger.log(`${TAG} button=${buttonId} note=${at?.notePath ?? 'unknown'} opened canvas=${canvasId}`);
    });

  /**
   * Puts the canvas shown into its own file and shows a new, empty one in its
   * place. What the old canvas holds is left as it was, so a thumbnail in a
   * note still opens the drawing it shows (#44).
   */
  const freshSheet = (what: string): Promise<void> =>
    serially(async () => {
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      const kept = canvasId;
      await show(dir, newCanvasId(), await host.currentPage());
      links.clearTrail();
      logger.log(`${TAG} ${what} canvas=${canvasId}, ${kept} kept`);
    });

  const newCanvas = (): Promise<void> => freshSheet('new');

  /** Clear starts a fresh sheet rather than emptying the canvas in place, so what a note points at survives it (#44). */
  const clearCanvas = (): Promise<void> => freshSheet('cleared,');

  const canvasesHere = async (): Promise<NoteCanvas[]> => {
    const dir = await resolveCanvasDir();
    if (dir === null) {
      return [];
    }
    const at = await host.currentPage();
    const saved = new Set(await store.savedCanvasIds(dir));
    // The canvas shown may be new and unsaved yet; any other must still have its file.
    const ids = [canvasId, ...canvasesIn(await loadIndex(dir), at?.notePath ?? null).filter(id => id !== canvasId && saved.has(id))];
    return ids.map(id => ({canvasId: id, madeAt: canvasMadeAt(id), thumbnail: thumbnailPath(dir, id), isShown: id === canvasId}));
  };

  const switchTo = (target: string): Promise<void> =>
    serially(async () => {
      const dir = await resolveCanvasDir();
      if (dir === null) {
        return;
      }
      const at = await host.currentPage();
      await show(dir, target, at);
      links.clearTrail();
      logger.log(`${TAG} switched to canvas=${canvasId} note=${at?.notePath ?? 'unknown'}`);
    });

  const insertImage = async (): Promise<boolean> => {
    // Picked outside the queue: the picker waits on the user, and must never hold up a save or a close.
    const source = await view.pickOver(host.pickImage);
    if (source === null) {
      return false;
    }
    let inserted = false;
    await serially(async () => {
      const dir = await resolveCanvasDir();
      if (dir === null) {
        return;
      }
      inserted = await store.importImage(source, imagesPath(dir));
      if (inserted) {
        logger.log(`${TAG}[IMAGE] inserted ${source}`);
      } else {
        logger.warn(`${TAG}[IMAGE] could not insert ${source}`);
      }
    });
    return inserted;
  };

  const exportPdf = async (): Promise<string | null> => {
    let exported: string | null = null;
    await serially(async () => {
      if (!(await host.requestFileAccess())) {
        logger.warn(`${TAG}[PDF] no file write access; nothing exported`);
        return;
      }
      const path = pdfPath(now());
      if (await store.exportPdf(path)) {
        exported = path;
        logger.log(`${TAG}[PDF] exported ${path}`);
      } else {
        logger.warn(`${TAG}[PDF] could not export ${path}`);
      }
    });
    return exported;
  };

  const close = (): Promise<void> =>
    serially(async () => {
      await saveShown();
      await view.stepAside();
    });

  return {
    open,
    newCanvas,
    clearCanvas,
    saveToNote: thumbnails.saveToNote,
    insertImage,
    pickNoteLink: links.pickNoteLink,
    followLink: links.followLink,
    canvasesHere,
    switchTo,
    backTo: links.backTo,
    goBack: links.goBack,
    exportPdf,
    close,
    currentCanvasId: () => canvasId,
  };
}
