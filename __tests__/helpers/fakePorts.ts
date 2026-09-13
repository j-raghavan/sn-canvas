// In-memory fakes for the canvas session's ports. `files` stands in for the
// plugin directory and `shown` for what the native view displays, so tests
// assert outcomes (what got saved where) rather than call sequences.

import type {CanvasStorePort, HostPort} from '../../src/application/canvasSession';
import type {Logger} from '../../src/sdk/types';

export type FakeStore = CanvasStorePort & {
  files: Map<string, string>;
  shown: string;
  failing: Set<keyof CanvasStorePort>;
};

export const createFakeStore = (initial: Record<string, string> = {}): FakeStore => {
  const files = new Map(Object.entries(initial));
  const failing = new Set<keyof CanvasStorePort>();
  const store: FakeStore = {
    files,
    failing,
    shown: '',
    async load(path) {
      store.shown = files.get(path) ?? '';
      return files.has(path);
    },
    async save(path) {
      if (failing.has('save')) {
        return false;
      }
      files.set(path, store.shown);
      return true;
    },
    async remove(path) {
      return files.delete(path);
    },
    async renderThumbnail(path) {
      if (failing.has('renderThumbnail')) {
        return false;
      }
      files.set(path, `png:${store.shown}`);
      return true;
    },
  };
  return store;
};

export type FakeHost = HostPort & {
  dir: string | null;
  lassoed: unknown[];
  inserted: string[];
  insertSucceeds: boolean;
  closeCount: number;
};

export const createFakeHost = (): FakeHost => {
  const host: FakeHost = {
    dir: '/plugin',
    lassoed: [],
    inserted: [],
    insertSucceeds: true,
    closeCount: 0,
    async pluginDir() {
      return host.dir;
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
    closeView() {
      host.closeCount += 1;
    },
  };
  return host;
};

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
