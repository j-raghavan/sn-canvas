// The two ways into SuperCanvas: the button ids index.js registers with the
// host. Kept in one place because the id a press arrives with decides which
// canvas opens (see application/canvasSession.ts).

/** The NOTE sidebar button; opens the scratch canvas. Clear of sibling plugins' ids (sn-shapes uses 100). */
export const BUTTON_ID_SIDEBAR = 500;

/** The lasso-toolbar "Open Canvas" button; opens the canvas behind a lassoed SuperCanvas thumbnail (FR13). */
export const BUTTON_ID_OPEN_LINKED = 501;
