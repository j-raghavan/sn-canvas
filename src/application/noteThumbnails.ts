// The canvas in the note: "Save to Note" puts a thumbnail of the canvas on the
// page, and "Open Canvas" on a lassoed thumbnail says which canvas it shows.
//
// Canvas only ever adds to a note. Writing into one (modifyElements) destroys
// the page's pictures on this firmware, so a canvas saved again leaves a new
// thumbnail beside the old one, and what a note holds is the user's to keep or
// delete (#30). Which thumbnail belongs to which canvas is therefore not
// written down in the note but kept here, as a link pending on the page: the
// thumbnail is the picture that was not there before it went in
// (domain/canvasIndex.ts).

import {
  claimPending,
  elementSummary,
  pendingOn,
  pictureNumbersOf,
  picturesOf,
  withLastCanvas,
  withPending,
  withoutCanvas,
  type CanvasIndex,
  type NotePage,
} from '../domain/canvasIndex';
import {
  DEFAULT_CANVAS_ID,
  canvasFilePath,
  canvasIdFromLassoedElements,
  thumbnailPath,
} from '../domain/canvasLink';
import {canvasIdFromTags} from '../domain/canvasTag';
import type {Logger} from '../sdk/types';
import type {CanvasStorePort, HostPort, SaveToNoteResult} from './ports';
import {newestCanvas} from './savedCanvases';

const TAG = '[SNCANVAS]';

/** A thumbnail just put into the note: which canvas, and the page as it was read before it went in. */
type NoteLink = {
  dir: string;
  linkedId: string;
  known: {at: NotePage; pictures: unknown[]} | null;
};

/** The canvas the view shows, which "Save to Note" saves and may rename (the scratch canvas gets an id of its own). */
export type ShownCanvas = {
  id: () => string;
  /** The canvas shown is now [canvasId]: the scratch canvas, saved into a note under its own id. */
  rename: (canvasId: string) => void;
};

/** The link index, as the session keeps it: read once, written through. */
export type IndexStore = {
  load: (dir: string) => Promise<CanvasIndex>;
  update: (dir: string, change: (current: CanvasIndex) => CanvasIndex) => Promise<void>;
};

export type NoteThumbnailDeps = {
  store: CanvasStorePort;
  host: HostPort;
  logger: Logger;
  newCanvasId: () => string;
  /** The folder canvases live in, settled by the session; null without one. */
  canvasDir: () => Promise<string | null>;
  index: IndexStore;
  shown: ShownCanvas;
  /** The session's one-at-a-time queue: a save must not run halfway through another. */
  serially: (task: () => Promise<void>) => Promise<void>;
};

export type NoteThumbnails = {
  /** Puts a thumbnail of the canvas shown into the note (FR12/FR13); null when none went in. Taps while one runs are ignored. */
  saveToNote: () => Promise<SaveToNoteResult>;
  /** The canvas a lassoed thumbnail shows, for "Open Canvas"; the newest canvas when nothing names one. */
  lassoedCanvasId: (dir: string) => Promise<string>;
};

