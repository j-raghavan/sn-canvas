/**
 * The sn-plugin-lib adapter unwraps {success, result} envelopes into plain
 * values and never rejects: every SDK failure becomes a logged fallback.
 */
const mockGetPluginDirPath = jest.fn();
const mockClosePluginView = jest.fn();
const mockShowPluginView = jest.fn();
const mockGetLassoElements = jest.fn();
const mockInsertImage = jest.fn();
const mockGetCurrentFilePath = jest.fn();
const mockGetCurrentPageNum = jest.fn();
const mockGetElements = jest.fn();
const mockModifyElements = jest.fn();
const mockGetPenInfo = jest.fn();
const mockSelectImage = jest.fn();
const mockSaveCurrentNote = jest.fn();
const mockOpenFile = jest.fn();

jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    getPluginDirPath: () => mockGetPluginDirPath(),
    closePluginView: () => mockClosePluginView(),
    showPluginView: () => mockShowPluginView(),
  },
  PluginCommAPI: {
    getPenInfo: () => mockGetPenInfo(),
    getLassoElements: () => mockGetLassoElements(),
    getCurrentFilePath: () => mockGetCurrentFilePath(),
    getCurrentPageNum: () => mockGetCurrentPageNum(),
  },
  PluginNoteAPI: {insertImage: (path: string) => mockInsertImage(path), saveCurrentNote: () => mockSaveCurrentNote()},
  RattaFileSelector: {selectFile: (params: unknown) => mockSelectImage(params)},
  PluginFileAPI: {
    openFile: (path: string, page: number) => mockOpenFile(path, page),
    getElements: (page: number, notePath: string) => mockGetElements(page, notePath),
    modifyElements: (notePath: string, page: number, elements: unknown[]) => mockModifyElements(notePath, page, elements),
  },
}));

import {createHostSdk} from '../src/infrastructure/hostSdk';
import {createRecordingLogger} from './helpers/fakePorts';

const failure = () => Promise.reject(new Error('host said no'));
const requestAccess = jest.fn().mockResolvedValue(true);

test('pluginDir is the host path, or null when it has none or the call fails', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger, requestAccess);
  mockGetPluginDirPath.mockResolvedValueOnce('/plugin');
  expect(await host.pluginDir()).toBe('/plugin');
  mockGetPluginDirPath.mockResolvedValueOnce('');
  expect(await host.pluginDir()).toBeNull();
  mockGetPluginDirPath.mockImplementationOnce(failure);
  expect(await host.pluginDir()).toBeNull();
  expect(logger.lines).toEqual(['warn [SNCANVAS] getPluginDirPath failed: Error: host said no']);
});

test('requestFileAccess is the shared file-permission request it is given', async () => {
  expect(await createHostSdk(createRecordingLogger(), requestAccess).requestFileAccess()).toBe(true);
  expect(requestAccess).toHaveBeenCalledTimes(1);
});

test('lassoedElements unwraps the element list, and is empty for anything else', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockGetLassoElements.mockResolvedValueOnce({success: true, result: [{id: 1}]});
  expect(await host.lassoedElements()).toEqual([{id: 1}]);
  mockGetLassoElements.mockResolvedValueOnce({success: false, error: {code: 1, message: 'no lasso'}});
  expect(await host.lassoedElements()).toEqual([]);
  mockGetLassoElements.mockResolvedValueOnce({success: true, result: 'not a list'});
  expect(await host.lassoedElements()).toEqual([]);
  mockGetLassoElements.mockImplementationOnce(failure);
  expect(await host.lassoedElements()).toEqual([]);
});

test('insertImage is true only when the host reports success', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockInsertImage.mockResolvedValueOnce({success: true, result: true});
  expect(await host.insertImage('/t.png')).toBe(true);
  expect(mockInsertImage).toHaveBeenCalledWith('/t.png');
  mockInsertImage.mockResolvedValueOnce({success: false});
  expect(await host.insertImage('/t.png')).toBe(false);
  mockInsertImage.mockResolvedValueOnce(null);
  expect(await host.insertImage('/t.png')).toBe(false);
  mockInsertImage.mockImplementationOnce(failure);
  expect(await host.insertImage('/t.png')).toBe(false);
});

