/**
 * Smoke test: App renders without throwing and mounts the SuperCanvas
 * header + native canvas view placeholder. Full interaction testing is
 * deferred until the tool palette / native bridge round trip exist
 * (spec/SuperCanvas-PRD.md §13 phasing).
 */
jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    init: jest.fn(),
    registerButtonListener: jest.fn(() => ({remove: jest.fn()})),
    closePluginView: jest.fn().mockResolvedValue(true),
  },
}));

import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import App from '../App';

test('renders the SuperCanvas screen', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<App />);
  });
  const tree = renderer!.toJSON();
  expect(tree).not.toBeNull();
});

test('close button calls PluginManager.closePluginView', async () => {
  const {PluginManager} = require('sn-plugin-lib');
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<App />);
  });
  const closeButton = renderer!.root.findByProps({testID: 'supercanvas-close'});
  await act(async () => {
    closeButton.props.onPress();
  });
  expect(PluginManager.closePluginView).toHaveBeenCalled();
});
