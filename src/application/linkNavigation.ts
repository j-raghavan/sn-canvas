// Following a link out of a canvas, and stepping back along the links
// followed (#34, FR7). Each link followed leaves a step: the canvas it was
// followed from, the note page it was open over, and the note it led to
// (domain/linkTrail.ts). The badge over that note, and the same badge in
// Canvas's header, take one step back: reopen that note at its page, then
// bring Canvas up over it showing that canvas, so Close and Save to Note act
// on the note the canvas belongs to.

import type {NotePage} from '../domain/canvasIndex';
import {noteNameOf, trailKeptAt, withStep, type TrailStep} from '../domain/linkTrail';
import type {Logger} from '../sdk/types';
import {LINK_LAST_PAGE, type BackBadgePort, type ElementLink, type HostPort} from './ports';
import type {PluginView} from './pluginView';

const TAG = '[SNCANVAS]';

export type LinkNavigationDeps = {
  host: HostPort;
  badge: BackBadgePort;
  logger: Logger;
  view: PluginView;
  /** The folder canvases live in, settled by the session; null without one. */
  canvasDir: () => Promise<string | null>;
  /** The canvas shown, and how the session switches to another. */
  shown: {id: () => string; show: (dir: string, canvasId: string, at: NotePage | null) => Promise<void>};
  /** Saves the canvas shown, as the session does before leaving it. */
  saveShown: () => Promise<void>;
  /** Whether [canvasId] is still saved in [dir]: a link to one that has gone is not followed (#2). */
  canvasExists: (dir: string, canvasId: string) => Promise<boolean>;
  serially: (task: () => Promise<void>) => Promise<void>;
  /** Logs as news or as a warning. */
  report: (ok: boolean, message: string) => void;
};

export type LinkNavigation = {
  /** Asks for a note to link the selected element to; the link once one was picked, or null when the picker was cancelled. */
  pickNoteLink: () => Promise<ElementLink | null>;
  followLink: (link: ElementLink) => Promise<boolean>;
  /** Where one step back goes, or null with no link to go back along. */
  backTo: () => TrailStep | null;
  goBack: () => Promise<void>;
  /** The trail as an open of Canvas over page [at] leaves it (null for a canvas opened from a thumbnail). */
  keepTrailAt: (at: NotePage | null) => void;
  /** Lets the trail go: a canvas opened some other way is not where the links were followed from. */
  clearTrail: () => void;
};

