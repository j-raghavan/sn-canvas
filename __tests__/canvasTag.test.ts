/**
 * A thumbnail's own record of its canvas (FR13): a canvas id in the picture's
 * userData, read back from any lasso of it. Only builds before #30 wrote one.
 */
import {canvasIdFromTags, canvasTag, taggedCanvasId} from '../src/domain/canvasTag';

test('a canvas id survives the round trip through the tag written into a picture', () => {
  expect(canvasTag('c-1')).toBe('sncanvas:c-1');
  expect(taggedCanvasId({userData: canvasTag('c-1')})).toBe('c-1');
});

test('a tag written before the plugin was renamed from SuperCanvas still names its canvas', () => {
  expect(taggedCanvasId({userData: 'snsupercanvas:c-1'})).toBe('c-1');
});

test.each([
  ['no element', null],
  ['no userData', {}],
  ['userData that is not a string', {userData: 7}],
  ["another plugin's userData", {userData: 'sn-drafting-pen'}],
  ['a tag naming something that is not a canvas', {userData: 'sncanvas:../../etc'}],
])('taggedCanvasId is null for %s', (_case, element) => {
  expect(taggedCanvasId(element)).toBeNull();
});

test('canvasIdFromTags is the canvas of the first tagged element among those lassoed', () => {
  expect(canvasIdFromTags([{userData: 'other'}, {userData: canvasTag('c-2')}, {userData: canvasTag('c-3')}])).toBe('c-2');
  expect(canvasIdFromTags([{}, null])).toBeNull();
});

