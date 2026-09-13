/**
 * The action bar (FR18): each action enabled only when the canvas says it
 * applies, and the ⋮ menu's z-order and zoom choices.
 */
import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import {INITIAL_UI_STATE, type CanvasUiState} from '../src/domain/styles';
import ActionBar from '../src/ui/ActionBar';

const renderBar = (overrides: Partial<CanvasUiState> = {}) => {
  const onCommand = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(<ActionBar ui={{...INITIAL_UI_STATE, ...overrides}} onCommand={onCommand} />);
  });
  const press = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  const isDisabled = (testID: string) => renderer.root.findByProps({testID}).props.disabled;
  const isMenuOpen = () => renderer.root.findAllByProps({testID: 'supercanvas-menu-zoomToFit'}).length > 0;
  return {onCommand, press, isDisabled, isMenuOpen};
};

const ACTIONS = ['supercanvas-undo', 'supercanvas-redo', 'supercanvas-delete', 'supercanvas-duplicate'];

test('each action is enabled only when the canvas says it applies', () => {
  expect(ACTIONS.map(renderBar().isDisabled)).toEqual([true, true, true, true]);
  expect(ACTIONS.map(renderBar({canUndo: true}).isDisabled)).toEqual([false, true, true, true]);
  expect(ACTIONS.map(renderBar({canRedo: true, hasSelection: true}).isDisabled)).toEqual([true, false, false, false]);
});

test.each([
  ['supercanvas-undo', 'undo'],
  ['supercanvas-redo', 'redo'],
  ['supercanvas-delete', 'deleteSelected'],
  ['supercanvas-duplicate', 'duplicateSelected'],
])('%s sends %s', (testID, command) => {
  const {press, onCommand} = renderBar({canUndo: true, canRedo: true, hasSelection: true});
  press(testID);
  expect(onCommand).toHaveBeenCalledWith(command);
});

test('the more button toggles the menu', () => {
  const {press, isMenuOpen} = renderBar();
  expect(isMenuOpen()).toBe(false);
  press('supercanvas-more');
  expect(isMenuOpen()).toBe(true);
  press('supercanvas-more');
  expect(isMenuOpen()).toBe(false);
});

test('z-order needs a selection; zoom never does', () => {
  const {press, isDisabled} = renderBar();
  press('supercanvas-more');
  const items = ['bringToFront', 'sendToBack', 'zoomToFit', 'zoomTo100'].map(item => `supercanvas-menu-${item}`);
  expect(items.map(isDisabled)).toEqual([true, true, false, false]);
});

test('choosing a menu item runs it and closes the menu', () => {
  const {press, onCommand, isMenuOpen} = renderBar({hasSelection: true});
  press('supercanvas-more');
  press('supercanvas-menu-bringToFront');
  expect(onCommand).toHaveBeenCalledWith('bringToFront');
  expect(isMenuOpen()).toBe(false);
});
