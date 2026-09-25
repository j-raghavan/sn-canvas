// What the canvas session talks to: the live canvas view's persistence
// (CanvasStorePort), the Supernote host (HostPort) and the badge that leads
// back from a followed link (BackBadgePort), with the shapes they carry.
//
// Ports never reject: a failed step reports false, null or empty, so the
// session branches on plain values instead of wrapping calls in try/catch.
// infrastructure/ holds the real adapters, and wiring.ts plugs them in.

import type {NotePage} from '../domain/canvasIndex';

/** The pen the note writes with, in the SDK's codes (sn-plugin-lib's PenInfo). */
export type NotePen = {type: number; width: number; color: number};

/** What "Save to Note" did: put a thumbnail of the canvas into the note, or nothing. */
export type SaveToNoteResult = 'inserted' | null;

// Where an element links to lives in the domain, since a canvas file holds it; re-exported so the
// session and its adapters take everything they speak in from one place.
export {LINK_LAST_PAGE, type ElementLink} from '../domain/canvasLink';

/** The live canvas view's persistence, by absolute path, the canvas folder's own small files, and the pen it gives back. */
export type CanvasStorePort = {
  /** Remembers the note's pen, for the canvas to set back as it gives the pen back to the note; false when it can't. */
  rememberNotePen: (pen: NotePen) => Promise<boolean>;
  /**
   * Shows the canvas saved at [path], its images in [imageDir], replacing the
   * view's content; a missing file shows an empty canvas and reports false, as
   * does a load that never reached the view.
   */
  load: (path: string, imageDir: string) => Promise<boolean>;
  /** Copies the image at [source] into [imageDir] and puts it on the canvas shown (FR22); false when it can't. */
  importImage: (source: string, imageDir: string) => Promise<boolean>;
  /** Writes the canvas shown to a one-page PDF at [path], fitted to its content (FR11); false when it can't. */
  exportPdf: (path: string) => Promise<boolean>;
  /**
   * Writes the canvas shown to [path], the file it was loaded from. Any other
   * file is refused (false), never written: a canvas file only ever holds the
   * canvas loaded from it (#30).
   */
  save: (path: string) => Promise<boolean>;
  /**
   * Writes the canvas shown to [path] and leaves the view where it is: a copy taken for a file the
   * canvas has not become yet (#62), so that giving up on it needs nothing put back. Only ever
   * creates: it will not write over a file, whoever's canvas that file holds. Narrower than the
   * rule [save] keeps, which is that the view only ever writes the canvas it is holding (#30).
   */
  writeTo: (path: string) => Promise<boolean>;
  /**
   * Makes [path] the file the view holds, writing nothing: the canvas becomes the one already
   * written there (#62). Nothing to put back should it fail, since nothing was written.
   */
  bindTo: (path: string) => Promise<boolean>;
  /** Whether the view shows the canvas saved at [path]. */
  holds: (path: string) => Promise<boolean>;
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
  /** Asks for what keeping canvases in shared storage needs, if not granted already; true when it may write and delete there. */
  requestCanvasFolderAccess: () => Promise<boolean>;
  /** Asks only to write, which is all putting a PDF in EXPORT needs (#17); true when it may write it. */
  requestExportAccess: () => Promise<boolean>;
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
  /** Hides the plugin view, leaving Canvas running behind whatever the user goes to; true once hidden. */
  closeView: () => Promise<boolean>;
  /** Brings the plugin view back to the front, as it was left; true once shown. */
  showView: () => Promise<boolean>;
};

/**
 * The way back from followed links (#34): the badge over a note a link opened,
 * whose taps reach the screen, and the last leg of each trip back.
 */
export type BackBadgePort = {
  /** Shows the badge reading [label] over the note at [notePath], until it is tapped or that note is left. */
  show: (label: string, notePath: string) => void;
  hide: () => void;
  /**
   * Once [notePath] has been asked to open, brings Canvas up over it as soon as
   * it is on screen; true when Canvas came up over that note. Done natively: JS
   * runs no timers while Canvas is covered.
   */
  arriveOver: (notePath: string) => Promise<boolean>;
};

/** A canvas made in the open note, as the note's canvas list shows it (#30). */
export type NoteCanvas = {
  canvasId: string;
  /** When it was made; null for the scratch canvas. */
  madeAt: number | null;
  /** Where its thumbnail is drawn once it has been saved to the note; the file may not exist before. */
  thumbnail: string;
  isShown: boolean;
};

/** The back badge's taps, which the screen hands to its session's goBack. */
export type BackBadgeTaps = {onTapped: (listener: () => void) => () => void};
