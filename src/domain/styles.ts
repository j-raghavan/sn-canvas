// The style panel's options (FR19), by the ids the native canvas stores
// (StyleColor, FillStyle, DashStyle and SizeStyle in CanvasStyle.kt), and the
// canvas-state event the action bar and style panel follow (FR18).

export const COLORS = [
  {id: 'black', name: 'Black', hex: '#1d1d1d'},
  {id: 'grey', name: 'Grey', hex: '#9fa8b2'},
  {id: 'light-violet', name: 'Light violet', hex: '#e085f4'},
  {id: 'violet', name: 'Violet', hex: '#ae3ec9'},
  {id: 'blue', name: 'Blue', hex: '#4465e9'},
  {id: 'light-blue', name: 'Light blue', hex: '#4ba1f1'},
  {id: 'yellow', name: 'Yellow', hex: '#f1ac4b'},
  {id: 'orange', name: 'Orange', hex: '#e16919'},
  {id: 'green', name: 'Green', hex: '#099268'},
  {id: 'light-green', name: 'Light green', hex: '#4cb05e'},
  {id: 'light-red', name: 'Light red', hex: '#f87777'},
  {id: 'red', name: 'Red', hex: '#e03131'},
] as const;

export type ColorId = (typeof COLORS)[number]['id'];

export const FILLS = ['none', 'semi', 'solid', 'pattern'] as const;
export type FillId = (typeof FILLS)[number];

export const DASHES = ['draw', 'dashed', 'dotted', 'solid'] as const;
/** An image's frame can also be left off (FR22). */
export const IMAGE_DASHES = [...DASHES, 'none'] as const;
export type DashId = (typeof IMAGE_DASHES)[number];

export const SIZES = ['s', 'm', 'l', 'xl'] as const;
export type SizeId = (typeof SIZES)[number];

/** tldraw's opacity steps; the native side accepts 0.1 to 1. */
export const OPACITY_STEPS = [0.1, 0.25, 0.5, 0.75, 1] as const;

export type CanvasStyle = {color: ColorId; opacity: number; fill: FillId; dash: DashId; size: SizeId};

/** A style property, as the native `setStyle` command takes it. */
export type StyleProperty = keyof CanvasStyle;

/** tldraw's defaults, which the native canvas also starts with. */
export const DEFAULT_STYLE: CanvasStyle = {color: 'black', opacity: 1, fill: 'none', dash: 'draw', size: 'm'};

/** What the action bar and style panel show, as the canvas reports it; a selected table brings its row and column actions. */
export type CanvasUiState = {
  canUndo: boolean;
  canRedo: boolean;
  hasSelection: boolean;
  /** Whether the canvas holds anything at all; Clear canvas applies only then. */
  hasContent: boolean;
  /** How many elements are selected; several at once rule out resizing, rotating and editing text. */
  selectionCount: number;
  /** Whether anything selected belongs to a group, so Ungroup applies. */
  canUngroup: boolean;
  /** Whether the one selected element links somewhere, so the link can be taken off it. */
  hasLink: boolean;
  /** Whether a cell of the selected table was tapped, so Remove row and Remove column know which one (#53). */
  hasTableCell: boolean;
  /** The selected element's type (such as 'table'), or null with nothing selected. */
  selectedType: string | null;
  style: CanvasStyle;
};

export const INITIAL_UI_STATE: CanvasUiState = {
  canUndo: false,
  canRedo: false,
  hasSelection: false,
  hasContent: false,
  selectionCount: 0,
  canUngroup: false,
  hasLink: false,
  hasTableCell: false,
  selectedType: null,
  style: DEFAULT_STYLE,
};

const COLOR_IDS: readonly ColorId[] = COLORS.map(color => color.id);

const oneOf = <T extends string>(options: readonly T[], value: unknown, fallback: T): T =>
  options.includes(value as T) ? (value as T) : fallback;

const isOpacity = (value: unknown): value is number => typeof value === 'number' && value >= 0.1 && value <= 1;

/** The canvas-state event body, validated at the bridge: anything missing or unknown falls back to the initial state. */
export function parseUiState(payload: unknown): CanvasUiState {
  const body = (payload ?? {}) as Record<string, unknown>;
  const style = (body.style ?? {}) as Record<string, unknown>;
  return {
    canUndo: body.canUndo === true,
    canRedo: body.canRedo === true,
    hasSelection: body.hasSelection === true,
    hasContent: body.hasContent === true,
    selectionCount: Number.isInteger(body.selectionCount) ? (body.selectionCount as number) : 0,
    canUngroup: body.canUngroup === true,
    hasLink: body.hasLink === true,
    hasTableCell: body.hasTableCell === true,
    selectedType: typeof body.selectedType === 'string' && body.selectedType !== '' ? body.selectedType : null,
    style: {
      color: oneOf(COLOR_IDS, style.color, DEFAULT_STYLE.color),
      opacity: isOpacity(style.opacity) ? style.opacity : DEFAULT_STYLE.opacity,
      fill: oneOf(FILLS, style.fill, DEFAULT_STYLE.fill),
      dash: oneOf(IMAGE_DASHES, style.dash, DEFAULT_STYLE.dash),
      size: oneOf(SIZES, style.size, DEFAULT_STYLE.size),
    },
  };
}

/** The OPACITY_STEPS index nearest to [opacity], where the opacity slider's knob sits. */
export function opacityStepIndex(opacity: number): number {
  let nearest = 0;
  OPACITY_STEPS.forEach((step, index) => {
    if (Math.abs(step - opacity) < Math.abs(OPACITY_STEPS[nearest] - opacity)) {
      nearest = index;
    }
  });
  return nearest;
}

export function colorName(color: ColorId): string {
  return COLORS[COLOR_IDS.indexOf(color)].name;
}

/**
 * What a swatch shows: the e-ink gray the canvas draws [color] with, when the
 * native side exports [einkGrays], so the panel matches the canvas; otherwise
 * the true colour.
 */
export function swatchColor(color: ColorId, einkGrays: Readonly<Record<string, string>> | null): string {
  return einkGrays?.[color] ?? COLORS[COLOR_IDS.indexOf(color)].hex;
}
