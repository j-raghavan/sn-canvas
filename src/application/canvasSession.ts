// The canvas session: which canvas the mounted view shows, and what a user
// does with it — open one (from a button press), start a new one, save it into
// the note as a linked thumbnail, and close (PRD FR11-FR13).
//
// Pure orchestration over two ports, tested with in-memory fakes;
// infrastructure/ holds the real adapters and wiring.ts plugs them in. Ports
// never reject: a failed step reports false/null, and the session logs and
// stops, so no user action can crash the plugin.
//
// One session per mounted canvas view (see ui/CanvasScreen.tsx). That is
// what makes "save the current canvas before switching" safe: the view is
// known to hold the session's canvas, never a freshly mounted, empty one.
//
// Nothing drawn is left out of reach: canvases live in MyStyle/SnCanvas,
// which outlasts an uninstall, the sidebar reopens the canvas the note it is
// opened in was last left showing, each note keeping its own (the first open
// after an install starts a new one instead), and
// "Open Canvas" finds a thumbnail's canvas through the link index
// (domain/canvasIndex.ts) and the tag it leaves on the picture itself
// (domain/canvasTag.ts).

import {
  claimPending,
  elementSummary,
  parseCanvasIndex,
  pictureNumbersOf,
  picturesOf,
  serializeCanvasIndex,
  lastCanvasFor,
  withLastCanvas,
  withPending,
  type CanvasIndex,
  type NotePage,
} from '../domain/canvasIndex';
import {
  DEFAULT_CANVAS_ID,
  LEGACY_SHARED_CANVAS_DIR,
  SHARED_CANVAS_DIR,
  canvasFilePath,
  canvasIdFromLassoedElements,
  canvasIdFromThumbnailPath,
  imagesPath,
  picturePathOf,
  indexPath,
  installMarkerPath,
  pdfPath,
  privateCanvasDir,
  thumbnailPath,
} from '../domain/canvasLink';
import {canvasIdFromTags, taggedCanvasId} from '../domain/canvasTag';
import {BUTTON_ID_OPEN_LINKED} from '../domain/entryPoints';
import type {Logger} from '../sdk/types';

/** The pen the note writes with, in the SDK's codes (sn-plugin-lib's PenInfo). */
export type NotePen = {type: number; width: number; color: number};

/** What "Save to Note" did: redrew the thumbnail already on the page, added one, or neither. */
export type SaveToNoteResult = 'refreshed' | 'inserted' | null;

/** Where an element links to (FR7); the shapes the canvas stores and the screen follows. */
export type ElementLink = {kind: 'note'; target: string; page: number};

/** Open the target where it was last left, rather than at a page of Canvas's choosing. */
export const LINK_LAST_PAGE = -1;

/** The live canvas view's persistence, by absolute path, the canvas folder's own small files, and the pen it gives back. */
export type CanvasStorePort = {
  /** Remembers the note's pen, for the canvas to set back as it gives the pen back to the note; false when it can't. */
  rememberNotePen: (pen: NotePen) => Promise<boolean>;
  /**
   * Shows the canvas saved at [path], its images in [imageDir], replacing the
   * view's content; a missing file shows an empty canvas and reports false.
   */
  load: (path: string, imageDir: string) => Promise<boolean>;
  /** Copies the image at [source] into [imageDir] and puts it on the canvas shown (FR22); false when it can't. */
  importImage: (source: string, imageDir: string) => Promise<boolean>;
  /** Writes the canvas shown to a one-page PDF at [path], fitted to its content (FR11); false when it can't. */
  exportPdf: (path: string) => Promise<boolean>;
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
  /** An image the user picks with the device's picker (FR22), by path; null when they cancel. */
  pickImage: () => Promise<string | null>;
  /** A note the user picks with the device's picker, by path; null when they cancel. */
  pickNote: () => Promise<string | null>;
  /** Opens the note at [path], at [page] (-1 keeps the page it was last left on); false when it would not open. */
  openNote: (path: string, page: number) => Promise<boolean>;
  lassoedElements: () => Promise<unknown[]>;
  insertImage: (path: string) => Promise<boolean>;
  /** The note page the user is on; null when the host can't say. */
  currentPage: () => Promise<NotePage | null>;
  /** The elements on page [at], in page order; empty when it can't be read. Slow: seconds, not milliseconds. */
  pageElements: (at: NotePage) => Promise<unknown[]>;
  /** Saves the note that is open, before its elements are modified; false when it can't be saved. */
  saveNote: () => Promise<boolean>;
  /**
   * Writes [canvasId] into the placed [picture]'s userData on page [at], showing
   * the PNG at [imagePath], so every later lasso of it names its canvas; true once written.
   */
  tagPicture: (picture: unknown, canvasId: string, at: NotePage, imagePath: string) => Promise<boolean>;
  /** Hides the plugin view, leaving Canvas running behind whatever the user goes to; true once hidden. */
  closeView: () => Promise<boolean>;
  /** Brings the plugin view back to the front, as it was left; true once shown. */
  showView: () => Promise<boolean>;
};

