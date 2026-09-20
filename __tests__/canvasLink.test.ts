/**
 * Canvas identity and file layout (FR12/FR13): minted ids, canvas, thumbnail
 * and index paths, and reading a canvas id back from lassoed note elements.
 */
import {
  DEFAULT_CANVAS_ID,
  EXPORT_DIR,
  pdfPath,
  SHARED_CANVAS_DIR,
  canvasFilePath,
  canvasIdFromLassoedElements,
  canvasIdFromThumbnailPath,
  imagesPath,
  indexPath,
  installMarkerPath,
  isCanvasId,
  mintCanvasId,
  canvasMadeAt,
  parseElementLink,
  picturePathOf,
  privateCanvasDir,
  thumbnailPath,
} from '../src/domain/canvasLink';
import {BUTTON_ID_OPEN_LINKED, BUTTON_ID_SIDEBAR} from '../src/domain/entryPoints';

test('the entry points are the button ids index.js registers', () => {
  expect([BUTTON_ID_SIDEBAR, BUTTON_ID_OPEN_LINKED]).toEqual([500, 501]);
});

test('canvases, thumbnails and the link index share one folder: in MyStyle, or the plugin directory without write access', () => {
  expect(SHARED_CANVAS_DIR).toBe('/storage/emulated/0/MyStyle/SnCanvas');
  expect(privateCanvasDir('/plugin')).toBe('/plugin/Canvas');
  expect(canvasFilePath(SHARED_CANVAS_DIR, DEFAULT_CANVAS_ID)).toBe('/storage/emulated/0/MyStyle/SnCanvas/default.json');
  expect(thumbnailPath('/plugin/Canvas', 'c-1')).toBe('/plugin/Canvas/thumbnails/c-1.png');
  expect(imagesPath('/plugin/Canvas')).toBe('/plugin/Canvas/images');
  expect(indexPath('/plugin/Canvas')).toBe('/plugin/Canvas/links.json');
  // The first-open marker lives outside the canvas folder, so it never moves to MyStyle with the canvases.
  expect(installMarkerPath('/plugin')).toBe('/plugin/canvas-opened');
});

test('a canvas id says when it was made; the scratch canvas says nothing', () => {
  expect(canvasMadeAt(mintCanvasId(1_700_000_000_000, () => 0.5))).toBe(1_700_000_000_000);
  expect(canvasMadeAt('default')).toBeNull();
});

test('mintCanvasId is deterministic for a given clock and randomness', () => {
  expect(mintCanvasId(0, () => 0)).toBe('c-0-0000');
  expect(mintCanvasId(1_700_000_000_000, () => 0.5)).toBe('c-loyw3v28-i000');
});

test('a minted id is a canvas id, and survives the round trip through its thumbnail path', () => {
  const canvasId = mintCanvasId(Date.now(), Math.random);
  expect(isCanvasId(canvasId)).toBe(true);
  expect(canvasIdFromThumbnailPath(thumbnailPath('/plugin', canvasId))).toBe(canvasId);
});

test.each([
  ['default', true],
  ['c-loyw3v28-i000', true],
  ['links', false],
  ['C-1', false],
  ['c-1/..', false],
  [42, false],
])('isCanvasId(%p) is %p', (value, expected) => {
  expect(isCanvasId(value)).toBe(expected);
});

describe('canvasIdFromThumbnailPath', () => {
  test.each([
    ['a non-string', 42],
    ['a picture that is not a Canvas thumbnail', '/note/images/photo.png'],
    ['a path that climbs out of the thumbnails folder', '/plugin/Canvas/thumbnails/../../x.png'],
    ['a thumbnail with a different extension', '/plugin/Canvas/thumbnails/c-1.jpg'],
    ['a thumbnail named by something that is not a canvas id', '/plugin/Canvas/thumbnails/Photo-1.png'],
  ])('rejects %s', (_case, path) => {
    expect(canvasIdFromThumbnailPath(path)).toBeNull();
  });
});

describe('canvasIdFromLassoedElements', () => {
  test('is null when nothing lassoed is a Canvas thumbnail', () => {
    expect(canvasIdFromLassoedElements([])).toBeNull();
    expect(canvasIdFromLassoedElements([null, {}, {picture: null}, {picture: {picturePath: 42}}])).toBeNull();
  });

  test('is the canvas of the first Canvas thumbnail in the lasso', () => {
    const lassoed = [
      {picture: {picturePath: '/note/images/photo.png'}},
      {picture: {picturePath: '/plugin/Canvas/thumbnails/c-2.png'}},
      {picture: {picturePath: '/plugin/Canvas/thumbnails/c-3.png'}},
    ];
    expect(canvasIdFromLassoedElements(lassoed)).toBe('c-2');
  });
});

test('a PDF export goes to EXPORT, named for its local date and time', () => {
  expect(EXPORT_DIR).toBe('/storage/emulated/0/EXPORT');
  expect(pdfPath(new Date(2026, 0, 5, 9, 4, 3))).toBe('/storage/emulated/0/EXPORT/Canvas-20260105-090403.pdf');
});

test('parseElementLink takes a link this build can follow, and nothing else', () => {
  expect(parseElementLink({kind: 'note', target: '/n.note', page: 3})).toEqual({kind: 'note', target: '/n.note', page: 3});
  // No page, or one that is not a whole number: the note opens where it was last left.
  expect(parseElementLink({kind: 'note', target: '/n.note'})).toEqual({kind: 'note', target: '/n.note', page: -1});
  expect(parseElementLink({kind: 'note', target: '/n.note', page: 1.5})).toEqual({kind: 'note', target: '/n.note', page: -1});
  // A kind this build does not follow, no target, or nothing at all.
  expect(parseElementLink({kind: 'canvas', target: 'c-1'})).toBeNull();
  expect(parseElementLink({kind: 'note', target: ''})).toBeNull();
  expect(parseElementLink({kind: 'note'})).toBeNull();
  expect(parseElementLink(null)).toBeNull();
});

test('picturePathOf reads Element.picture.picturePath and tolerates anything else', () => {
  expect(picturePathOf({picture: {picturePath: '/a.png'}})).toBe('/a.png');
  expect(picturePathOf(undefined)).toBeUndefined();
});
