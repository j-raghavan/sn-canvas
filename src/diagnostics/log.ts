// Two diagnostic helpers with different visibility profiles (shared with
// sn-copilot's src/diagnostics/log.ts).
//
// debugLog — __DEV__-gated. Collapses to a no-op in production
// bundles (Metro builds with --dev false set __DEV__ to false). Use
// for chatty per-event logs whose signal isn't worth shipping.
//
// infoLog — always emits via console.warn so the line survives
// production bundles and lands in logcat. Use for the small set of
// "what just happened on this user action" lines we want available
// for support/diagnosis. Don't pass note paths or other identifying
// data — keep it to ids, counts, and booleans.
//
// Errors and actionable warnings should keep using console.warn /
// console.error directly.

/** What every line Canvas logs starts with, so its lines stand out in a logcat full of the firmware's own. */
export const TAG = '[SNCANVAS]';

export const debugLog = (...args: unknown[]): void => {
  if (typeof __DEV__ !== 'undefined' && __DEV__ === false) {
    return;
  }
  console.log(...args);
};

export const infoLog = (...args: unknown[]): void => {
  console.warn(...args);
};
