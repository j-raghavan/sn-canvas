/**
 * The keyboard text editor (FR6/FR24): placed over the text, handing the text
 * back exactly once, whether by Done or by the keyboard closing.
 */
import React from 'react';
import {StyleSheet} from 'react-native';
import ReactTestRenderer, {act, type ReactTestRendererJSON} from 'react-test-renderer';
import type {TextEditRequest} from '../src/domain/textEdit';
import TextEditor from '../src/ui/TextEditor';

const request: TextEditRequest = {
  elementId: 't',
  cellIndex: null,
  text: 'hello',
  left: 10,
  top: 20,
  width: 300,
  height: 40,
  fontSize: 24,
  isNote: false,
};

const renderEditor = (overrides: Partial<TextEditRequest> = {}) => {
  const onDone = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(<TextEditor request={{...request, ...overrides}} onDone={onDone} />);
  });
  const input = () => renderer.root.findByProps({testID: 'text-editor-input'});
  const frame = () => StyleSheet.flatten((renderer.toJSON() as ReactTestRendererJSON).props.style);
  const tap = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  return {onDone, input, frame, tap};
};

test('opens over the text, at its size, with its content', () => {
  const {input, frame} = renderEditor();
  expect(input().props.value).toBe('hello');
  expect(StyleSheet.flatten(input().props.style)).toMatchObject({fontSize: 24, minHeight: 40, padding: 8});
  expect(frame()).toMatchObject({left: 10, top: 20, width: 300});
});

test('a narrow table cell still gets room to type', () => {
  expect(renderEditor({width: 50}).frame().width).toBe(140);
});

test('Done hands back what was typed, once, even when the keyboard then closes', () => {
  const {onDone, input, tap} = renderEditor();
  act(() => {
    input().props.onChangeText('bye');
  });
  tap('text-editor-done');
  act(() => {
    input().props.onBlur();
  });
  expect(onDone).toHaveBeenCalledTimes(1);
  expect(onDone).toHaveBeenCalledWith('bye');
});

test('the keyboard closing hands the text back too', () => {
  const {onDone, input} = renderEditor();
  act(() => {
    input().props.onBlur();
  });
  expect(onDone).toHaveBeenCalledWith('hello');
});

test('a note is edited on a note-coloured background', () => {
  expect(StyleSheet.flatten(renderEditor({isNote: true}).input().props.style).backgroundColor).toBe('#eeeeee');
  expect(StyleSheet.flatten(renderEditor().input().props.style).backgroundColor).toBe('#ffffff');
});
