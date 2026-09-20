// The file permissions Canvas uses. The firmware (Chauvet) denies shared
// storage (Note, MyStyle, Document, …) by default; only the plugin's own
// directory is exempt. As in sn-shapes and sn-mindmap, each permission is
// declared in PluginConfig.json under `uses-permissions` and requested at
// runtime, since declaration alone leaves hasPermission at 0. READ lets Canvas
// read the open note's page (getElements) to tell its thumbnails apart; WRITE
// and DELETE let it keep canvases in MyStyle/SnCanvas. A refused native
// file call throws SecurityException, which would kill the plugin, so nothing
// touches the canvas folder before the answer is in.
// Ref: docs.supernote.com/en/plugin-base/permission
//
// Asked for as soon as the plugin loads (index.js: at install, and each time
// the plugin starts), as sn-shapes and sn-mindmap do, and awaited by the
// session before it opens the first canvas. Both share one request (see
// wiring.ts), so the user sees each dialog once.

import {PluginManager} from 'sn-plugin-lib';
import {TAG} from '../diagnostics/log';
import type {Logger} from '../sdk/types';


export const FILE_READ = 'plugin.permission.FILE:READ';
export const FILE_WRITE = 'plugin.permission.FILE:WRITE';
export const FILE_DELETE = 'plugin.permission.FILE:DELETE';

/**
 * What Canvas asks the firmware for, by what it is about to do. Each permission
 * is asked for only when it is not granted yet, one dialog at a time, and a
 * permission already being asked for is awaited rather than asked again, so a
 * dialog never goes up twice. Neither call rejects.
 */
export type FileAccess = {
  /** Read, write and delete: what keeping canvases in MyStyle/SnCanvas needs. */
  forCanvases: () => Promise<boolean>;
  /**
   * Write, and nothing else: what putting a PDF in EXPORT needs. Asking to
   * delete for it showed a dialog about deleting files to someone who only
   * wanted a PDF, and refused the export when they said no (#17).
   */
  toWrite: () => Promise<boolean>;
};

export function createFileAccess(logger: Logger): FileAccess {
  // One promise per permission, while it is being asked for, so two things
  // wanting the same one wait on the same dialog.
  const asking = new Map<string, Promise<boolean>>();
  // And every dialog waits its turn, so a second caller arriving partway
  // through another's run never puts a second dialog up beside it.
  let queue: Promise<unknown> = Promise.resolve();

  // hasPermission: 0 not granted, 1 granted. requestPermission: 0 deny, 1 while using, 2 always.
  const ask = async (name: string): Promise<boolean> => {
    try {
      const had = await PluginManager.hasPermission(name);
      const got = had > 0 ? had : await PluginManager.requestPermission(name);
      logger.log(`${TAG}[PERM] ${name} -> ${got}`);
      return got > 0;
    } catch (error) {
      logger.warn(`${TAG}[PERM] ${name} failed: ${String(error)}`);
      return false;
    }
  };

  const grant = (name: string): Promise<boolean> => {
    const already = asking.get(name);
    if (already !== undefined) {
      return already;
    }
    // [ask] answers false rather than rejecting, whatever the firmware does, so the next dialog
    // follows this one however this one went and a refusal never stalls the queue.
    const answer = queue.then(() => ask(name)).finally(() => asking.delete(name));
    queue = answer;
    asking.set(name, answer);
    return answer;
  };

  return {
    forCanvases: async () => {
      await grant(FILE_READ);
      const canWrite = await grant(FILE_WRITE);
      const canDelete = await grant(FILE_DELETE);
      return canWrite && canDelete;
    },
    toWrite: () => grant(FILE_WRITE),
  };
}
