// The trail of followed links (#34, #2): each link followed from a canvas
// leaves a step, and the way back takes them in reverse, one at a time, as the
// firmware's own link Back does. A step holds where it was followed from, the
// canvas and the note page, and is of the same two kinds the link was: one that
// opened another note, or one that brought up another canvas of the same note.

import type {NotePage} from './canvasIndex';

/** Where a step was followed from: the canvas shown, over the note page it was shown on. */
type From = NotePage & {readonly canvasId: string};

/**
 * A link followed, and how to come back along it.
 *
 * A note step left the plugin, so coming back reopens [to] and brings Canvas up over it. A canvas
 * step never left the note, so coming back only shows [canvasId] again. They are told apart by
 * [kind], the same word the link itself used, so a step cannot half-describe itself.
 */
export type TrailStep = (From & {readonly kind: 'note'; readonly to: string}) | (From & {readonly kind: 'canvas'});

/**
 * The note a step is still good in: a note step is good in the note it led to, a canvas step in the
 * note it never left. Canvas reopening anywhere else means these links were not followed from there.
 */
export function noteOf(step: TrailStep): string {
  return step.kind === 'note' ? step.to : step.notePath;
}

/** What the header's Back offers to return to: a note by name, or simply the canvas left behind. */
export function backLabelOf(step: TrailStep): string {
  return step.kind === 'note' ? noteNameOf(step.notePath) : 'canvas';
}

/** Steps kept, the newest: a trail longer than anyone walks back is only memory held. */
export const MAX_TRAIL = 20;

/** [trail] with [step] on top. */
export function withStep(trail: readonly TrailStep[], step: TrailStep): TrailStep[] {
  return [...trail, step].slice(-MAX_TRAIL);
}

/**
 * [trail] as an open of Canvas over page [at] leaves it: kept while Canvas
 * opens in the note the trail is good in (so a link followed from there
 * stacks on it), and let go anywhere else, or for a canvas opened from a
 * thumbnail ([at] null).
 */
export function trailKeptAt(trail: readonly TrailStep[], at: NotePage | null): readonly TrailStep[] {
  const top = trail.at(-1);
  return at !== null && top !== undefined && noteOf(top) === at.notePath ? trail : [];
}

/** A note's name as the device lists it: its file name, without the folder or the .note. */
export function noteNameOf(notePath: string): string {
  return notePath.slice(notePath.lastIndexOf('/') + 1).replace(/\.note$/i, '');
}
