/**
 * Smoke test over the real wiring: App renders the screen, and Close reaches
 * PluginManager.closePluginView. No plugin directory is reported, so nothing
 * touches the (absent) native canvas module.
 */
jest.mock('sn-plugin-lib', () => ({
  PluginManager: {
    init: jest.fn(),
    registerButtonListener: jest.fn(() => ({remove: jest.fn()})),
    getPluginDirPath: jest.fn().mockResolvedValue(null),
    closePluginView: jest.fn().mockResolvedValue(true),
  },
}));

import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import App from '../App';

let warn: jest.SpyInstance;
beforeEach(() => {
  warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => warn.mockRestore());

test('renders the SuperCanvas screen', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<App />);
  });
  expect(renderer!.toJSON()).not.toBeNull();
});

test('the close button closes the plugin view', async () => {
  const {PluginManager} = require('sn-plugin-lib');
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(<App />);
  });
  await act(async () => {
    renderer!.root.findByProps({testID: 'supercanvas-close'}).props.onPress();
  });
  expect(PluginManager.closePluginView).toHaveBeenCalled();
});
