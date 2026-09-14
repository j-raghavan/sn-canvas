// What the canvas sends when text editing begins (FR6/FR24): which element
// (and table cell) is being edited, its text, and where the keyboard editor
// goes, in layout units (TextEditRequest.kt).

export type TextEditRequest = {
  elementId: string;
  /** The table cell being edited, or null for a text box or sticky note. */
  cellIndex: number | null;
  text: string;
  left: number;
  top: number;
  width: number;
  height: number;
  fontSize: number;
  isNote: boolean;
};

const isNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

/** The edit-text event body, validated at the bridge: null when it can't place an editor. */
export function parseTextEditRequest(payload: unknown): TextEditRequest | null {
  const body = (payload ?? {}) as Record<string, unknown>;
  const {elementId, cellIndex, text, left, top, width, height, fontSize} = body;
  if (typeof elementId !== 'string' || elementId === '' || ![left, top, width, height, fontSize].every(isNumber)) {
    return null;
  }
  return {
    elementId,
    cellIndex: isNumber(cellIndex) && cellIndex >= 0 ? cellIndex : null,
    text: typeof text === 'string' ? text : '',
    left: left as number,
    top: top as number,
    width: width as number,
    height: height as number,
    fontSize: fontSize as number,
    isNote: body.isNote === true,
  };
}
