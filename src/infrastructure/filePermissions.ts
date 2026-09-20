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
 * A request for Canvas's file permissions: read, write and delete, one dialog
 * at a time and only for those not granted yet. It resolves true when write
 * and delete are both granted, and never rejects. A call while one is in
 * flight shares it; a call after it settled asks again, for any refused.
 */
export function createFileAccess(logger: Logger): () => Promise<boolean> {
  let inFlight: Promise<boolean> | null = null;

  // hasPermission: 0 not granted, 1 granted. requestPermission: 0 deny, 1 while using, 2 always.
  const grant = async (name: string): Promise<boolean> => {
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

  const ask = async (): Promise<boolean> => {
    await grant(FILE_READ);
    const canWrite = await grant(FILE_WRITE);
    const canDelete = await grant(FILE_DELETE);
    return canWrite && canDelete;
  };

  return () => {
    if (inFlight === null) {
      inFlight = ask().finally(() => {
        inFlight = null;
      });
    }
    return inFlight;
  };
}
