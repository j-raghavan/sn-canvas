/**
 * The action bar (FR18): each action enabled only when the canvas says it
 * applies, and the ⋮ menu's z-order, zoom and new-canvas choices.
 */
import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import {INITIAL_UI_STATE, type CanvasUiState} from '../src/domain/styles';
import ActionBar from '../src/ui/ActionBar';

const renderBar = (overrides: Partial<CanvasUiState> = {}) => {
  const onCommand = jest.fn();
  const onNewCanvas = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(<ActionBar ui={{...INITIAL_UI_STATE, ...overrides}} onCommand={onCommand} onNewCanvas={onNewCanvas} />);
  });
  const press = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  const isDisabled = (testID: string) => renderer.root.findByProps({testID}).props.disabled;
  const isListed = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const isMenuOpen = () => isListed('supercanvas-menu-zoomToFit');
  return {onCommand, onNewCanvas, press, isDisabled, isMenuOpen, isListed};
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

test('row and column actions are listed only while a table is selected', () => {
  const items = ['tableAddRow', 'tableAddColumn', 'tableRemoveRow', 'tableRemoveColumn'].map(item => `supercanvas-menu-${item}`);
  const shape = renderBar({hasSelection: true, selectedType: 'rectangle'});
  shape.press('supercanvas-more');
  expect(items.map(shape.isListed)).toEqual([false, false, false, false]);
  const table = renderBar({hasSelection: true, selectedType: 'table'});
  table.press('supercanvas-more');
  expect(items.map(table.isListed)).toEqual([true, true, true, true]);
  table.press('supercanvas-menu-tableAddColumn');
  expect(table.onCommand).toHaveBeenCalledWith('tableAddColumn');
});

test('New canvas, last in the menu, goes to the session rather than the canvas', () => {
  const {press, onCommand, onNewCanvas, isMenuOpen} = renderBar();
  press('supercanvas-more');
  press('supercanvas-menu-newCanvas');
  expect(onNewCanvas).toHaveBeenCalledTimes(1);
  expect(onCommand).not.toHaveBeenCalled();
  expect(isMenuOpen()).toBe(false);
});