test('currentPage is the note and page the host reports, or null when either is missing', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockGetCurrentFilePath.mockResolvedValueOnce({success: true, result: '/n.note'});
  mockGetCurrentPageNum.mockResolvedValueOnce({success: true, result: 2});
  expect(await host.currentPage()).toEqual({notePath: '/n.note', page: 2});
  mockGetCurrentFilePath.mockResolvedValueOnce({success: false});
  mockGetCurrentPageNum.mockResolvedValueOnce({success: true, result: 2});
  expect(await host.currentPage()).toBeNull();
  mockGetCurrentFilePath.mockImplementationOnce(failure);
  mockGetCurrentPageNum.mockResolvedValueOnce({success: true, result: 2});
  expect(await host.currentPage()).toBeNull();
});

test('pageElements reads the page, page first as the SDK wants, and logs the pictures on it', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger, requestAccess);
  const page = [
    {uuid: 'stroke', type: 0, numInPage: 1},
    {uuid: 'pic', type: 200, numInPage: 2},
  ];
  mockGetElements.mockResolvedValueOnce({success: true, result: page});
  expect(await host.pageElements({notePath: '/n.note', page: 3})).toEqual(page);
  expect(mockGetElements).toHaveBeenCalledWith(3, '/n.note');
  expect(logger.lines).toEqual(['log [SNCANVAS][LINK] page=3 pictures=[{"uuid":"pic","type":200,"num":2}]']);
  // A refused read comes back as an error envelope: logged, so the reason reaches the plugin's own log.
  mockGetElements.mockResolvedValueOnce({success: false, error: {code: 403, message: 'sdcard_no_read'}});
  expect(await host.pageElements({notePath: '/n.note', page: 3})).toEqual([]);
  expect(logger.lines).toContain(
    'warn [SNCANVAS] getElements failed: {"success":false,"error":{"code":403,"message":"sdcard_no_read"}}',
  );
  mockGetElements.mockImplementationOnce(failure);
  expect(await host.pageElements({notePath: '/n.note', page: 3})).toEqual([]);
});

test('saveNote saves the note that is open, and is false when the host refuses or throws', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockSaveCurrentNote.mockResolvedValueOnce({success: true, result: true});
  expect(await host.saveNote()).toBe(true);
  mockSaveCurrentNote.mockResolvedValueOnce({success: false, error: {code: 1, message: 'busy'}});
  expect(await host.saveNote()).toBe(false);
  mockSaveCurrentNote.mockImplementationOnce(failure);
  expect(await host.saveNote()).toBe(false);
});

test('pickNote asks the picker for one note, and is null when the user cancels or it throws', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockSelectImage.mockResolvedValueOnce(['/storage/emulated/0/Note/plan.note']);
  expect(await host.pickNote()).toBe('/storage/emulated/0/Note/plan.note');
  expect(mockSelectImage).toHaveBeenCalledWith({selectType: 1, suffixList: ['note'], maxNum: 1, title: 'Link to a note'});
  mockSelectImage.mockResolvedValueOnce([]);
  expect(await host.pickNote()).toBeNull();
  // The picker answering with something that is not a list of paths at all.
  mockSelectImage.mockResolvedValueOnce(null);
  expect(await host.pickNote()).toBeNull();
  mockSelectImage.mockImplementationOnce(failure);
  expect(await host.pickNote()).toBeNull();
});

test('openNote opens the note at the page it is given, and is false when the host refuses or throws', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockOpenFile.mockResolvedValueOnce({success: true, result: true});
  expect(await host.openNote('/n.note', -1)).toBe(true);
  expect(mockOpenFile).toHaveBeenCalledWith('/n.note', -1);
  mockOpenFile.mockResolvedValueOnce({success: false, error: {code: 404, message: 'gone'}});
  expect(await host.openNote('/n.note', 2)).toBe(false);
  mockOpenFile.mockImplementationOnce(failure);
  expect(await host.openNote('/n.note', 2)).toBe(false);
});

