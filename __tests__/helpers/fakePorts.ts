// In-memory fakes for the canvas session's ports. `files` stands in for the
// plugin directory and `shown` for what the native view displays, so tests
// assert outcomes (what got saved where) rather than call sequences.

import type {CanvasStorePort, HostPort} from '../../src/application/canvasSession';
import {isCanvasId} from '../../src/domain/canvasLink';
import type {Logger} from '../../src/sdk/types';

export type FakeStore = CanvasStorePort & {
  /** In the order written, so the last entry is the newest file. */
  files: Map<string, string>;
  shown: string;
  failing: Set<keyof CanvasStorePort>;
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
    async load(path) {
      store.shown = files.get(path) ?? '';
      return files.has(path);
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
  /** What getLastElement reports for the image just inserted. */
  lastUuid: string | null;
  closeCount: number;
};

export const createFakeHost = (): FakeHost => {
  const host: FakeHost = {
    dir: '/plugin',
    lassoed: [],
    inserted: [],
    insertSucceeds: true,
    lastUuid: 'u-1',
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
    async lastElementUuid() {
      return host.lastUuid;
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
