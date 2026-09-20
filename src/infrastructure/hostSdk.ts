// HostPort over sn-plugin-lib. It normalizes the SDK's loosely typed
// {success, result} envelopes into plain values and never rejects.
//
// File access is the one shared request from wiring.ts (see
// infrastructure/filePermissions.ts), injected so index.js and the session ask
// through the same one.

import {PluginCommAPI, PluginFileAPI, PluginManager, PluginNoteAPI, RattaFileSelector} from 'sn-plugin-lib';
import type {HostPort, NotePen} from '../application/canvasSession';
import {elementSummary, notePageOf, picturesOf} from '../domain/canvasIndex';
import {TAG} from '../diagnostics/log';
import type {FileAccess} from './filePermissions';
import {resultOf, succeeded, type Logger} from '../sdk/types';
import {neverThrows} from './neverThrows';


// RattaFileSelector.selectFile's selectType for picking a single file.
const SINGLE_FILE = 1;

/** True for a getPenInfo result with a number for each of the pen's codes. */
const isNotePen = (pen: unknown): pen is NotePen =>
  typeof pen === 'object' &&
  pen !== null &&
  (['type', 'width', 'color'] as const).every(code => Number.isFinite((pen as Record<string, unknown>)[code]));

export function createHostSdk(logger: Logger, access: FileAccess): HostPort {
  const attempt = neverThrows(logger);

  const listOf = (response: unknown): unknown[] => {
    const items = resultOf<unknown[]>(response);
    return Array.isArray(items) ? items : [];
  };

  return {
    pluginDir: () => attempt('getPluginDirPath', null, async () => (await PluginManager.getPluginDirPath()) || null),
    requestCanvasFolderAccess: access.forCanvases,
    requestExportAccess: access.toWrite,
    notePen: () =>
      attempt('getPenInfo', null, async () => {
        const pen = resultOf<unknown>(await PluginCommAPI.getPenInfo());
        logger.log(`${TAG}[PEN] note pen=${JSON.stringify(pen ?? null)}`);
        return isNotePen(pen) ? {type: pen.type, width: pen.width, color: pen.color} : null;
      }),
    // The device's file picker, for one image of a kind ImageElements.kt takes; a cancel comes back empty.
    // Not selectImage: that starts the picker from an Activity a plugin doesn't have, and its
    // NullPointerException closes the plugin. selectFile starts it through the host.
    pickImage: () =>
      attempt('selectFile', null, async () => {
        const paths: unknown = await RattaFileSelector.selectFile({
          selectType: SINGLE_FILE,
          suffixList: ['png', 'jpg', 'jpeg', 'webp'],
          maxNum: 1,
          title: 'Insert an image',
        });
        const path: unknown = Array.isArray(paths) ? paths[0] : null;
        return typeof path === 'string' && path !== '' ? path : null;
      }),
    // The same picker as an image, filtered to notes; a cancel comes back empty.
    pickNote: () =>
      attempt('selectFile', null, async () => {
        const paths: unknown = await RattaFileSelector.selectFile({
          selectType: SINGLE_FILE,
          suffixList: ['note'],
          maxNum: 1,
          title: 'Link to a note',
        });
        const path: unknown = Array.isArray(paths) ? paths[0] : null;
        return typeof path === 'string' && path !== '' ? path : null;
      }),
    // -1 for the page opens the note where it was last left (sn-plugin-lib's own meaning for it).
    openNote: (path, page) => attempt('openFile', false, async () => succeeded(await PluginFileAPI.openFile(path, page))),
    lassoedElements: () => attempt('getLassoElements', [], async () => listOf(await PluginCommAPI.getLassoElements())),
    insertImage: path => attempt('insertImage', false, async () => succeeded(await PluginNoteAPI.insertImage(path))),
    currentPage: () =>
      attempt('getCurrentFilePath/getCurrentPageNum', null, async () => {
        const [notePath, page] = await Promise.all([PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum()]);
        return notePageOf(resultOf(notePath), resultOf(page));
      }),
    // getElements takes (page, notePath), the other way round from the rest of PluginFileAPI (as sn-drafting-pen notes).
    pageElements: at =>
      attempt('getElements', [], async () => {
        const response = await PluginFileAPI.getElements(at.page, at.notePath);
        if (!succeeded(response)) {
          // A refused read (reading a note's file needs plugin.permission.FILE:READ) is an error envelope, not a throw.
          logger.warn(`${TAG} getElements failed: ${JSON.stringify(response)}`);
        }
        const elements = listOf(response);
        logger.log(`${TAG}[LINK] page=${at.page} pictures=${JSON.stringify(picturesOf(elements).map(elementSummary))}`);
        return elements;
      }),
    // Modifying elements of the note that is open races its own writes unless it is saved first (sn-plugin-lib's own warning).
    saveNote: () => attempt('saveCurrentNote', false, async () => succeeded(await PluginNoteAPI.saveCurrentNote())),
    closeView: () => attempt('closePluginView', false, async () => (await PluginManager.closePluginView()) === true),
    showView: () => attempt('showPluginView', false, async () => (await PluginManager.showPluginView()) === true),
  };
}
