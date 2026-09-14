/**
 * The edit-text event body (FR6/FR24), validated at the bridge.
 */
import {parseTextEditRequest} from '../src/domain/textEdit';

const body = {elementId: 'tb', cellIndex: 3, text: 'hi', left: 1, top: 2, width: 3, height: 4, fontSize: 18, isNote: false};

test('reads a well-formed event body', () => {
  expect(parseTextEditRequest(body)).toEqual(body);
});

test('a negative or missing cell index means a text box or a note', () => {
  expect(parseTextEditRequest({...body, cellIndex: -1})?.cellIndex).toBeNull();
  expect(parseTextEditRequest({...body, cellIndex: undefined})?.cellIndex).toBeNull();
});

test('text that is not a string reads as empty, and isNote only when true', () => {
  expect(parseTextEditRequest({...body, text: 5, isNote: 'yes'})).toMatchObject({text: '', isNote: false});
  expect(parseTextEditRequest({...body, isNote: true})?.isNote).toBe(true);
});

test('a body that cannot place an editor is null', () => {
  expect(parseTextEditRequest(null)).toBeNull();
  expect(parseTextEditRequest({...body, elementId: ''})).toBeNull();
  expect(parseTextEditRequest({...body, elementId: 7})).toBeNull();
  expect(parseTextEditRequest({...body, width: Number.NaN})).toBeNull();
  expect(parseTextEditRequest({...body, top: '2'})).toBeNull();
});
