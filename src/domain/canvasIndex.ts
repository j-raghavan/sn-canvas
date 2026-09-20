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

import {isCanvasId, picturePathOf} from './canvasLink';

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
  /** Oldest first. */
  readonly pending: readonly PendingLink[];
};

/** A pending link a lassoed picture claims: the index without it, its canvas, and the picture to tag with it. */
export type Claim = {readonly index: CanvasIndex; readonly canvasId: string; readonly picture: unknown};

export const EMPTY_INDEX: CanvasIndex = {canvasesByNote: {}, lastByNote: {}, lastCanvasId: null, pending: []};

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
  const saved = (raw ?? {}) as {canvasesByNote?: unknown; lastByNote?: unknown; lastCanvasId?: unknown; pending?: unknown};
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
    return {...index, lastCanvasId: canvasId};
  }
  const others = (index.canvasesByNote[notePath] ?? []).filter(id => id !== canvasId);
  return {
    ...index,
    canvasesByNote: {...index.canvasesByNote, [notePath]: [canvasId, ...others]},
    lastByNote: {...index.lastByNote, [notePath]: canvasId},
    lastCanvasId: canvasId,
  };
}

/** The canvases shown in [notePath], most recent first; none when the host cannot say which note is open. */
export function canvasesIn(index: CanvasIndex, notePath: string | null): readonly string[] {
  return notePath === null ? [] : (index.canvasesByNote[notePath] ?? []);
}

/**
 * Whether any note has been shown [canvasId]. A canvas stays claimed once a note has shown
 * it, even after that note moves on to another, because its file still holds what was drawn
 * on it. Handing it to a second note would show that note the first one's work, and lose it
 * the moment the second note saves (#46). A canvas shown with no note to go by is claimed by
 * nobody, since nothing knows whose it is.
 */
export function isClaimed(index: CanvasIndex, canvasId: string): boolean {
  return (
    Object.values(index.lastByNote).includes(canvasId) ||
    Object.values(index.canvasesByNote).some(ids => ids.includes(canvasId))
  );
}

/**
 * [index] with [canvasId] forgotten by every note, and by the plain last canvas. Save to
 * Note gives the scratch canvas an id of its own and deletes the file it had, so that id
 * stops meaning anything and has to stop being claimed with it: otherwise the first note to
 * use the scratch canvas would hold it for good and no other note would ever be given one.
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
  const own = index.lastByNote[notePath];
  if (own !== undefined) {
    return own;
  }
  // Claimed counts every note that has shown it, not just the one that reopens it, or a canvas a note has
  // moved on from would be handed to the next note to ask while its file still holds the drawing (#46).
  const unclaimed = index.lastCanvasId !== null && !isClaimed(index, index.lastCanvasId);
  return unclaimed ? index.lastCanvasId : null;
}

/** [index] with [link] waiting for its thumbnail; only the newest [MAX_PENDING] are kept. */
export function withPending(index: CanvasIndex, link: PendingLink): CanvasIndex {
  return {...index, pending: [...index.pending, link].slice(-MAX_PENDING)};
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
    const claimant = [...index.pending]
      .reverse()
      .find(
        link => link.notePath === at.notePath && link.page === at.page && (num === null || !link.knownPictureNumbers.includes(num)),
      );
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
