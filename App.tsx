import React from 'react';
import CanvasScreen from './src/ui/CanvasScreen';
import {installPluginRouter} from './src/infrastructure/pluginRouter';
import {buildCanvasSession, hostButtonEvents} from './src/wiring';

installPluginRouter(); // idempotent — safe to call from index.js AND here (some test harnesses render App without running index.js).

export default function App(): React.JSX.Element {
  return <CanvasScreen createSession={buildCanvasSession} buttonEvents={hostButtonEvents} />;
}
