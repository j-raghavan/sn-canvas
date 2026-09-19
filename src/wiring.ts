// Composition root: the one module that knows which concrete adapters back
// the application's ports. App.tsx hands what it builds to the screen, so
// every other module is tested against fakes.

import {createCanvasSession, type BackBadgeTaps, type CanvasSession} from './application/canvasSession';
import {mintCanvasId} from './domain/canvasLink';
import {infoLog} from './diagnostics/log';
import {createFileAccess} from './infrastructure/filePermissions';
import {createHostSdk} from './infrastructure/hostSdk';
import {createNativeBackBadge} from './infrastructure/nativeBackBadge';
import {createNativeCanvasStore} from './infrastructure/nativeCanvasStore';
import {getLastButtonEvent, subscribeToButtonEvents} from './infrastructure/pluginRouter';
import type {Logger} from './sdk/types';
import type {ButtonEventSource} from './ui/CanvasScreen';

// `log` carries the few per-action lines worth having in a release logcat, so it
// goes through infoLog, which survives production bundles.
const logger: Logger = {
  log: infoLog,
  warn: msg => console.warn(msg),
  error: msg => console.error(msg),
};

/**
 * Canvas's file permissions, asked for once: index.js calls it as the plugin
 * loads, and each session awaits the same request before it opens a canvas.
 */
export const requestFileAccess = createFileAccess(logger);

// One badge for the plugin: every session shows and hides the same one, and the screen hears its taps.
const backBadge = createNativeBackBadge(logger);

/** The back badge's taps, which bring Canvas back from a followed link. */
export const backBadgeTaps: BackBadgeTaps = backBadge;

/** A session over the real host and native canvas; the screen builds one per mount. */
export function buildCanvasSession(): CanvasSession {
  return createCanvasSession({
    store: createNativeCanvasStore(logger),
    host: createHostSdk(logger, requestFileAccess),
    badge: backBadge,
    newCanvasId: () => mintCanvasId(Date.now(), Math.random),
    logger,
  });
}

/** The host's button presses, by id. */
export const hostButtonEvents: ButtonEventSource = {
  lastButtonId: () => getLastButtonEvent()?.id ?? null,
  onButton: listener => subscribeToButtonEvents(event => listener(event.id)),
};
