/**
 * v1d (FR12/FR13): a small, namespaced JSON payload stamped into a note
 * `Element.userData` field to trace an inserted thumbnail sticker back to
 * the SuperCanvas canvas it was saved from. `userData` is a shared generic
 * string field other plugins/the host may also write into, so this never
 * assumes it owns the whole field — it only reads/writes its own namespaced
 * key and degrades to `null` on anything it doesn't recognize, never throws.
 */

const NAMESPACE_KEY = 'snSuperCanvasId';

export function buildUserData(canvasId: string): string {
  return JSON.stringify({[NAMESPACE_KEY]: canvasId});
}

export function parseUserData(userData: string | null | undefined): string | null {
  if (!userData) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(userData);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }
  const value = (parsed as Record<string, unknown>)[NAMESPACE_KEY];
  return typeof value === 'string' && value.length > 0 ? value : null;
}
