/**
 * Canvas identity and file layout (FR12/FR13): minted ids, canvas, thumbnail
 * and index paths, and reading a canvas id back from lassoed note elements.
 */
import {
  DEFAULT_CANVAS_ID,
  canvasDirPath,
  canvasFilePath,
  canvasIdFromLassoedElements,
  canvasIdFromThumbnailPath,
  indexPath,
  isCanvasId,
  mintCanvasId,
  picturePathOf,
  thumbnailPath,
} from '../src/domain/canvasLink';
import {BUTTON_ID_OPEN_LINKED, BUTTON_ID_SIDEBAR} from '../src/domain/entryPoints';

test('the entry points are the button ids index.js registers', () => {
  expect([BUTTON_ID_SIDEBAR, BUTTON_ID_OPEN_LINKED]).toEqual([500, 501]);
});

test('canvases, thumbnails and the link index live in one folder under the plugin directory', () => {
  expect(canvasDirPath('/plugin')).toBe('/plugin/SuperCanvas');
  expect(canvasFilePath('/plugin', DEFAULT_CANVAS_ID)).toBe('/plugin/SuperCanvas/default.json');
  expect(thumbnailPath('/plugin', 'c-1')).toBe('/plugin/SuperCanvas/thumbnails/c-1.png');
  expect(indexPath('/plugin')).toBe('/plugin/SuperCanvas/links.json');
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
    ['a picture that is not a SuperCanvas thumbnail', '/note/images/photo.png'],
    ['a path that climbs out of the thumbnails folder', '/plugin/SuperCanvas/thumbnails/../../x.png'],
    ['a thumbnail with a different extension', '/plugin/SuperCanvas/thumbnails/c-1.jpg'],
    ['a thumbnail named by something that is not a canvas id', '/plugin/SuperCanvas/thumbnails/Photo-1.png'],
  ])('rejects %s', (_case, path) => {
    expect(canvasIdFromThumbnailPath(path)).toBeNull();
  });
});

describe('canvasIdFromLassoedElements', () => {
  test('is null when nothing lassoed is a SuperCanvas thumbnail', () => {
    expect(canvasIdFromLassoedElements([])).toBeNull();
    expect(canvasIdFromLassoedElements([null, {}, {picture: null}, {picture: {picturePath: 42}}])).toBeNull();
  });

  test('is the canvas of the first SuperCanvas thumbnail in the lasso', () => {
    const lassoed = [
      {picture: {picturePath: '/note/images/photo.png'}},
      {picture: {picturePath: '/plugin/SuperCanvas/thumbnails/c-2.png'}},
      {picture: {picturePath: '/plugin/SuperCanvas/thumbnails/c-3.png'}},
    ];
    expect(canvasIdFromLassoedElements(lassoed)).toBe('c-2');
  });
});

test('picturePathOf reads Element.picture.picturePath and tolerates anything else', () => {
  expect(picturePathOf({picture: {picturePath: '/a.png'}})).toBe('/a.png');
  expect(picturePathOf(undefined)).toBeUndefined();
});
