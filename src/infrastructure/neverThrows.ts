// The one shape every adapter has: a host or native call that never throws.
// A failure is logged and reported as the fallback, so the application
// branches on plain values instead of wrapping calls in try/catch.

import {TAG} from '../diagnostics/log';
import type {Logger} from '../sdk/types';

/** Runs [call] through [logger]: its result, or [fallback] with a warning when it throws. */
export const neverThrows =
  (logger: Logger) =>
  async <T>(call: string, fallback: T, run: () => Promise<T>): Promise<T> => {
    try {
      return await run();
    } catch (error) {
      logger.warn(`${TAG} ${call} failed: ${String(error)}`);
      return fallback;
    }
  };
