import {buildUserData, parseUserData} from '../src/canvasUserData';

test('round-trips a canvasId through build and parse', () => {
  const userData = buildUserData('default');
  expect(parseUserData(userData)).toBe('default');
});

test('round-trips an arbitrary canvasId', () => {
  const userData = buildUserData('abc-123-xyz');
  expect(parseUserData(userData)).toBe('abc-123-xyz');
});

test('parseUserData returns null for null', () => {
  expect(parseUserData(null)).toBeNull();
});

test('parseUserData returns null for undefined', () => {
  expect(parseUserData(undefined)).toBeNull();
});

test('parseUserData returns null for an empty string', () => {
  expect(parseUserData('')).toBeNull();
});

test('parseUserData returns null for malformed JSON', () => {
  expect(parseUserData('{not valid json')).toBeNull();
});

test('parseUserData returns null when the namespaced key is missing', () => {
  expect(parseUserData(JSON.stringify({someOtherPlugin: 'value'}))).toBeNull();
});

test('parseUserData returns null when the namespaced value is not a string', () => {
  expect(parseUserData(JSON.stringify({snSuperCanvasId: 42}))).toBeNull();
});

test('parseUserData returns null when the namespaced value is an empty string', () => {
  expect(parseUserData(JSON.stringify({snSuperCanvasId: ''}))).toBeNull();
});

test('parseUserData returns null for a JSON value that is not an object', () => {
  expect(parseUserData('"just a string"')).toBeNull();
  expect(parseUserData('42')).toBeNull();
  expect(parseUserData('null')).toBeNull();
  expect(parseUserData('[1,2,3]')).toBeNull();
});
