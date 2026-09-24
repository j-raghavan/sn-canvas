// Which canvas the sidebar reopens, and the links waiting for "Save to Note"
// thumbnails to be placed (PRD FR12/FR13). The note hands Canvas nothing
// lasting about an inserted picture: it places it only once its placement
// lasso closes, and a lasso hands back a copy with a fresh uuid and a
// temporary file. What a lasso's copy keeps is the picture's number in its
// page. So "Save to Note" leaves a pending link that remembers the numbers of
// the pictures already on the page, and the first "Open Canvas" on a picture
// that wasn't among them claims it; the session then tags the picture itself
// with its canvas (domain/canvasTag.ts), which every later lasso carries.
// Kept as JSON beside the canvases (canvasLink.indexPath) and read
// defensively: a missing or damaged index is an empty one, and only
// well-formed entries get through (entries from builds that recorded uuids
// are dropped).

import {DEFAULT_CANVAS_ID, isCanvasId, picturePathOf} from './canvasLink';

/** A page of a note. */
export type NotePage = {readonly notePath: string; readonly page: number};

/** A thumbnail inserted into a note, waiting to be placed and claimed. */
export type PendingLink = NotePage & {
  readonly canvasId: string;
  /** The numbers in the page of the pictures already there when it was inserted: the thumbnail is a picture that isn't one of them. */
  readonly knownPictureNumbers: readonly number[];
};

export type CanvasIndex = {
  /**
   * The canvas the sidebar reopens in each note, by note path: a canvas belongs
   * to the note it was made in, so opening Canvas in one note never shows
   * another note's work.
   */
  readonly lastByNote: Readonly<Record<string, string>>;
  /**
   * Every canvas shown in each note, most recent first: a note's canvases
   * stay within reach from Canvas itself, whatever became of their thumbnails
   * (#30). Canvases deleted since are left for the reader to skip.
   */
  readonly canvasesByNote: Readonly<Record<string, readonly string[]>>;
  /**
   * The canvas the sidebar reopened before canvases belonged to notes. Kept for
   * the note that claims it first (see [lastCanvasFor]), and used when the host
   * cannot say which note is open.
   */
  readonly lastCanvasId: string | null;
  /**
   * Canvases shown when the host could not say which note was open, so nothing
   * knows whose they are (#49). They belong to nobody and go on belonging to
   * nobody: a canvas the next note to ask was handed would show that note
   * another note's drawing, and lose it the moment that note saved.
   *
   * This is also what tells those apart from what an older build left behind.
   * Both come out as a last canvas with no note records at all, and the old one
   * has to stay adoptable or an upgrade orphans the drawing in it. A build that
   * knew about this wrote the canvas down here; a build that did not, could not.
   */
  readonly unowned: readonly string[];
  /** Oldest first. */
  readonly pending: readonly PendingLink[];
};

/** A pending link a lassoed picture claims: the index without it, its canvas, and the picture to tag with it. */
export type Claim = {readonly index: CanvasIndex; readonly canvasId: string; readonly picture: unknown};

export const EMPTY_INDEX: CanvasIndex = {canvasesByNote: {}, lastByNote: {}, lastCanvasId: null, unowned: [], pending: []};

/** Pending links kept, the newest: one for a thumbnail deleted before it was ever opened would otherwise wait for good. */
export const MAX_PENDING = 20;

// sn-plugin-lib's Element.TYPE_PICTURE.
const PICTURE_TYPE = 200;

/** A note page from the host's current file path and page number; null unless both are usable. */
export function notePageOf(notePath: unknown, page: unknown): NotePage | null {
  return typeof notePath === 'string' && notePath !== '' && Number.isInteger(page) && (page as number) >= 0
    ? {notePath, page: page as number}
    : null;
}

