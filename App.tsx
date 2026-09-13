import React from 'react';
import SuperCanvasScreen from './src/SuperCanvasScreen';
import {installPluginRouter} from './src/pluginRouter';

installPluginRouter(); // idempotent — safe to call from index.js AND here (some test harnesses render App without running index.js).

export default function App(): React.JSX.Element {
  return <SuperCanvasScreen />;
}
