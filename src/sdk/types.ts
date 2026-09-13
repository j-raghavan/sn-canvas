// Narrow shapes for SDK-facing code, defined here so sn-plugin-lib's real
// classes match by structural typing without importing them (the same
// approach as sn-copilot's src/sdk/types.ts).

/** sn-plugin-lib's response envelope. Its .d.ts types most calls as a bare `Object`. */
export type APIResponse<T> = {
  success: boolean;
  result?: T;
  error?: {code: number; message: string};
};

export type Logger = {
  log: (msg: string) => void;
  warn: (msg: string) => void;
  error: (msg: string) => void;
};

/** True when an sn-plugin-lib call reported success. */
export function succeeded(response: unknown): boolean {
  return (response as APIResponse<unknown> | null | undefined)?.success === true;
}

/** The `result` of a successful sn-plugin-lib call; undefined for a failed or malformed one. */
export function resultOf<T>(response: unknown): T | undefined {
  return succeeded(response) ? (response as APIResponse<T>).result : undefined;
}