/** A note element's number in its page (sn-plugin-lib `Element.numInPage`, from 1), or null when it has none. */
export function numberOf(element: unknown): number | null {
  const num = (element as {numInPage?: unknown} | null | undefined)?.numInPage;
  return Number.isInteger(num) && (num as number) >= 1 ? (num as number) : null;
}

const pendingLinkOf = (value: unknown): PendingLink | null => {
  const saved = (value ?? {}) as {canvasId?: unknown; notePath?: unknown; page?: unknown; knownPictureNumbers?: unknown};
  const at = notePageOf(saved.notePath, saved.page);
  const known = saved.knownPictureNumbers;
  const knownPictureNumbers =
    Array.isArray(known) && known.every(num => numberOf({numInPage: num}) !== null) ? (known as number[]) : null;
  return at !== null && isCanvasId(saved.canvasId) && knownPictureNumbers !== null
    ? {...at, canvasId: saved.canvasId, knownPictureNumbers}
    : null;
};

/** The index saved as [json]; the empty index for no file or anything unreadable. */
export function parseCanvasIndex(json: string | null): CanvasIndex {
  if (json === null) {
    return EMPTY_INDEX;
  }
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    return EMPTY_INDEX;
  }
  const saved = (raw ?? {}) as {
    canvasesByNote?: unknown;
    lastByNote?: unknown;
    lastCanvasId?: unknown;
    unowned?: unknown;
    pending?: unknown;
  };
  const pending = Array.isArray(saved.pending)
    ? saved.pending
        .map(pendingLinkOf)
        .filter((link): link is PendingLink => link !== null)
        .slice(-MAX_PENDING)
    : [];
  const lastByNote = lastByNoteOf(saved.lastByNote);
  return {
    canvasesByNote:
      saved.canvasesByNote === undefined ? canvasesKnownFrom(lastByNote, pending) : canvasesByNoteOf(saved.canvasesByNote),
    lastByNote,
    lastCanvasId: isCanvasId(saved.lastCanvasId) ? saved.lastCanvasId : null,
    // Absent is empty, which is what an older build's index reads as, and is the whole point: an
    // index with nothing here is one whose last canvas may still be adopted (#49).
    unowned: Array.isArray(saved.unowned) ? saved.unowned.filter(isCanvasId) : [],
    pending,
  };
}

/** Each note's canvases as saved, keeping only well-formed lists of canvas ids. */
const canvasesByNoteOf = (value: unknown): Record<string, string[]> => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return {};
  }
  const lists = Object.entries(value as Record<string, unknown>)
    .filter((pair): pair is [string, unknown[]] => pair[0] !== '' && Array.isArray(pair[1]))
    .map(([note, ids]) => [note, ids.filter(isCanvasId)] as const);
  return Object.fromEntries(lists);
};

/**
 * Each note's canvases for an index saved before they were kept: the canvas it
 * reopens, then those its thumbnails' pending links name, newest first.
 */
const canvasesKnownFrom = (lastByNote: Record<string, string>, pending: readonly PendingLink[]): Record<string, string[]> => {
  const known: Record<string, string[]> = {};
  const add = (note: string, canvasId: string) => {
    known[note] = [...(known[note] ?? []).filter(id => id !== canvasId), canvasId];
  };
  pending.forEach(link => add(link.notePath, link.canvasId));
  Object.entries(lastByNote).forEach(([note, canvasId]) => add(note, canvasId));
  return Object.fromEntries(Object.entries(known).map(([note, ids]) => [note, [...ids].reverse()]));
};

/** The canvas each note reopens, keeping only well-formed pairs; an index saved before notes owned canvases has none. */
const lastByNoteOf = (value: unknown): Record<string, string> => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return {};
  }
  const pairs = Object.entries(value as Record<string, unknown>).filter(
    (pair): pair is [string, string] => pair[0] !== '' && isCanvasId(pair[1]),
  );
  return Object.fromEntries(pairs);
};

export function serializeCanvasIndex(index: CanvasIndex): string {
  return JSON.stringify(index);
}

