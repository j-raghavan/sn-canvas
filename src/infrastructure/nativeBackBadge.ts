// BackBadgePort over the native BackBadgeModule
// (android/app/src/main/java/com/sncanvas/canvas/BackBadgeModule.kt), and the
// source of its taps. Like the canvas store it never throws: without the module
// (a test harness, an older build) the badge is simply never shown, and the
// sidebar still reopens Canvas.

import {NativeEventEmitter, NativeModules, type NativeModule} from 'react-native';
import type {BackBadgePort, BackBadgeTaps} from '../application/canvasSession';
import type {Logger} from '../sdk/types';

export type NativeBackBadgeModule = NativeModule & {
  show: (label: string, notePath: string) => void;
  hide: () => void;
};

/** What BackBadgeModule.kt emits as the badge is tapped. */
export const BACK_BADGE_TAPPED = 'canvasBackBadgeTapped';

export function createNativeBackBadge(
  logger: Logger,
  native: NativeBackBadgeModule | undefined = (NativeModules as {BackBadge?: NativeBackBadgeModule} | undefined)?.BackBadge,
): BackBadgePort & BackBadgeTaps {
  if (!native) {
    logger.warn('[SNCANVAS] NativeModules.BackBadge is missing; no way back from a followed link but the sidebar');
    return {show: () => undefined, hide: () => undefined, onTapped: () => () => undefined};
  }
  const emitter = new NativeEventEmitter(native);
  return {
    show: (label, notePath) => native.show(label, notePath),
    hide: () => native.hide(),
    onTapped: listener => {
      const subscription = emitter.addListener(BACK_BADGE_TAPPED, listener);
      return () => subscription.remove();
    },
  };
}
