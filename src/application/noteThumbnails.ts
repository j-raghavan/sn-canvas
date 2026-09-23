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
  pictureNumbersOf,
  picturesOf,
  withLastCanvas,
  withPending,
  withoutCanvas,
  wouldClaimEverythingOn,
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
  const picturesOn = async (at: NotePage): Promise<unknown[]> => {
    await host.saveNote();
    return picturesOf(await host.pageElements(at));
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
    const already = at === null ? null : {at, pictures: await picturesOn(at)};
    // The scratch canvas is written under its new id, and kept there; any other is saved where it was loaded from.
    const saved = await (fromScratch ? store.saveAs(canvasFile) : store.save(canvasFile));
    const drawn = saved && (await store.renderThumbnail(thumbnail));
    const placed = drawn && (await host.insertImage(thumbnail));
    if (!placed) {
      logger.warn(`${TAG}[LINK] save to note failed; canvas=${shown.id()} unchanged`);
      if (fromScratch) {
        // Back to the scratch canvas it still is: the view keeps its file, and the new id's files go.
        if (saved) {
          await store.saveAs(canvasFilePath(dir, DEFAULT_CANVAS_ID));
        }
        await store.remove(canvasFile);
        await store.remove(thumbnail);
      }
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
    // A link that knew no pictures is asked first and answers every lasso on its page, so every
    // thumbnail there would open this canvas. Leaving none is not free: this thumbnail goes on the
    // page all the same and an older link, which did not know it, claims it and opens the older
    // canvas. One thumbnail answering wrongly is the price of not making all of them do it (#47).
    if (wouldClaimEverythingOn(await index.load(link.dir), at, knownPictureNumbers)) {
      logger.warn(
        `${TAG}[LINK] page=${at.page} read no pictures though links are waiting on it; no pending link for ` +
          `canvas=${link.linkedId}, so a thumbnail already linked on this page may answer for it`,
      );
      return;
    }
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