export function createLinkNavigation({
  host,
  badge,
  logger,
  view,
  canvasDir,
  shown,
  saveShown,
  canvasExists,
  serially,
  report,
}: LinkNavigationDeps): LinkNavigation {
  // Kept in memory: the plugin runtime outlives the notes a link opens over it (seen on device, #34).
  let trail: readonly TrailStep[] = [];
  // One step back at a time: a second tap while one runs (the e-ink screen is slow to show it) would take two.
  let stepping: Promise<void> | null = null;

  const pickNoteLink = async (): Promise<ElementLink | null> => {
    const target = await view.pickOver(host.pickNote);
    if (target === null) {
      logger.log(`${TAG}[LINK] no note picked; nothing linked`);
      return null;
    }
    logger.log(`${TAG}[LINK] linking to note=${target}`);
    return {kind: 'note', target, page: LINK_LAST_PAGE};
  };

  /**
   * A link to another canvas of the same note (#2). Nothing leaves the plugin: the canvas shown is
   * saved and the target brought up in its place, with a step back to the one left. A target that is
   * no longer saved is refused rather than loaded, since showing it would open an empty canvas and
   * make it the note's own, losing the place the note had.
   */
  const followToCanvas = async (link: ElementLink): Promise<boolean> => {
    let switched = false;
    await serially(async () => {
      const dir = await canvasDir();
      if (dir === null) {
        return;
      }
      const from = shown.id();
      if (link.target === from || !(await canvasExists(dir, link.target))) {
        return;
      }
      const at = await host.currentPage();
      await shown.show(dir, link.target, at);
      switched = true;
      if (at !== null) {
        trail = withStep(trail, {...at, kind: 'canvas', canvasId: from});
      }
    });
    report(switched, `${TAG}[LINK] ${switched ? 'switched to' : 'could not switch to'} canvas=${link.target}`);
    return switched;
  };

  const followToNote = async (link: ElementLink): Promise<boolean> => {
    let opened = false;
    await serially(async () => {
      await saveShown();
      const from = await host.currentPage();
      opened = await view.leaveFor({notePath: link.target, page: link.page});
      if (!opened) {
        await view.comeBack();
      } else if (from !== null) {
        trail = withStep(trail, {...from, kind: 'note', canvasId: shown.id(), to: link.target});
        badge.show(noteNameOf(from.notePath), link.target);
      }
    });
    report(opened, `${TAG}[LINK] ${opened ? 'followed' : 'could not follow'} link to ${link.target} page=${link.page}`);
    return opened;
  };

  const followLink = (link: ElementLink): Promise<boolean> =>
    link.kind === 'canvas' ? followToCanvas(link) : followToNote(link);

  const stepBack = (): Promise<void> =>
    serially(async () => {
      badge.hide();
      const step = trail.at(-1);
      const dir = await canvasDir();
      if (step === undefined || dir === null) {
        // Nothing to go back to (never offered): bringing Canvas up would show a canvas over a note it may not be.
        logger.warn(`${TAG}[LINK] nothing to go back to`);
        return;
      }
      if (step.kind === 'canvas') {
        // The note never changed, so there is nothing to leave or come back to: bring the canvas the
        // link was followed from back up in place of the one it led to (#2).
        await shown.show(dir, step.canvasId, step);
        trail = trail.slice(0, -1);
        logger.log(`${TAG}[LINK] back to canvas=${step.canvasId}`);
        return;
      }
      const said = `to ${step.notePath} page=${step.page} canvas=${step.canvasId}`;
      const wasUp = view.isUp();
      // A step further back than one: its canvas is not the one shown. Switched while Canvas is up, since a
      // hidden Canvas has no view to save from or load into. From the badge over a note, it is the one shown,
      // and switching to it would only wait out the load's five seconds for a view that cannot come until
      // Canvas is back up, which is the whole of the delay in getting there.
      const here = await host.currentPage();
      const left = shown.id();
      if (wasUp || left !== step.canvasId) {
        await shown.show(dir, step.canvasId, step);
      }
      if (!(await view.leaveFor(step))) {
        // Things go back as they were, and the step stays: Canvas up on the canvas it showed, or, from the badge,
        // down with the badge back over the note.
        if (wasUp) {
          await view.comeBack();
          await shown.show(dir, left, here);
        } else {
          badge.show(noteNameOf(step.notePath), step.to);
        }
        logger.warn(`${TAG}[LINK] could not go back ${said}`);
        return;
      }
      if (await badge.arriveOver(step.notePath)) {
        view.cameUp();
        trail = trail.slice(0, -1);
        logger.log(`${TAG}[LINK] back ${said}`);
      } else {
        // Canvas stays down over whichever note opened, and the step stays: the sidebar then opens that note's
        // own canvas, or, back in the note the link led to, offers the step again. Bringing Canvas up here would
        // show the step's canvas over a note it may not belong to.
        logger.warn(`${TAG}[LINK] could not come back in time ${said}; Canvas stays down`);
      }
    });

  return {
    pickNoteLink,
    followLink,
    backTo: () => trail.at(-1) ?? null,
    goBack: () => {
      const running = stepping;
      if (running !== null) {
        return running;
      }
      const step = stepBack().finally(() => {
        stepping = null;
      });
      stepping = step;
      return step;
    },
    keepTrailAt: at => {
      trail = trailKeptAt(trail, at);
    },
    clearTrail: () => {
      trail = [];
    },
  };
}