/**
 * The badge over a note that a followed link opened (#34): one tap on it
 * brings Canvas back. Shown and hidden here; its taps reach the screen.
 */
export type BackBadgePort = {
  /** Shows the badge reading [label] over the note at [notePath], until it is tapped or that note is left. */
  show: (label: string, notePath: string) => void;
  hide: () => void;
};

/** The back badge's taps, which the screen hands to its session's returnFromLink. */
export type BackBadgeTaps = {onTapped: (listener: () => void) => () => void};

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
  /** Puts an image the user picks on the canvas shown (FR22), copied into the canvas folder; true once it is there. */
  insertImage: () => Promise<boolean>;
  /** Asks for a note to link the selected element to (FR7); the link once one was picked, or null when the picker was cancelled. */
  pickNoteLink: () => Promise<ElementLink | null>;
  /**
   * Follows [link]: saves the canvas, steps Canvas aside for the note it names,
   * and puts the back badge over that note; false when it would not open, and
   * Canvas stays.
   */
  followLink: (link: ElementLink) => Promise<boolean>;
  /** The back badge was tapped: Canvas comes back as the link left it. */
  returnFromLink: () => Promise<void>;
  /**
   * Puts the canvas into the note as a thumbnail that links back to it: a
   * thumbnail of this canvas already on the page is redrawn where it sits
   * ('refreshed'), otherwise a new one is added ('inserted'). Null when neither
   * happened. Taps while one runs are ignored.
   */
  saveToNote: () => Promise<SaveToNoteResult>;
  /** Exports the canvas shown to a PDF in EXPORT, fitted to its content (FR11); its path, or null when none was written. */
  exportPdf: () => Promise<string | null>;
  /** Saves the canvas, then closes the plugin view whether or not the save worked. */
  close: () => Promise<void>;
  currentCanvasId: () => string;
};

const TAG = '[SNCANVAS]';

/** What the back badge says: where a tap on it goes. */
export const BACK_BADGE_LABEL = 'Canvas';

