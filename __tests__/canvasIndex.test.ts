/**
 * The link index (FR12/FR13): which note element opens which canvas, and the
 * canvas last open, read back defensively from its JSON.
 */
import {
  EMPTY_INDEX,
  linkedCanvasIdOf,
  parseCanvasIndex,
  serializeCanvasIndex,
  uuidOf,
  withLastCanvas,
  withLink,
} from '../src/domain/canvasIndex';

test('an index survives its save and load, links and last canvas alike', () => {
  const index = withLastCanvas(withLink(EMPTY_INDEX, 'u-1', 'c-1'), 'c-1');
  expect(parseCanvasIndex(serializeCanvasIndex(index))).toEqual({links: {'u-1': 'c-1'}, lastCanvasId: 'c-1'});
});

test.each([
  ['no file', null],
  ['unparseable JSON', '{nope'],
  ['JSON that is not an object', '42'],
  ['null', 'null'],
  ['links that are not an object', JSON.stringify({links: 'nope'})],
])('%s reads as the empty index', (_case, json) => {
  expect(parseCanvasIndex(json)).toEqual(EMPTY_INDEX);
});

test('links to anything but a canvas id, and a last canvas that is not one, are dropped', () => {
  const json = JSON.stringify({links: {a: 'c-1', b: '../../etc', c: 42}, lastCanvasId: '/x'});
  expect(parseCanvasIndex(json)).toEqual({links: {a: 'c-1'}, lastCanvasId: null});
});

test('linkedCanvasIdOf is the canvas of the first lassoed element the index links', () => {
  const index = withLink(withLink(EMPTY_INDEX, 'u-1', 'c-1'), 'u-2', 'c-2');
  expect(linkedCanvasIdOf([{uuid: 'x'}, {uuid: 'u-2'}, {uuid: 'u-1'}], index)).toBe('c-2');
  expect(linkedCanvasIdOf([null, {}, {uuid: 'constructor'}, {uuid: 'toString'}], index)).toBeNull();
  expect(linkedCanvasIdOf([], index)).toBeNull();
});

test('uuidOf reads Element.uuid, and is null for anything else', () => {
  expect(uuidOf({uuid: 'u-1'})).toBe('u-1');
  expect(uuidOf({uuid: ''})).toBeNull();
  expect(uuidOf({uuid: 7})).toBeNull();
  expect(uuidOf(undefined)).toBeNull();
});
