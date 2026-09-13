/**
 * Canvas identity and file layout (FR12/FR13): minted ids, canvas and
 * thumbnail paths, and reading a canvas id back from lassoed note elements.
 */
import {
  DEFAULT_CANVAS_ID,
  canvasFilePath,
  canvasIdFromLassoedElements,
  canvasIdFromThumbnailPath,
  mintCanvasId,
  picturePathOf,
  thumbnailPath,
} from '../src/domain/canvasLink';
import {BUTTON_ID_OPEN_LINKED, BUTTON_ID_SIDEBAR} from '../src/domain/entryPoints';

test('the entry points are the button ids index.js registers', () => {
  expect([BUTTON_ID_SIDEBAR, BUTTON_ID_OPEN_LINKED]).toEqual([500, 501]);
});

test('canvases and thumbnails live under the plugin directory, named by canvas id', () => {
  expect(canvasFilePath('/plugin', DEFAULT_CANVAS_ID)).toBe('/plugin/SuperCanvas/default.json');
  expect(thumbnailPath('/plugin', 'c-1')).toBe('/plugin/SuperCanvas/thumbnails/c-1.png');
});

test('mintCanvasId is deterministic for a given clock and randomness', () => {
  expect(mintCanvasId(0, () => 0)).toBe('c-0-0000');
  expect(mintCanvasId(1_700_000_000_000, () => 0.5)).toBe('c-loyw3v28-i000');
});

test('a minted id survives the round trip through its thumbnail path', () => {
  const canvasId = mintCanvasId(Date.now(), Math.random);
  expect(canvasIdFromThumbnailPath(thumbnailPath('/plugin', canvasId))).toBe(canvasId);
});

describe('canvasIdFromThumbnailPath', () => {
  test.each([
    ['a non-string', 42],
    ['a picture that is not a SuperCanvas thumbnail', '/note/images/photo.png'],
    ['a path that climbs out of the thumbnails folder', '/plugin/SuperCanvas/thumbnails/../../x.png'],
    ['a thumbnail with a different extension', '/plugin/SuperCanvas/thumbnails/c-1.jpg'],
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
