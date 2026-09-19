// In-memory fakes for the canvas session's ports. `files` stands in for the
// plugin directory and `shown` for what the native view displays, so tests
// assert outcomes (what got saved where) rather than call sequences.

import type {CanvasStorePort, HostPort, NotePen} from '../../src/application/canvasSession';
import type {NotePage} from '../../src/domain/canvasIndex';
import {isCanvasId} from '../../src/domain/canvasLink';
import type {Logger} from '../../src/sdk/types';

export type FakeStore = CanvasStorePort & {
  /** In the order written, so the last entry is the newest file. */
  files: Map<string, string>;
  shown: string;
  failing: Set<keyof CanvasStorePort>;
  /** The note's pen the canvas would set back. */
  notePen: NotePen | null;
  /** The images folder of the canvas shown. */
  imageDir: string | null;
  /** The images put on the canvas: their source and the folder they were copied into. */
  imported: Array<{source: string; imageDir: string}>;
  /** The PDFs the canvas was exported to, by path. */
  exported: string[];
};

export const createFakeStore = (initial: Record<string, string> = {}): FakeStore => {
  const files = new Map(Object.entries(initial));
  const failing = new Set<keyof CanvasStorePort>();
  const write = (path: string, content: string) => {
    files.delete(path);
    files.set(path, content);
  };
  const store: FakeStore = {
    files,
    failing,
    shown: '',
    notePen: null,
    imageDir: null,
    imported: [],
    exported: [],
    async exportPdf(path) {
      if (failing.has('exportPdf')) {
        return false;
      }
      store.exported.push(path);
      return true;
    },
    async rememberNotePen(pen) {
      if (failing.has('rememberNotePen')) {
        return false;
      }
      store.notePen = pen;
      return true;
    },
    async load(path, imageDir) {
      store.shown = files.get(path) ?? '';
      store.imageDir = imageDir;
      return files.has(path);
    },
    async importImage(source, imageDir) {
      if (failing.has('importImage')) {
        return false;
      }
      store.imported.push({source, imageDir});
      return true;
    },
    async save(path) {
      if (failing.has('save')) {
        return false;
      }
      write(path, store.shown);
      return true;
    },
    async remove(path) {
      return files.delete(path);
    },
    async renderThumbnail(path) {
      if (failing.has('renderThumbnail')) {
        return false;
      }
      write(path, `png:${store.shown}`);
      return true;
    },
    async readText(path) {
      return files.get(path) ?? null;
    },
    async writeText(path, text) {
      write(path, text);
      return true;
    },
    async adoptFolder(from, to) {
      let moved = 0;
      for (const [path, content] of [...files]) {
        const target = `${to}${path.slice(from.length)}`;
        if (path.startsWith(`${from}/`) && !files.has(target)) {
          files.delete(path);
          write(target, content);
          moved += 1;
        }
      }
      return moved;
    },
    async savedCanvasIds(canvasDir) {
      return [...files.keys()]
        .reverse()
        .filter(path => path.startsWith(`${canvasDir}/`) && path.endsWith('.json'))
        .map(path => path.slice(canvasDir.length + 1, -'.json'.length))
        .filter(isCanvasId);
    },
  };
  return store;
};

export type FakeHost = HostPort & {
  dir: string | null;
  lassoed: unknown[];
  inserted: string[];
  insertSucceeds: boolean;
  /** Whether the user grants file write access (else canvases stay in the plugin folder). */
  fileWrite: boolean;
  accessRequests: number;
  /** The note page the user is on. */
  page: NotePage | null;
  /** The elements on that page, as getElements reports them ([notePicture] builds one). */
  elements: unknown[];
  /** The notes saved before their elements were modified. */
  noteSaves: number;
  tagSucceeds: boolean;
  /** The pictures tagged with a canvas, in order. */
  tagged: Array<{canvasId: string; picture: unknown; imagePath: string}>;
  closeCount: number;
  /** The pen the note writes with, as getPenInfo reports it. */
  pen: NotePen | null;
  /** The image the user picks; null when they cancel the picker. */
  picked: string | null;
  /** The note the user picks to link to; null when they cancel the picker. */
  pickedNote: string | null;
  /** The notes opened by following a link, in order. */
  openedNotes: Array<{path: string; page: number}>;
  openNoteSucceeds: boolean;
};

export const createFakeHost = (): FakeHost => {
  const host: FakeHost = {
    dir: '/plugin',
    pen: null,
    picked: null,
    pickedNote: null,
    openedNotes: [],
    openNoteSucceeds: true,
    async notePen() {
      return host.pen;
    },
    async pickImage() {
      return host.picked;
    },
    async pickNote() {
      return host.pickedNote;
    },
    async openNote(path, page) {
      if (!host.openNoteSucceeds) {
        return false;
      }
      host.openedNotes.push({path, page});
      return true;
    },
    lassoed: [],
    inserted: [],
    insertSucceeds: true,
    fileWrite: false,
    accessRequests: 0,
    page: {notePath: '/note.note', page: 0},
    elements: [],
    noteSaves: 0,
    tagSucceeds: true,
    tagged: [],
    closeCount: 0,
    async pluginDir() {
      return host.dir;
    },
    async requestFileAccess() {
      host.accessRequests += 1;
      return host.fileWrite;
    },
    async lassoedElements() {
      return host.lassoed;
    },
    async insertImage(path) {
      if (!host.insertSucceeds) {
        return false;
      }
      host.inserted.push(path);
      return true;
    },
    async currentPage() {
      return host.page;
    },
    async pageElements() {
      return host.elements;
    },
    async saveNote() {
      host.noteSaves += 1;
      return true;
    },
    async tagPicture(picture, canvasId, _at, imagePath) {
      if (!host.tagSucceeds) {
        return false;
      }
      host.tagged.push({canvasId, picture, imagePath});
      return true;
    },
    closeView() {
      host.closeCount += 1;
    },
  };
  return host;
};

/** A picture element on a note page, as getElements reports one: sn-plugin-lib's TYPE_PICTURE, numbered from 1. */
export const notePicture = (numInPage: number, userData?: string): Record<string, unknown> => ({
  type: 200,
  numInPage,
  picture: {picturePath: `/note/pictures/${numInPage}.png`, rect: {left: 0, top: 0, right: 100, bottom: 100}},
  ...(userData === undefined ? {} : {userData}),
});

export type RecordingLogger = Logger & {lines: string[]};

export const createRecordingLogger = (): RecordingLogger => {
  const lines: string[] = [];
  return {
    lines,
    log: msg => {
      lines.push(`log ${msg}`);
    },
    warn: msg => {
      lines.push(`warn ${msg}`);
    },
    error: msg => {
      lines.push(`error ${msg}`);
    },
  };
};
