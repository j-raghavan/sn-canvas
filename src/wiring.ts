// Composition root: the one module that knows which concrete adapters back
// the application's ports. App.tsx hands what it builds to the screen, so
// every other module is tested against fakes.

import {createCanvasSession, type CanvasSession} from './application/canvasSession';
import {mintCanvasId} from './domain/canvasLink';
import {infoLog} from './diagnostics/log';
import {createHostSdk} from './infrastructure/hostSdk';
import {createNativeCanvasStore} from './infrastructure/nativeCanvasStore';
import {getLastButtonEvent, subscribeToButtonEvents} from './infrastructure/pluginRouter';
import type {Logger} from './sdk/types';
import type {ButtonEventSource} from './ui/SuperCanvasScreen';

// `log` carries the few per-action lines worth having in a release logcat, so it
// goes through infoLog, which survives production bundles.
const logger: Logger = {
  log: infoLog,
  warn: msg => console.warn(msg),
  error: msg => console.error(msg),
};

/** A session over the real host and native canvas; the screen builds one per mount. */
export function buildCanvasSession(): CanvasSession {
  return createCanvasSession({
    store: createNativeCanvasStore(logger),
    host: createHostSdk(logger),
    newCanvasId: () => mintCanvasId(Date.now(), Math.random),
    logger,
  });
}

/** The host's button presses, by id. */
export const hostButtonEvents: ButtonEventSource = {
  lastButtonId: () => getLastButtonEvent()?.id ?? null,
  onButton: listener => subscribeToButtonEvents(event => listener(event.id)),
};
