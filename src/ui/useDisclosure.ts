// A panel that a button opens and closes, and that tells the screen when it
// opens so the onboarding hints can go: the ⋮ menu, the style panel and the zoom
// control all work this way. Two rules live here rather than in each of them,
// because both have been got wrong once already: the screen hears about an
// opening and not a closing, so the hints are not put away twice; and it hears
// outside the state updater, which has to stay pure since React may run it more
// than once.

import {useState} from 'react';

export type Disclosure = {
  isOpen: boolean;
  /** Opens a closed panel (telling the screen) or closes an open one. */
  toggle: () => void;
  /** Closes it without telling the screen: a choice was made, and the screen already knows. */
  close: () => void;
};

export function useDisclosure(onOpen: () => void): Disclosure {
  const [isOpen, setOpen] = useState(false);
  return {
    isOpen,
    toggle: () => {
      if (!isOpen) {
        onOpen();
      }
      setOpen(!isOpen);
    },
    close: () => setOpen(false),
  };
}
