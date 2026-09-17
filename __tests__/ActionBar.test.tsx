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
  const onClearCanvas = jest.fn();
  const onLinkToNote = jest.fn();
  const onMenuOpen = jest.fn();
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  act(() => {
    renderer = ReactTestRenderer.create(
      <ActionBar
        ui={{...INITIAL_UI_STATE, ...overrides}}
        onCommand={onCommand}
        onNewCanvas={onNewCanvas}
        onClearCanvas={onClearCanvas}
        onLinkToNote={onLinkToNote}
        onMenuOpen={onMenuOpen}
      />,
    );
  });
  const press = (testID: string) =>
    act(() => {
      renderer.root.findByProps({testID}).props.onPress();
    });
  const isDisabled = (testID: string) => renderer.root.findByProps({testID}).props.disabled;
  const isListed = (testID: string) => renderer.root.findAllByProps({testID}).length > 0;
  const isMenuOpen = () => isListed('canvas-menu-zoomToFit');
  return {onCommand, onNewCanvas, onClearCanvas, onLinkToNote, onMenuOpen, press, isDisabled, isMenuOpen, isListed};
};

const ACTIONS = ['canvas-undo', 'canvas-redo', 'canvas-delete', 'canvas-duplicate'];

test('each action is enabled only when the canvas says it applies', () => {
  expect(ACTIONS.map(renderBar().isDisabled)).toEqual([true, true, true, true]);
  expect(ACTIONS.map(renderBar({canUndo: true}).isDisabled)).toEqual([false, true, true, true]);
  expect(ACTIONS.map(renderBar({canRedo: true, hasSelection: true}).isDisabled)).toEqual([true, false, false, false]);
});

test.each([
  ['canvas-undo', 'undo'],
  ['canvas-redo', 'redo'],
  ['canvas-delete', 'deleteSelected'],
  ['canvas-duplicate', 'duplicateSelected'],
])('%s sends %s', (testID, command) => {
  const {press, onCommand} = renderBar({canUndo: true, canRedo: true, hasSelection: true});
  press(testID);
  expect(onCommand).toHaveBeenCalledWith(command);
});

test('the more button toggles the menu, and says so as it opens', () => {
  const {press, isMenuOpen, onMenuOpen} = renderBar();
  expect(isMenuOpen()).toBe(false);
  press('canvas-more');
  expect(isMenuOpen()).toBe(true);
  expect(onMenuOpen).toHaveBeenCalledTimes(1);
  press('canvas-more');
  expect(isMenuOpen()).toBe(false);
  // Closing is not an opening: the hints are not put away twice.
  expect(onMenuOpen).toHaveBeenCalledTimes(1);
});

test('z-order needs a selection; zoom never does', () => {
  const {press, isDisabled} = renderBar();
  press('canvas-more');
  const items = ['bringToFront', 'sendToBack', 'zoomToFit', 'zoomTo100'].map(item => `canvas-menu-${item}`);
  expect(items.map(isDisabled)).toEqual([true, true, false, false]);
});

test('choosing a menu item runs it and closes the menu', () => {
  const {press, onCommand, isMenuOpen} = renderBar({hasSelection: true});
  press('canvas-more');
  press('canvas-menu-bringToFront');
  expect(onCommand).toHaveBeenCalledWith('bringToFront');
  expect(isMenuOpen()).toBe(false);
});

test('row and column actions are listed only while a table is selected', () => {
  const items = ['tableAddRow', 'tableAddColumn', 'tableRemoveRow', 'tableRemoveColumn'].map(item => `canvas-menu-${item}`);
  const shape = renderBar({hasSelection: true, selectedType: 'rectangle'});
  shape.press('canvas-more');
  expect(items.map(shape.isListed)).toEqual([false, false, false, false]);
  const table = renderBar({hasSelection: true, selectedType: 'table'});
  table.press('canvas-more');
  expect(items.map(table.isListed)).toEqual([true, true, true, true]);
  table.press('canvas-menu-tableAddColumn');
  expect(table.onCommand).toHaveBeenCalledWith('tableAddColumn');
});

test('Group and Ungroup sit in the bar beside delete and duplicate, greyed out until they apply', () => {
  // On the bar itself, not behind the ⋮ menu: an action that has to be found is an action nobody uses.
  const one = renderBar({hasSelection: true, selectionCount: 1});
  expect([one.isDisabled('canvas-group'), one.isDisabled('canvas-ungroup')]).toEqual([true, true]);

  const several = renderBar({hasSelection: true, selectionCount: 3});
  expect([several.isDisabled('canvas-group'), several.isDisabled('canvas-ungroup')]).toEqual([false, true]);
  several.press('canvas-group');
  expect(several.onCommand).toHaveBeenCalledWith('group');

  const grouped = renderBar({hasSelection: true, selectionCount: 2, canUngroup: true});
  expect(grouped.isDisabled('canvas-ungroup')).toBe(false);
  grouped.press('canvas-ungroup');
  expect(grouped.onCommand).toHaveBeenCalledWith('ungroup');
});

test('Link to note needs a selection, and Remove link a link on it', () => {
  const nothing = renderBar();
  nothing.press('canvas-more');
  expect([nothing.isDisabled('canvas-menu-linkToNote'), nothing.isDisabled('canvas-menu-unlinkSelected')]).toEqual([true, true]);

  const selected = renderBar({hasSelection: true});
  selected.press('canvas-more');
  expect([selected.isDisabled('canvas-menu-linkToNote'), selected.isDisabled('canvas-menu-unlinkSelected')]).toEqual([false, true]);
  // Picking the note is the screen's job, not a command the canvas can run.
  selected.press('canvas-menu-linkToNote');
  expect(selected.onLinkToNote).toHaveBeenCalledTimes(1);
  expect(selected.onCommand).not.toHaveBeenCalled();

  const linked = renderBar({hasSelection: true, hasLink: true});
  linked.press('canvas-more');
  expect(linked.isDisabled('canvas-menu-unlinkSelected')).toBe(false);
  linked.press('canvas-menu-unlinkSelected');
  expect(linked.onCommand).toHaveBeenCalledWith('unlinkSelected');
});

test('Clear canvas applies only to a canvas with something on it', () => {
  const empty = renderBar();
  empty.press('canvas-more');
  expect(empty.isDisabled('canvas-menu-clearCanvas')).toBe(true);
  const drawn = renderBar({hasContent: true});
  drawn.press('canvas-more');
  expect(drawn.isDisabled('canvas-menu-clearCanvas')).toBe(false);
});

test('Clear canvas goes to the screen, which confirms it, rather than straight to the canvas', () => {
  const {press, onCommand, onClearCanvas, isMenuOpen} = renderBar({hasContent: true});
  press('canvas-more');
  press('canvas-menu-clearCanvas');
  expect(onClearCanvas).toHaveBeenCalledTimes(1);
  expect(onCommand).not.toHaveBeenCalled();
  expect(isMenuOpen()).toBe(false);
});

test('New canvas, last in the menu, goes to the session rather than the canvas', () => {
  const {press, onCommand, onNewCanvas, isMenuOpen} = renderBar();
  press('canvas-more');
  press('canvas-menu-newCanvas');
  expect(onNewCanvas).toHaveBeenCalledTimes(1);
  expect(onCommand).not.toHaveBeenCalled();
  expect(isMenuOpen()).toBe(false);
});
