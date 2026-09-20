// Whether Canvas is on screen, and the moves that put it there or step it
// aside. The host hides Canvas for anything the note opens over it, but only
// while the note holds it: hiding Canvas unbinds it (the host's
// PluginApp.closePluginView clears the client binder), and only the note binds
// it again. Canvas brought up by the way back is unbound, so it would cover a
// picker the note opens; it steps aside itself instead (#34).

import type {HostPort} from './ports';
import type {NotePage} from '../domain/canvasIndex';

export type PluginView = {
  /** Canvas came up because the note opened it (the sidebar, a thumbnail), so the host hides it for the note's own screens. */
  openedByNote: () => void;
  /** Whether Canvas is on screen. */
  isUp: () => boolean;
  /** Canvas came up over the note a step back reopened, brought up natively rather than by [comeBack]. */
  cameUp: () => void;
  /** Hides Canvas, which unbinds it from the note until the note opens it again. */
  stepAside: () => Promise<void>;
  /** Brings Canvas back to the front, as it was left. */
  comeBack: () => Promise<void>;
  /**
   * Opens the note page [to], Canvas stepping aside first: one brought up by
   * the way back would otherwise stay in front of it. Whether Canvas comes back
   * when the note will not open is the caller's to say.
   */
  leaveFor: (to: NotePage) => Promise<boolean>;
  /**
   * Runs one of the host's pickers over Canvas. Canvas the note brought up is
   * hidden by the host for it; Canvas the way back brought up is not, and would
   * cover it, so it steps aside itself and comes back once the pick is made.
   */
  pickOver: <T>(pick: () => Promise<T>) => Promise<T>;
};

export function createPluginView(host: HostPort): PluginView {
  let isCanvasUp = false;
  let isBoundToNote = false;

  const stepAside = async (): Promise<void> => {
    await host.closeView();
    isCanvasUp = false;
    isBoundToNote = false;
  };

  const comeBack = async (): Promise<void> => {
    isCanvasUp = await host.showView();
  };

  const pickOver = async <T>(pick: () => Promise<T>): Promise<T> => {
    if (isBoundToNote || !isCanvasUp) {
      return pick();
    }
    await stepAside();
    try {
      return await pick();
    } finally {
      await comeBack();
    }
  };

  return {
    openedByNote: () => {
      isCanvasUp = true;
      isBoundToNote = true;
    },
    isUp: () => isCanvasUp,
    cameUp: () => {
      isCanvasUp = true;
    },
    stepAside,
    comeBack,
    leaveFor: async to => {
      await stepAside();
      return host.openNote(to.notePath, to.page);
    },
    pickOver,
  };
}
