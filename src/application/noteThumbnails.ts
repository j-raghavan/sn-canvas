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
  mayNoteHave,
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
    if (linkedId !== null) {
      return linkedId;
    }
    // Nothing names it (a thumbnail from a build without links, say): the newest canvas is the best
    // guess, but only one this note may be shown. Taking the newest file whatever it is would hand
    // over another note's drawing, or one drawn when nothing knew whose it was (#49).
    const current = await index.load(dir);
    const here = (await host.currentPage())?.notePath ?? null;
    const guess = await newestCanvas(store, dir, id => mayNoteHave(current, here, id));
    if (guess === null) {
      logger.log(`${TAG}[LINK] nothing this note may be shown is saved; a canvas of its own`);
    }
    return guess ?? DEFAULT_CANVAS_ID;
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
    // A copy under the new id, taken without moving the view off the scratch canvas: the canvas only
    // becomes the new one once the note has actually taken the thumbnail, so a note that refuses
    // leaves nothing to put back (#62). Any other canvas is saved to the file it was loaded from.
    const saved = await (fromScratch ? store.writeTo(canvasFile) : store.save(canvasFile));
    const drawn = saved && (await store.renderThumbnail(thumbnail));
    const placed = drawn && (await host.insertImage(thumbnail));
    if (!placed) {
      if (fromScratch) {
        // The copy was never anything: it goes, and the canvas is the scratch one still, because it
        // never stopped being. A file left here is one no note lists, and an unlisted file is what
        // the newest-canvas guess can hand to a note it was never drawn for (#46).
        const fileGone = await store.remove(canvasFile);
        const thumbGone = await store.remove(thumbnail);
        if (!fileGone || !thumbGone) {
          logger.warn(`${TAG}[LINK] canvas=${linkedId} was not saved to the note but its files are still here`);
        }
      }
      logger.warn(`${TAG}[LINK] save to note failed; canvas=${shown.id()} unchanged`);
      return null;
    }
    // It went in, so the canvas becomes the new one and the view moves to the file already written.
    // Should that fail the thumbnail is still in the note and the drawing still in its file, so the
    // canvas is recorded below all the same; what does not happen is the scratch canvas being given
    // up, since the view is still holding it.
    const became = !fromScratch || (await store.saveAs(canvasFile));
    if (became && fromScratch) {
      shown.rename(linkedId);
    } else if (!became) {
      logger.warn(`${TAG}[LINK] canvas=${linkedId} is in the note, but the canvas shown is ${shown.id()} still`);
    }
    // Its id is given up only once its file really has gone. A file still standing there still holds the
    // drawing, and handing the id to the next note that asks is the very loss this is about (#46).
    const scratchGone = became && fromScratch && (await store.remove(canvasFilePath(dir, DEFAULT_CANVAS_ID)));
    if (fromScratch && became && !scratchGone) {
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
