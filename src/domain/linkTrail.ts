// The trail of followed links (#34): each link followed from a canvas leaves a
// step, and the way back takes them in reverse, one at a time, as the
// firmware's own link Back does. A step is the canvas the link was followed
// from, the note page it was open over, and the note the link led [to].

import type {NotePage} from './canvasIndex';

export type TrailStep = NotePage & {
  readonly canvasId: string;
  readonly to: string;
  /**
   * A link to another canvas of the same note (#2): the note never changed, so going back switches
   * the canvas and nothing leaves the plugin. Absent for a link that opened a note.
   */
  readonly withinNote?: boolean;
};

/** What the header's Back offers to return to, for a step of either kind (#2). */
export function backLabelOf(step: TrailStep): string {
  return step.withinNote === true ? 'canvas' : noteNameOf(step.notePath);
}

/** Steps kept, the newest: a trail longer than anyone walks back is only memory held. */
export const MAX_TRAIL = 20;

/** [trail] with [step] on top. */
export function withStep(trail: readonly TrailStep[], step: TrailStep): TrailStep[] {
  return [...trail, step].slice(-MAX_TRAIL);
}

/**
 * [trail] as an open of Canvas over page [at] leaves it: kept while Canvas
 * opens in the note the last link led to (so a link followed from there
 * stacks on it), and let go anywhere else, or for a canvas opened from a
 * thumbnail ([at] null).
 */
export function trailKeptAt(trail: readonly TrailStep[], at: NotePage | null): readonly TrailStep[] {
  return at !== null && trail.at(-1)?.to === at.notePath ? trail : [];
}

/** A note's name as the device lists it: its file name, without the folder or the .note. */
export function noteNameOf(notePath: string): string {
  return notePath.slice(notePath.lastIndexOf('/') + 1).replace(/\.note$/i, '');
}