/**
 * [index] with [canvasId] as the canvas the sidebar reopens: in [notePath] when
 * the host could say which note is open, and as the plain last canvas either
 * way, which is what an open with no note to go by falls back to.
 */
export function withLastCanvas(index: CanvasIndex, canvasId: string, notePath: string | null): CanvasIndex {
  if (notePath === null) {
    // Nobody's, and written down as nobody's (#49). Left as only the last canvas it would be handed
    // to the next note that asked, which would show that note this drawing and lose it on its save.
    return {
      ...index,
      lastCanvasId: canvasId,
      unowned: index.unowned.includes(canvasId) ? index.unowned : [...index.unowned, canvasId],
    };
  }
  const others = (index.canvasesByNote[notePath] ?? []).filter(id => id !== canvasId);
  return {
    ...index,
    canvasesByNote: {...index.canvasesByNote, [notePath]: [canvasId, ...others]},
    lastByNote: {...index.lastByNote, [notePath]: canvasId},
    lastCanvasId: canvasId,
    // A note has it now, so it is no longer nobody's: the note's own records say whose it is from
    // here, and leaving it listed as unowned as well would be two answers to one question.
    unowned: index.unowned.filter(id => id !== canvasId),
  };
}

/** The canvases shown in [notePath], most recent first; none when the host cannot say which note is open. */
export function canvasesIn(index: CanvasIndex, notePath: string | null): readonly string[] {
  return notePath === null ? [] : (index.canvasesByNote[notePath] ?? []);
}

/**
 * Whether any note has been shown [canvasId]. A canvas goes on belonging to a note after it
 * moves on to another, because its file still holds what was drawn on it. Handing it to a
 * second note would show that note the first one's work, and lose it the moment the second
 * note saves (#46). A canvas shown with no note to go by belongs to nobody, since nothing
 * knows whose it is.
 *
 * Both records are read, though one note's canvases already cover what it reopens, because a
 * half-written index can have the two disagree and this is read back defensively.
 */
export function belongsToANote(index: CanvasIndex, canvasId: string): boolean {
  return (
    Object.values(index.lastByNote).includes(canvasId) ||
    Object.values(index.canvasesByNote).some(ids => ids.includes(canvasId))
  );
}

/**
 * Whether a note asking for a canvas may be given [canvasId]: no note has been shown it, and it is
 * not one nobody owns (#49). The two are different questions, which is why they are asked
 * separately: a canvas shown with no note known belongs to no note, and is still not going spare.
 *
 * A canvas an older build left as its last is free, and has to stay free, or upgrading orphans
 * whatever was drawn in it. That one is not written down as nobody's, because that build had
 * nowhere to write it.
 */
export function isFreeToAdopt(index: CanvasIndex, canvasId: string): boolean {
  return !belongsToANote(index, canvasId) && !index.unowned.includes(canvasId);
}

/** Whether the scratch canvas is nobody's, so the next note to ask may be given it (#46). */
export function isScratchCanvasFree(index: CanvasIndex): boolean {
  return isFreeToAdopt(index, DEFAULT_CANVAS_ID);
}

/**
 * The canvas [notePath] has of its own, or null when it has none. Strictly its own: unlike
 * [lastCanvasFor] it adopts nothing an older build left lying around.
 */
export function ownCanvasOf(index: CanvasIndex, notePath: string | null): string | null {
  return notePath === null ? null : (index.lastByNote[notePath] ?? null);
}

/**
 * [index] with [canvasId] out of every note's canvases, out of what each reopens, and out of
 * the plain last canvas. Save to Note gives the scratch canvas an id of its own and deletes
 * the file it had, so that id stops meaning anything and has to stop belonging to anyone with
 * it: otherwise the first note to use the scratch canvas would hold it for good and no other
 * note would ever be given one.
 *
 * Pending links are left as they are. None can name the scratch canvas, because a save from
 * it always mints a new id first, so there is nothing there to take out.
 */