export function createNoteThumbnails({
  store,
  host,
  logger,
  newCanvasId,
  canvasDir,
  index,
  shown,
  serially,
}: NoteThumbnailDeps): NoteThumbnails {
  let saveToNotePending = false;

  /**
   * The pictures on page [at]. It takes seconds, so it is read only when it
   * decides something.
   *
   * The note is saved first: getElements reads the note's file, so a thumbnail
   * placed since the last save is not in it yet, and the page comes back
   * looking emptier than it is (seen on device: a page with a thumbnail on it
   * read back as having no pictures at all).
   */
  const picturesOn = async (at: NotePage, dir: string): Promise<unknown[]> => {
    const read = async () => {
      await host.saveNote();
      return picturesOf(await host.pageElements(at));
    };
    const found = await read();
    // A read that finds none is read again when links are waiting on that page. No pictures is what
    // this firmware gives for pages that have them, and it is also the truth for a page whose
    // pictures have all been deleted; the two decide opposite things about the links waiting there,
    // so it is worth the seconds to ask twice. Only the failing path pays (#47).
    if (found.length > 0 || pendingOn(await index.load(dir), at).length === 0) {
      return found;
    }
    const again = await read();
    logger.log(`${TAG}[LINK] page=${at.page} read no pictures with links waiting; read again and found ${again.length}`);
    return again;
  };

  /**
   * The canvas of the pending link a lassoed thumbnail claims, if one waits for
   * it on this page. The link stays pending, to be claimed again the next time
   * that thumbnail is lassoed: Canvas writes nothing into the note to mark it,
   * which destroys the page's pictures on this firmware (#30).
   */
  const claimLassoed = async (dir: string, elements: readonly unknown[]): Promise<string | null> => {
    const current = await index.load(dir);
    const at = current.pending.length > 0 ? await host.currentPage() : null;
    const claim = at === null ? null : claimPending(current, elements, at);
    return claim?.canvasId ?? null;
  };

  const lassoedCanvasId = async (dir: string): Promise<string> => {
    const elements = await host.lassoedElements();
    const linkedId =
      canvasIdFromTags(elements) ?? canvasIdFromLassoedElements(elements) ?? (await claimLassoed(dir, elements));
    logger.log(
      `${TAG}[LINK] lassoed=${elements.length} elements=${JSON.stringify(elements.map(elementSummary))} canvas=${linkedId}`,
    );
    // Nothing names it (a thumbnail from a build without links, say): the newest canvas is the best guess.
    return linkedId ?? (await newestCanvas(store, dir)) ?? DEFAULT_CANVAS_ID;
  };

  /**
   * Undoes a Save to Note that the note refused, for the scratch canvas alone: the forward path's
   * inverse, named so that what it does not undo is visible rather than buried in an else-branch.
   *
   * Normally the canvas goes back to being the scratch one and the new id's files go. When the
   * write that puts it back fails, it cannot: saveAs rebinds the view to what it wrote, and on
   * failure the native side puts the binding back where it was, which is the new file the first
   * save bound it to. Removing that file would take away the only one the view can still write to,
   * and later saves would go to the scratch file and be refused, since a canvas file only ever
   * holds the canvas it was loaded from (#30). The drawing stays as the new canvas instead, which
   * is recorded, since a file holding real work that no note lists is the loss #46 is about coming
   * back by another route. The scratch id keeps its place: its file is still there.
   *
   * The id the drawing kept when it could not go back, or null when the canvas is the scratch one still.
   */
  const unwindScratchSave = async (
    dir: string,
    {
      linkedId,
      canvasFile,
      thumbnail,
      saved,
      at,
    }: {linkedId: string; canvasFile: string; thumbnail: string; saved: boolean; at: NotePage | null},
  ): Promise<string | null> => {
    const restored = saved && (await store.saveAs(canvasFilePath(dir, DEFAULT_CANVAS_ID)));
    if (!saved || restored) {
      // The new id never became a canvas, so its files go. Both removes run: a file left here is one
      // no note lists, and an unlisted file is what newestCanvas can hand to a note it was never
      // drawn for (#46). The success path says so when its delete fails; so does this one.
      const fileGone = await store.remove(canvasFile);
      const thumbGone = await store.remove(thumbnail);
      if (!fileGone || !thumbGone) {
        logger.warn(`${TAG}[LINK] canvas=${linkedId} was not saved to the note but its files are still here`);
      }
      return null;
    }
    // The thumbnail stays: the drawing is this canvas now, so the picture rendered of it is a true
    // one, and it is what the note's canvas list draws until the next save renders another.
    shown.rename(linkedId);
    const into = at ?? (await host.currentPage());
    await index.update(dir, current => withLastCanvas(current, linkedId, into?.notePath ?? null));
    return linkedId;
  };

  /** Puts the thumbnail in the note; the canvas it links to (and its folder), or null when it didn't go in. */
  const linkIntoNote = async (): Promise<NoteLink | null> => {
    const dir = await canvasDir();
    if (!dir) {
      return null;
    }
    // The scratch canvas gets an id of its own; a linked canvas re-links under the id it has.
    const fromScratch = shown.id() === DEFAULT_CANVAS_ID;
    const linkedId = fromScratch ? newCanvasId() : shown.id();
    const canvasFile = canvasFilePath(dir, linkedId);
    const thumbnail = thumbnailPath(dir, linkedId);
    // The page as it is before the thumbnail goes in: the new picture is the one that was not there (leavePendingLink).
    const at = await host.currentPage();
    const already = at === null ? null : {at, pictures: await picturesOn(at, dir)};
    // The scratch canvas is written under its new id, and kept there; any other is saved where it was loaded from.
    const saved = await (fromScratch ? store.saveAs(canvasFile) : store.save(canvasFile));
    const drawn = saved && (await store.renderThumbnail(thumbnail));
    const placed = drawn && (await host.insertImage(thumbnail));
    if (!placed) {
      const stayedAs = fromScratch ? await unwindScratchSave(dir, {linkedId, canvasFile, thumbnail, saved, at}) : null;
      // After the unwind, because it is the unwind that decides whether the canvas is still the one
      // we started on: saying "unchanged" before it has run is a claim this path cannot make.
      logger.warn(
        stayedAs === null
          ? `${TAG}[LINK] save to note failed; canvas=${shown.id()} unchanged`
          : `${TAG}[LINK] save to note failed; ${DEFAULT_CANVAS_ID} could not be put back, so the drawing is canvas=${stayedAs}`,
      );
      return null;
    }
    if (fromScratch) {
      // The scratch content lives on as the linked canvas, which the sidebar now reopens, and its old file goes.
      shown.rename(linkedId);
    }
    // Its id is given up only once its file really has gone. A file still standing there still holds the
    // drawing, and handing the id to the next note that asks is the very loss this is about (#46).
    const scratchGone = fromScratch && (await store.remove(canvasFilePath(dir, DEFAULT_CANVAS_ID)));
    if (fromScratch && !scratchGone) {
      logger.warn(`${TAG}[LINK] ${DEFAULT_CANVAS_ID} is still on disk, so it stays this note's`);
    }
    // The note it went into reopens it: asked of the host when no page was read (the scratch canvas's first save),
    // or the note would go on naming the scratch canvas, whose file is gone, and reopen empty (#30).
    const into = already?.at ?? (await host.currentPage());
    // One write, because the two halves are one rule: the scratch canvas's id is retired, since its file has
    // just gone and nothing may go on belonging to it (#46), and the note is recorded against the new id. Written
    // separately there is a moment where the index holds neither, which reads as a build from before the index.
    await index.update(dir, current =>
      withLastCanvas(scratchGone ? withoutCanvas(current, DEFAULT_CANVAS_ID) : current, linkedId, into?.notePath ?? null),
    );
    logger.log(`${TAG}[LINK] inserted thumbnail for canvas=${linkedId}`);
    return {dir, linkedId, known: already};
  };

  /**
   * After an insert: a pending link that remembers the pictures the page held
   * before it ([linkIntoNote] read them), so the thumbnail can be told apart
   * once the note places it. Without that read there is nothing to tell it
   * apart by, and no link to leave.
   */
  const leavePendingLink = async (link: NoteLink): Promise<void> => {
    if (link.known === null) {
      logger.warn(`${TAG}[LINK] no note page; Open Canvas on this thumbnail will show the newest canvas`);
      return;
    }
    const {at, pictures} = link.known;
    const knownPictureNumbers = pictureNumbersOf(pictures);
    await index.update(link.dir, current => withPending(current, {...at, canvasId: link.linkedId, knownPictureNumbers}));
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
      // Queued, not awaited: reading the page takes seconds, and the note places the thumbnail only later anyway.
      serially(() => leavePendingLink(done));
      return 'inserted';
    });
  };

  return {saveToNote, lassoedCanvasId};
}
