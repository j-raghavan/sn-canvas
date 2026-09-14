// HostPort over sn-plugin-lib. It normalizes the SDK's loosely typed
// {success, result} envelopes into plain values and never rejects.

import {PluginCommAPI, PluginFileAPI, PluginManager, PluginNoteAPI} from 'sn-plugin-lib';
import type {HostPort} from '../application/canvasSession';
import {uuidOf} from '../domain/canvasIndex';
import {resultOf, succeeded, type Logger} from '../sdk/types';

const TAG = '[SUPERCANVAS]';

export function createHostSdk(logger: Logger): HostPort {
  const attempt = async <T>(call: string, fallback: T, run: () => Promise<T>): Promise<T> => {
    try {
      return await run();
    } catch (error) {
      logger.warn(`${TAG} ${call} failed: ${String(error)}`);
      return fallback;
    }
  };

  return {
    pluginDir: () => attempt('getPluginDirPath', null, async () => (await PluginManager.getPluginDirPath()) || null),
    lassoedElements: () =>
      attempt('getLassoElements', [], async () => {
        const elements = resultOf<unknown[]>(await PluginCommAPI.getLassoElements());
        return Array.isArray(elements) ? elements : [];
      }),
    insertImage: path => attempt('insertImage', false, async () => succeeded(await PluginNoteAPI.insertImage(path))),
    // insertImage doesn't hand back the element it made; the page's last element is it (sn-tables does the same).
    lastElementUuid: () => attempt('getLastElement', null, async () => uuidOf(resultOf(await PluginFileAPI.getLastElement()))),
    closeView: () => {
      attempt('closePluginView', false, () => PluginManager.closePluginView());
    },
  };
}