export function withoutCanvas(index: CanvasIndex, canvasId: string): CanvasIndex {
  const canvasesByNote: Record<string, string[]> = {};
  Object.entries(index.canvasesByNote).forEach(([notePath, ids]) => {
    const kept = ids.filter(id => id !== canvasId);
    if (kept.length > 0) {
      canvasesByNote[notePath] = kept;
    }
  });
  const lastByNote = Object.fromEntries(Object.entries(index.lastByNote).filter(([, id]) => id !== canvasId));
  return {
    ...index,
    canvasesByNote,
    lastByNote,
    lastCanvasId: index.lastCanvasId === canvasId ? null : index.lastCanvasId,
    // Out of here too: a retired id means nothing to anyone, nobody included. Left behind it would
    // go on refusing the id to the next note that could have had it (#49).
    unowned: index.unowned.filter(id => id !== canvasId),
  };
}

/**
 * The canvas [notePath] reopens, or null when that note has none of its own
 * yet. A canvas recorded before canvases belonged to notes goes to the first
 * note that asks and stays with it, so upgrading does not strand the canvas
 * that was open at the time.
 */
export function lastCanvasFor(index: CanvasIndex, notePath: string | null): string | null {
  if (notePath === null) {
    return index.lastCanvasId;
  }
  const own = ownCanvasOf(index, notePath);
  if (own !== null) {
    return own;
  }
  const last = index.lastCanvasId;
  if (last === null) {
    return null;
  }
  // The canvas last open goes to the first note to ask for it and stays with it. One this note has already
  // been shown is its own to reopen, whatever it reopens by default; one another note has been shown is not
  // on offer, or a canvas whose file still holds that note's drawing would be handed over (#46).
  return canvasesIn(index, notePath).includes(last) || isFreeToAdopt(index, last) ? last : null;
}

/** The links waiting on page [at], oldest first. */
export function pendingOn(index: CanvasIndex, at: NotePage): readonly PendingLink[] {
  return index.pending.filter(link => link.notePath === at.notePath && link.page === at.page);
}

/**
 * The links waiting on page [at], newest first, which is the order both the matching and a lasso
 * ask them in: the newest knew the most thumbnails, so it is about the fewest pictures.
 */
const newestFirstOn = (index: CanvasIndex, at: NotePage): readonly PendingLink[] => [...pendingOn(index, at)].reverse();

/**
 * Whether the picture numbered [num] could be [link]'s thumbnail: the thumbnail is the picture that
 * was not on the page when the link was left, so any number it did not know could be its. A picture
 * with no number at all (null) could be any of them.
 *
 * The one rule, asked in both directions: [stillWaiting] asks it of every picture for one link, and
 * [claimPending] of every link for one picture.
 *
 * The null arm is spelled out rather than left to `includes`, which would answer the same for a
 * list of numbers. Nothing can tell the two apart, so it is here to say what a numberless picture
 * means and not because the answer would change.
 */
const couldBe = (link: PendingLink, num: number | null): boolean =>
  num === null || !link.knownPictureNumbers.includes(num);

/**
 * The links on a page that a picture could still be waiting to be claimed by, given [pictures],
 * every picture on that page now. A link could be about any picture it did not already know, and
 * one thumbnail is one picture, so this is the largest set of links that can be given a picture
 * each; whatever cannot be is waiting for a thumbnail the page no longer has (#47).
 *
 * Taking each link's first free picture in turn is not enough. A newer link can know fewer numbers
 * than an older one, because a picture's number is its place in the page and a deletion renumbers
 * what is left, so their claims are not nested and a link can take the one picture another had left.
 * Hence the reassignment below: a link may displace one already placed, as long as that one can be
 * put somewhere else.
 */
