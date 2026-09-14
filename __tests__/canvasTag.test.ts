/**
 * A thumbnail's own record of its canvas (FR13): the canvas id written into
 * the placed picture's userData, read back from any lasso of it.
 */
import {canvasIdFromTags, canvasTag, taggedCanvasId, taggedPicture} from '../src/domain/canvasTag';

test('a canvas id survives the round trip through the tag written into a picture', () => {
  expect(canvasTag('c-1')).toBe('snsupercanvas:c-1');
  expect(taggedCanvasId({userData: canvasTag('c-1')})).toBe('c-1');
});

test.each([
  ['no element', null],
  ['no userData', {}],
  ['userData that is not a string', {userData: 7}],
  ["another plugin's userData", {userData: 'sn-drafting-pen'}],
  ['a tag naming something that is not a canvas', {userData: 'snsupercanvas:../../etc'}],
])('taggedCanvasId is null for %s', (_case, element) => {
  expect(taggedCanvasId(element)).toBeNull();
});

test('canvasIdFromTags is the canvas of the first tagged element among those lassoed', () => {
  expect(canvasIdFromTags([{userData: 'other'}, {userData: canvasTag('c-2')}, {userData: canvasTag('c-3')}])).toBe('c-2');
  expect(canvasIdFromTags([{}, null])).toBeNull();
});

test('taggedPicture puts the picture on its page, shows it from a PNG that exists, tags it, and drops the native data the bridge cannot carry back', () => {
  const lassoed = {
    uuid: 'copy',
    type: 200,
    numInPage: 42,
    pageNum: -1,
    angles: {},
    contoursSrc: {},
    picture: {picturePath: 'plugin/1.png', rect: {left: 760, top: 1080, right: 1160, bottom: 1480}},
  };
  expect(taggedPicture(lassoed, 'c-1', 2, '/canvases/thumbnails/c-1.png')).toEqual({
    uuid: 'copy',
    type: 200,
    numInPage: 42,
    pageNum: 2,
    picture: {picturePath: '/canvases/thumbnails/c-1.png', rect: {left: 760, top: 1080, right: 1160, bottom: 1480}},
    userData: 'snsupercanvas:c-1',
  });
  expect(taggedPicture(null, 'c-1', 0, '/t.png')).toEqual({
    pageNum: 0,
    userData: 'snsupercanvas:c-1',
    picture: {picturePath: '/t.png'},
  });
});
