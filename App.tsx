import React from 'react';
import SuperCanvasScreen from './src/ui/SuperCanvasScreen';
import {installPluginRouter} from './src/infrastructure/pluginRouter';
import {buildCanvasSession, hostButtonEvents} from './src/wiring';

installPluginRouter(); // idempotent — safe to call from index.js AND here (some test harnesses render App without running index.js).

export default function App(): React.JSX.Element {
  return <SuperCanvasScreen createSession={buildCanvasSession} buttonEvents={hostButtonEvents} />;
}