const stillWaiting = (onPage: readonly PendingLink[], pictures: readonly number[]): readonly PendingLink[] => {
  const takenBy = new Map<number, PendingLink>();
  const couldBeIts = (link: PendingLink) => pictures.filter(num => couldBe(link, num));
  const place = (link: PendingLink, tried: Set<number>): boolean =>
    couldBeIts(link).some(num => {
      if (tried.has(num)) {
        return false;
      }
      tried.add(num);
      const holder = takenBy.get(num);
      if (holder === undefined || place(holder, tried)) {
        takenBy.set(num, link);
        return true;
      }
      return false;
    });
  // Newest first: it knew the most thumbnails, so it has the fewest pictures to be placed among.
  const waiting = new Set([...onPage].reverse().filter(link => place(link, new Set())));
  return onPage.filter(link => waiting.has(link));
};

/**
 * [index] with [link] waiting for its thumbnail; only the newest [MAX_PENDING] are kept.
 *
 * Links the page can no longer account for go at the same time. [link]'s known numbers are every
 * picture on that page as it is now, so they say which of the links already there still have a
 * thumbnail to be claimed by; the rest never will be, and until this they stayed for good and
 * filled the index until the cap started dropping the ones that were still good (#47).
 *
 * A page with no pictures on it has nothing any of its links can ever be claimed by, so they all
 * go and the one being left is the only one there, which is what makes it safe for it to know no
 * pictures. That rests on the read being true, which is why an empty one is taken twice before it
 * is believed ([createNoteThumbnails]'s picturesOn).
 */
export function withPending(index: CanvasIndex, link: PendingLink): CanvasIndex {
  const kept = stillWaiting(pendingOn(index, link), link.knownPictureNumbers);
  const spent = new Set(pendingOn(index, link).filter(other => !kept.includes(other)));
  return {...index, pending: [...index.pending.filter(other => !spent.has(other)), link].slice(-MAX_PENDING)};
}

/**
 * The claim a lassoed picture makes: the newest link pending on page [at] that
 * didn't know the picture's number. Newest first matters with two thumbnails
 * on one page: the newer one's link knew the older picture, so each claims its
 * own. Null when nothing lassoed can claim a pending link.
 */
export function claimPending(index: CanvasIndex, lassoed: readonly unknown[], at: NotePage): Claim | null {
  for (const picture of picturesOf(lassoed)) {
    const num = numberOf(picture);
    const claimant = newestFirstOn(index, at).find(link => couldBe(link, num));
    if (claimant !== undefined) {
      return {index: {...index, pending: index.pending.filter(link => link !== claimant)}, canvasId: claimant.canvasId, picture};
    }
  }
  return null;
}

const isPicture = (element: unknown): boolean => {
  if ((element as {type?: unknown} | null | undefined)?.type === PICTURE_TYPE) {
    return true;
  }
  const path = picturePathOf(element);
  return path !== undefined && path !== null;
};

/** The pictures among note [elements], in their order. */
export function picturesOf(elements: readonly unknown[]): unknown[] {
  return elements.filter(isPicture);
}

/** The numbers in the page of the pictures among note [elements], in their order. */
export function pictureNumbersOf(elements: readonly unknown[]): number[] {
  return picturesOf(elements)
    .map(numberOf)
    .filter((num): num is number => num !== null);
}

/**
 * A note element in brief, for the log: which element it is and where it sits
 * (uuid, type, number in its page, page, picture rect and path, user data),
 * never what it shows. The path is what says whether a picture on the page is a
 * Canvas thumbnail, so it is worth having in the log when one is not found.
 */
export function elementSummary(element: unknown): Record<string, unknown> {
  const e = (element ?? {}) as {
    uuid?: unknown;
    type?: unknown;
    numInPage?: unknown;
    pageNum?: unknown;
    userData?: unknown;
    picture?: {rect?: unknown} | null;
  };
  return {
    uuid: e.uuid,
    type: e.type,
    num: e.numInPage,
    page: e.pageNum,
    rect: e.picture?.rect,
    path: picturePathOf(element),
    userData: e.userData,
  };
}