test('tagPicture writes the canvas into the picture as the lasso gave it, on its page, and is true once the note modified it', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger, requestAccess);
  const at = {notePath: '/n.note', page: 2};
  const lassoed = {uuid: 'copy', type: 200, numInPage: 42, pageNum: -1, angles: {}, contoursSrc: {}};
  mockModifyElements.mockResolvedValueOnce({success: true, result: [42]});
  expect(await host.tagPicture(lassoed, 'c-1', at, '/c/thumbnails/c-1.png')).toBe(true);
  expect(mockModifyElements).toHaveBeenCalledWith('/n.note', 2, [
    {uuid: 'copy', type: 200, numInPage: 42, pageNum: 2, userData: 'sncanvas:c-1', picture: {picturePath: '/c/thumbnails/c-1.png'}},
  ]);
  mockModifyElements.mockResolvedValueOnce({success: true, result: []});
  expect(await host.tagPicture(lassoed, 'c-1', at, '/c/thumbnails/c-1.png')).toBe(false);
  mockModifyElements.mockResolvedValueOnce({success: false, error: {code: 107, message: 'bad element'}});
  expect(await host.tagPicture(lassoed, 'c-1', at, '/c/thumbnails/c-1.png')).toBe(false);
  expect(logger.lines).toContain('warn [SNCANVAS] modifyElements failed: {"success":false,"error":{"code":107,"message":"bad element"}}');
  mockModifyElements.mockImplementationOnce(failure);
  expect(await host.tagPicture(lassoed, 'c-1', at, '/c/thumbnails/c-1.png')).toBe(false);
});

test("notePen is the note's pen, logged as the host reported it, and null for anything but three numbers", async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger, requestAccess);
  mockGetPenInfo.mockResolvedValueOnce({success: true, result: {type: 14, width: 700, color: 0, extra: 1}});
  expect(await host.notePen()).toEqual({type: 14, width: 700, color: 0});
  expect(logger.lines).toEqual(['log [SNCANVAS][PEN] note pen={"type":14,"width":700,"color":0,"extra":1}']);
  mockGetPenInfo.mockResolvedValueOnce({success: true, result: {type: 14, width: '700', color: 0}});
  expect(await host.notePen()).toBeNull();
  mockGetPenInfo.mockResolvedValueOnce({success: true, result: null});
  expect(await host.notePen()).toBeNull();
  mockGetPenInfo.mockResolvedValueOnce({success: false});
  expect(await host.notePen()).toBeNull();
  expect(logger.lines).toContain('log [SNCANVAS][PEN] note pen=null');
  mockGetPenInfo.mockImplementationOnce(failure);
  expect(await host.notePen()).toBeNull();
});

test('pickImage asks the file picker for one image, and is its path, or null for a cancel or a failure', async () => {
  const host = createHostSdk(createRecordingLogger(), requestAccess);
  mockSelectImage.mockResolvedValueOnce(['/sdcard/a.png']);
  expect(await host.pickImage()).toBe('/sdcard/a.png');
  expect(mockSelectImage).toHaveBeenCalledWith({
    selectType: 1,
    suffixList: ['png', 'jpg', 'jpeg', 'webp'],
    maxNum: 1,
    title: 'Insert an image',
  });
  mockSelectImage.mockResolvedValueOnce(null);
  expect(await host.pickImage()).toBeNull();
  mockSelectImage.mockResolvedValueOnce([]);
  expect(await host.pickImage()).toBeNull();
  mockSelectImage.mockResolvedValueOnce(['']);
  expect(await host.pickImage()).toBeNull();
  mockSelectImage.mockImplementationOnce(failure);
  expect(await host.pickImage()).toBeNull();
});

test('closeView and showView hide and bring back the plugin view; a failure is only logged', async () => {
  const logger = createRecordingLogger();
  const host = createHostSdk(logger, requestAccess);
  mockClosePluginView.mockResolvedValueOnce(true);
  expect(await host.closeView()).toBe(true);
  mockShowPluginView.mockResolvedValueOnce(true);
  expect(await host.showView()).toBe(true);
  mockClosePluginView.mockImplementationOnce(failure);
  expect(await host.closeView()).toBe(false);
  mockShowPluginView.mockResolvedValueOnce(undefined);
  expect(await host.showView()).toBe(false);
  expect(logger.lines).toEqual(['warn [SNCANVAS] closePluginView failed: Error: host said no']);
});