/** A thumbnail just put into the note: which canvas, whether it was redrawn in place, and the page read on the way. */
type NoteLink = {
  dir: string;
  linkedId: string;
  refreshed: boolean;
  known: {at: NotePage; pictures: unknown[]} | null;
};

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

  /** The newest saved canvas besides the scratch one: what to show when nothing recorded says which. */
  const newestCanvas = async (dir: string): Promise<string | null> =>
    (await store.savedCanvasIds(dir)).find(id => id !== DEFAULT_CANVAS_ID) ?? null;

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
      const newest = await newestCanvas(dir);
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
   * other press the note's own canvas, or a new, empty one on the first open
   * since an install for a note that has none yet.
   */
  const targetFor = async (dir: string, buttonId: number | null, at: NotePage | null): Promise<string> => {
    const firstSinceInstall = await isFirstOpenSinceInstall();
    if (buttonId === BUTTON_ID_OPEN_LINKED) {
      return linkedCanvasId(dir);
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
    if (hasOpened && target === canvasId) {
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
      await rememberNotePen();
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      // The note this open belongs to: which canvas it shows, and which note that canvas is recorded against.
      const at = await host.currentPage();
      await show(dir, await targetFor(dir, buttonId, at), at);
      logger.log(`${TAG} button=${buttonId} note=${at?.notePath ?? 'unknown'} opened canvas=${canvasId}`);
    });

  const newCanvas = (): Promise<void> =>
    serially(async () => {
      const dir = await resolveCanvasDir();
      if (!dir) {
        return;
      }
      await show(dir, newCanvasId(), await host.currentPage());
      logger.log(`${TAG} new canvas=${canvasId}`);
    });

  /**
   * The page the note is on and the pictures already there, read once per save
   * — it takes seconds — and only when a thumbnail of this canvas could
   * already be on the page. A canvas that has never been linked cannot have
   * one, so its save doesn't wait for the read at all.
   *
   * The note is saved first: getElements reads the note's file, so a thumbnail
   * placed since the last save is not in it yet, and the page comes back
   * looking emptier than it is (seen on device: a page with a thumbnail on it
   * read back as having no pictures at all).
   */
  const pageAlready = async (couldHoldOne: boolean): Promise<{at: NotePage; pictures: unknown[]} | null> => {
    if (!couldHoldOne) {
      return null;
    }
    await host.saveNote();
    const at = await host.currentPage();
    return at === null ? null : {at, pictures: picturesOf(await host.pageElements(at))};
  };

  /**
   * Whether [picture] is the thumbnail of canvas [id]. Its tag says so once one
   * was written (domain/canvasTag.ts), but tagging a lasso's copy doesn't take
   * on every firmware, so the picture's own path — the thumbnail PNG it was
   * inserted from — is read as well.
   */
  const showsCanvas = (picture: unknown, id: string): boolean =>
    taggedCanvasId(picture) === id || canvasIdFromThumbnailPath(picturePathOf(picture)) === id;

  /**
   * The thumbnail already in the note, redrawn: the note re-reads the PNG as it
   * takes the modified picture, so the picture keeps the place and the size the
   * user gave it and only what it shows changes. The picture handed over is the
   * note's own, as [pageAlready] read it, not a lasso's copy of one: a copy
   * carries a number in the page that the note doesn't match, and modifying it
   * changes nothing (seen on device as `modifyElements failed: {"result":[]}`).
   */
  const refreshThumbnail = async (picture: unknown, id: string, at: NotePage, imagePath: string): Promise<boolean> => {
    const refreshed = await host.tagPicture(picture, id, at, imagePath);
    if (!refreshed) {
      logger.warn(`${TAG}[LINK] could not refresh the thumbnail for canvas=${id} on page=${at.page}`);
    }
    return refreshed;
  };

  /** Puts the thumbnail in the note; the canvas it links to (and its folder), or null when it didn't go in. */
  const linkIntoNote = async (): Promise<NoteLink | null> => {
    const dir = await resolveCanvasDir();
    if (!dir) {
      return null;
    }
    // The scratch canvas gets an id of its own; a linked canvas re-links under the id it has.
    const fromScratch = canvasId === DEFAULT_CANVAS_ID;
    const linkedId = fromScratch ? newCanvasId() : canvasId;
    const canvasFile = canvasFilePath(dir, linkedId);
    const thumbnail = thumbnailPath(dir, linkedId);
    const already = await pageAlready(!fromScratch);
    const existing = already?.pictures.find(picture => showsCanvas(picture, linkedId)) ?? null;
    const drawn = (await store.save(canvasFile)) && (await store.renderThumbnail(thumbnail));
    const placed =
      drawn &&
      (existing !== null && already !== null
        ? await refreshThumbnail(existing, linkedId, already.at, thumbnail)
        : await host.insertImage(thumbnail));
    if (!placed) {
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
    await updateIndex(dir, current => withLastCanvas(current, linkedId, already?.at?.notePath ?? null));
    logger.log(`${TAG}[LINK] ${existing === null ? 'inserted' : 'refreshed'} thumbnail for canvas=${linkedId}`);
    // A refreshed picture is already tagged with its canvas; only a new one has to be claimed once the note places it.
    return {dir, linkedId, refreshed: existing !== null, known: already};
  };

  /**
   * After an insert: a pending link that remembers the pictures on the page,
   * so the thumbnail can be told apart once the note places it. It reuses the
   * page [linkIntoNote] already read, and reads it itself only when there was
   * nothing to reuse.
   */
  const leavePendingLink = async (link: NoteLink): Promise<void> => {
    const at = link.known?.at ?? (await host.currentPage());
    if (at === null) {
      logger.warn(`${TAG}[LINK] no note page; Open Canvas on this thumbnail will show the newest canvas`);
      return;
    }
    const knownPictureNumbers = pictureNumbersOf(link.known?.pictures ?? (await host.pageElements(at)));
    await updateIndex(link.dir, current => withPending(current, {...at, canvasId: link.linkedId, knownPictureNumbers}));
    logger.log(`${TAG}[LINK] pending link for canvas=${link.linkedId} page=${at.page} knownPictures=${knownPictureNumbers.length}`);
  };

  const saveToNote = (): Promise<SaveToNoteResult> => {
    if (saveToNotePending) {
      logger.log(`${TAG}[LINK] save to note already running; tap ignored`);
      return Promise.resolve(null);
    }
    saveToNotePending = true;
    let linked: NoteLink | null = null;
    return serially(async () => {
      try {
        linked = await linkIntoNote();
      } finally {
        saveToNotePending = false;
      }
    }).then(() => {
      const done = linked;
      if (done === null) {
        return null;
      }
      if (done.refreshed) {
        return 'refreshed';
      }
      // Queued, not awaited: reading the page takes seconds, and the note places the thumbnail only later anyway.
      serially(() => leavePendingLink(done));
      return 'inserted';
    });
  };

  /**
   * A note to link the selected element to. The page is the one the note was
   * last left on, so linking asks for nothing but the note itself.
   */
  const pickNoteLink = async (): Promise<ElementLink | null> => {
    const target = await host.pickNote();
    if (target === null) {
      logger.log(`${TAG}[LINK] no note picked; nothing linked`);
      return null;
    }
    logger.log(`${TAG}[LINK] linking to note=${target}`);
    return {kind: 'note', target, page: LINK_LAST_PAGE};
  };

  const followLink = async (link: ElementLink): Promise<boolean> => {
    let opened = false;
    await serially(async () => {
      await saveShown();
      // Canvas steps aside first: one brought back by the badge would otherwise stay in front of the note.
      await host.closeView();
      opened = await host.openNote(link.target, link.page);
      if (opened) {
        badge.show(BACK_BADGE_LABEL, link.target);
      } else {
        await host.showView();
      }
    });
    report(opened, `${TAG}[LINK] ${opened ? 'followed' : 'could not follow'} link to ${link.target} page=${link.page}`);
    return opened;
  };

  const returnFromLink = (): Promise<void> =>
    serially(async () => {
      badge.hide();
      report(await host.showView(), `${TAG}[LINK] back to canvas=${canvasId}`);
    });

  const insertImage = async (): Promise<boolean> => {
    // Picked outside the queue: the picker waits on the user, and must never hold up a save or a close.
    const source = await host.pickImage();
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
      await host.closeView();
    });

  return {
    open,
    newCanvas,
    saveToNote,
    insertImage,
    pickNoteLink,
    followLink,
    returnFromLink,
    exportPdf,
    close,
    currentCanvasId: () => canvasId,
  };
}
