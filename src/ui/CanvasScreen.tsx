// The plugin's one screen (PRD §3 step 3, §9): the native canvas full-screen,
// under a header (Save to Note, Close), with the style panel top right, the
// action bar above the floating toolbar (FR16-FR19), and the keyboard text
// editor over whatever text the canvas is editing (FR6/FR24). Thin by design: the canvas
// session and the button presses are injected (wiring.ts, via App.tsx), and
// the action bar and style panel follow the state the canvas reports.

import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType} from 'react-native';
import type {BackBadgeTaps, CanvasSession, NoteCanvas} from '../application/canvasSession';
import {INITIAL_UI_STATE, parseUiState, swatchColor, type CanvasUiState} from '../domain/styles';
import {LINK_LAST_PAGE, parseElementLink} from '../domain/canvasLink';
import {backLabelOf, type TrailStep} from '../domain/linkTrail';
import {parseTextEditRequest, type TextEditRequest} from '../domain/textEdit';
import ActionBar from './ActionBar';
import CanvasList from './CanvasList';
import ConfirmDialog from './ConfirmDialog';
import HelpHints from './HelpHints';
import HelpTour from './HelpTour';
import StylePanel from './StylePanel';
import TextEditor from './TextEditor';
import Toolbar from './Toolbar';
import ZoomControl from './ZoomControl';
import {
  CanvasNativeView,
  dispatchCanvasCommand,
  nativeEinkGrays,
  type CanvasCommand,
  type CanvasViewRef,
  type ToolMode,
} from './nativeCanvasView';

/** Button presses into the plugin, by id (see domain/entryPoints.ts). */
export type ButtonEventSource = {
  /** The press that opened the plugin, if it arrived before this screen mounted. */
  lastButtonId: () => number | null;
  onButton: (listener: (buttonId: number) => void) => () => void;
};

type Props = {
  /** Called once per mount: a session belongs to the native view it drives. */
  createSession: () => CanvasSession;
  buttonEvents: ButtonEventSource;
  /** Taps on the badge a followed link leaves over the note (#34), each bringing Canvas back. */
  backBadgeTaps: BackBadgeTaps;
};

// FR23: Save to Note has its own icon; the upward arrow is kept for Export to PDF.
const SAVE_TO_NOTE_ICON = require('../../assets/icons/action-save-to-note.png');
const EXPORT_PDF_ICON = require('../../assets/icons/action-save.png');

// A notice names a file from the storage root down, as the device's file manager shows it.
const STORAGE_ROOT = '/storage/emulated/0';
const CLOSE_ICON = require('../../assets/icons/action-close.png');
// #34: the firmware's return-badge arrow, white outlined in black, shown untinted.
const BACK_ARROW_ICON = require('../../assets/icons/badge-back-arrow.png');

/**
 * What Save to Note says once the thumbnail is in: it waits on the page to be placed, and the note drops it if
 * something else is done first (#30).
 */
const ADDED_TO_NOTE = 'Added to note: place it on the page before anything else';
const COULD_NOT_ADD = 'Could not add this to the note; nothing was changed';

/** How long the "Added to note" confirmation stays up. */
export const NOTICE_MS = 2500;

export default function CanvasScreen({createSession, buttonEvents, backBadgeTaps}: Props): React.JSX.Element {
  const [session] = useState(createSession);
  const [einkGrays] = useState(nativeEinkGrays);
  const [toolMode, setToolMode] = useState<ToolMode>('select');
  const [ui, setUi] = useState<CanvasUiState>(INITIAL_UI_STATE);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<TextEditRequest | null>(null);
  // The onboarding hints (HelpHints): on by default each time the plugin opens, off once the canvas is
  // touched (pen or finger) or a toolbar action is taken; the (?) button in Toolbar's dock brings them back.
  const [showHints, setShowHints] = useState(false);
  // The tour (#71): on its own the first time Canvas is opened after an install, and from the ⋮
  // menu after that. It says what things do, where the hints say which control is which.
  const [showTour, setShowTour] = useState(false);
  const [tourOnOpen, setTourOnOpen] = useState(false);
  // Clearing takes everything at once, so both ways in (the eraser's options, the ⋮ menu) ask here first.
  const [isConfirmingClear, setConfirmingClear] = useState(false);
  const canvasRef = useRef<CanvasViewRef>(null);
  // #30: the canvases made in this note, while the list of them is open.
  // The note's canvases, shown either to switch to one or to link the selection to one (#2).
  const [canvasPicker, setCanvasPicker] = useState<{canvases: readonly NoteCanvas[]; forLink: boolean} | null>(null);
  // #34: one step back along the links followed to this canvas, offered as the firmware offers its own.
  const [back, setBack] = useState<TrailStep | null>(null);
  /**
   * Runs something that can change the trail, then re-reads it. Every one of these used to say so for
   * itself, and the one added for canvas links forgot, which left a link followed with no way back on
   * screen: a canvas link leaves Canvas up, so nothing else re-reads it (#2).
   */
  const withTrail = useCallback(
    async (run: () => Promise<unknown>) => {
      await run();
      setBack(session.backTo());
    },
    [session],
  );

  const goBack = useCallback(() => withTrail(() => session.goBack()), [session, withTrail]);

  // The plugin runtime stays warm between opens, so this screen can stay mounted across them: every press
  // re-resolves which canvas to show.
  useEffect(() => {
    const openCanvas = (buttonId: number | null) =>
      withTrail(() => session.open(buttonId)).then(() => {
        // Asked after the open, because the open is what settles it: the marker saying whether
        // Canvas has ever been opened since it was installed is read and written in there (#71).
        return session.showsTourOnOpen().then(everyTime => {
          setTourOnOpen(everyTime);
          // Read before it is needed, not inside the test: it is spent by being read, and an || that
          // never reaches it leaves it set. Asked for every time and then turned off, that saved
          // answer brings the tour back once more on the open straight after (#71).
          const firstOpen = session.takeFirstOpenSinceInstall();
          if (everyTime || firstOpen) {
            setShowTour(true);
          }
        });
      });
    openCanvas(buttonEvents.lastButtonId());
    return buttonEvents.onButton(openCanvas);
  }, [session, buttonEvents, withTrail]);

  // The badge over a note a link opened: a tap is one step back, as the header's is.
  useEffect(() => backBadgeTaps.onTapped(goBack), [goBack, backBadgeTaps]);

  useEffect(() => {
    if (notice === null) {
      return undefined;
    }
    const timer = setTimeout(() => setNotice(null), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice]);

  const runCommand = (command: CanvasCommand, args?: readonly string[]) =>
    dispatchCanvasCommand(canvasRef.current, command, args);

  // FR22: a new image arrives selected, so select is the tool that moves and resizes it.
  const insertImage = async () => {
    setShowHints(false);
    if (await session.insertImage()) {
      setToolMode('select');
    }
  };

  const changeTool = (tool: ToolMode) => {
    setShowHints(false);
    setToolMode(tool);
  };

  // FR11: say where the PDF went, since nothing on the canvas shows it; or that it didn't.
  const exportPdf = async () => {
    const path = await session.exportPdf();
    setNotice(path === null ? 'Could not export the PDF' : `Saved to ${path.replace(`${STORAGE_ROOT}/`, '')}`);
  };

  // FR7: the picker names the note; the canvas stores the link on whatever is selected.
  const linkToNote = async () => {
    const link = await session.pickNoteLink();
    if (link !== null) {
      runCommand('linkSelected', [link.kind, link.target, String(link.page)]);
      setNotice('Linked to the note');
    }
  };

  // FR7: a tap on a link's glyph; the note opens over the plugin, so nothing more is said about it here.
  const followLink = async (payload: unknown) => {
    const link = parseElementLink(payload);
    if (link === null) {
      return;
    }
    let followed = false;
    await withTrail(async () => {
      followed = await session.followLink(link);
    });
    if (!followed) {
      setNotice(link.kind === 'canvas' ? 'That canvas is no longer here' : 'Could not open that note');
    }
  };

  const showNoteCanvases = async () => setCanvasPicker({canvases: await session.canvasesHere(), forLink: false});

  // #2: the same list, picked from to link rather than to switch. The canvas shown is not offered,
  // since an element linking to the canvas it is drawn on would go nowhere.
  const linkToCanvas = async () => {
    const canvases = (await session.canvasesHere()).filter(canvas => !canvas.isShown);
    if (canvases.length === 0) {
      setNotice('This note has no other canvas to link to');
      return;
    }
    setCanvasPicker({canvases, forLink: true});
  };

  const pickedCanvas = async (canvasId: string) => {
    const forLink = canvasPicker?.forLink === true;
    setCanvasPicker(null);
    if (forLink) {
      runCommand('linkSelected', ['canvas', canvasId, String(LINK_LAST_PAGE)]);
      setNotice('Linked to the canvas');
      return;
    }
    await withTrail(() => session.switchTo(canvasId));
  };

  const newCanvas = () => withTrail(() => session.newCanvas());

  const clearCanvas = () => withTrail(() => session.clearCanvas());

  // FR12: confirm what happened, so the thumbnail isn't added twice for want of feedback, and a
  // refresh of the one already on the page doesn't look like nothing happened.
  const saveToNote = async () => {
    const saved = await session.saveToNote();
    if (saved === 'ignored') {
      // A second tap while the first is still going. Saying anything here would be saying it of a
      // save that is still running and about to work.
      return;
    }
    // Said either way otherwise. A save that did not happen used to say nothing at all, which reads
    // exactly like one that did nothing visible, and the canvas is what the user would have gone on
    // drawing on believing it was in the note (#68).
    setNotice(saved !== null ? ADDED_TO_NOTE : COULD_NOT_ADD);
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View style={styles.headerStart}>
          {back !== null && (
            <Pressable
              testID="canvas-back"
              accessibilityLabel={`Back to ${backLabelOf(back)}`}
              style={styles.backBadge}
              onPress={goBack}>
              <View style={styles.backBox}>
                <Text style={styles.backLabel} numberOfLines={1}>
                  {backLabelOf(back)}
                </Text>
              </View>
              <Image source={BACK_ARROW_ICON} style={styles.backArrow} />
            </Pressable>
          )}
          <Text style={styles.title}>Canvas</Text>
        </View>
        <View style={styles.headerActions}>
          {/* An empty canvas has nothing to export or to show in a note: both would produce a blank page. */}
          <HeaderButton
            testID="canvas-export-pdf"
            label="Export to PDF"
            icon={EXPORT_PDF_ICON}
            onPress={exportPdf}
            disabled={!ui.hasContent}
          />
          <HeaderButton
            testID="canvas-save-to-note"
            label="Save to Note"
            icon={SAVE_TO_NOTE_ICON}
            onPress={saveToNote}
            disabled={!ui.hasContent}
          />
          <HeaderButton testID="canvas-close" label="Close" icon={CLOSE_ICON} onPress={session.close} />
        </View>
      </View>
      <View style={styles.canvasArea}>
        <CanvasNativeView
          ref={canvasRef}
          style={StyleSheet.absoluteFill}
          toolMode={toolMode}
          onCanvasState={event => setUi(parseUiState(event.nativeEvent))}
          // The hints come up with an empty canvas, which has nothing to hide behind them and nothing yet to do, and
          // stay away from one with work on it; the (?) button brings them back. Decided by the load alone: the canvas
          // state also arrives as the view re-attaches, still saying what the canvas before this one held.
          onCanvasLoaded={event => setShowHints(!parseUiState(event.nativeEvent).hasContent)}
          onEditText={event => setEditing(parseTextEditRequest(event.nativeEvent))}
          onCanvasTouch={() => setShowHints(false)}
          onFollowLink={event => followLink(event.nativeEvent)}
        />
        <ActionBar
          ui={ui}
          onCommand={runCommand}
          onNewCanvas={newCanvas}
          onClearCanvas={() => setConfirmingClear(true)}
          onLinkToNote={linkToNote}
          onLinkToCanvas={linkToCanvas}
          onNoteCanvases={showNoteCanvases}
          onShowTour={() => setShowTour(true)}
          onMenuOpen={() => setShowHints(false)}
        />
        <ZoomControl ui={ui} onCommand={command => runCommand(command)} onOpen={() => setShowHints(false)} />
        {/* Over the action bar, which is always there and sits across the middle tools: the eraser's hint has to
            cross it to reach the eraser, and only its arrow does. Still under the style panel and the toolbar,
            and the ⋮ menu dismisses the hints as it opens, so nothing a tap opens is ever drawn over. */}
        {/* Not while the tour is up. A first open after an install has an empty canvas, which is when
            the hints put themselves there, and is the one time the tour comes up on its own: both at
            once is two sets of arrows over one screen, half of them under the scrim. Said here rather
            than when the tour opens, because the canvas finishes loading after it and would put them
            back. They are waiting once it is closed, which is where a tour of the plugin should leave
            someone on an empty canvas. */}
        {showHints && !showTour && <HelpHints />}
        <StylePanel
          style={ui.style}
          selectedType={ui.selectedType}
          swatch={color => swatchColor(color, einkGrays)}
          onChange={(property, value) => runCommand('setStyle', [property, value])}
          onOpen={() => setShowHints(false)}
        />
        <Toolbar
          toolMode={toolMode}
          onToolChange={changeTool}
          onInsertImage={insertImage}
          showHints={showHints}
          onToggleHints={() => setShowHints(current => !current)}
          onClearCanvas={() => setConfirmingClear(true)}
          canClearCanvas={ui.hasContent}
        />
        {/* Over everything, unlike the hints: the tour puts a scrim down and closes on a tap outside
            its card, so a toolbar or a style panel left on top of it would sit crisp over the faded
            canvas and still take the taps meant to dismiss it (#71). */}
        {showTour && (
          <HelpTour
            onClose={() => setShowTour(false)}
            onOpenEveryTime={tourOnOpen}
            onOpenEveryTimeChange={on => {
              setTourOnOpen(on);
              session.setShowTourOnOpen(on);
            }}
          />
        )}
        {isConfirmingClear && (
          <ConfirmDialog
            testID="canvas-clear-confirm"
            title="Clear the whole canvas?"
            body="An empty canvas takes its place. This one is kept under Canvases in this note."
            cancelLabel="Cancel"
            cancelAccessibilityLabel="Stay on this canvas"
            actionLabel="Clear canvas"
            actionAccessibilityLabel="Clear the canvas"
            onCancel={() => setConfirmingClear(false)}
            onAction={() => {
              setConfirmingClear(false);
              clearCanvas();
            }}
          />
        )}
        {canvasPicker !== null && (
          <CanvasList canvases={canvasPicker.canvases} onPick={pickedCanvas} onClose={() => setCanvasPicker(null)} />
        )}
        {editing !== null && (
          <TextEditor
            // A fresh editor, with its own text, for every edit.
            key={`${editing.elementId}:${editing.cellIndex}`}
            request={editing}
            onDone={text => {
              runCommand('setText', [text]);
              setEditing(null);
            }}
          />
        )}
        {notice !== null && (
          <View style={styles.notice} pointerEvents="none">
            <Text style={styles.noticeText}>{notice}</Text>
          </View>
        )}
      </View>
    </View>
  );
}

type HeaderButtonProps = {testID: string; label: string; icon: ImageSourcePropType; onPress: () => void; disabled?: boolean};

function HeaderButton({testID, label, icon, onPress, disabled = false}: HeaderButtonProps): React.JSX.Element {
  return (
    <Pressable testID={testID} accessibilityLabel={label} disabled={disabled} style={styles.headerButton} onPress={onPress}>
      <Image source={icon} style={[styles.headerIcon, disabled && styles.disabled]} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#cccccc',
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    color: '#000000',
  },
  headerStart: {
    flexDirection: 'row',
    alignItems: 'center',
    flexShrink: 1,
  },
  // The firmware's return badge: a black box with a white label, its arrow running past the box's right edge.
  backBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 16,
    flexShrink: 1,
  },
  backBox: {
    backgroundColor: '#000000',
    borderRadius: 4,
    paddingLeft: 14,
    paddingRight: 30,
    paddingVertical: 6,
    flexShrink: 1,
  },
  backLabel: {
    fontSize: 16,
    color: '#ffffff',
  },
  backArrow: {
    width: 34,
    height: 34,
    marginLeft: -24,
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  headerIcon: {
    width: 22,
    height: 22,
    tintColor: '#000000',
  },
  disabled: {
    opacity: 0.3,
  },
  canvasArea: {
    flex: 1,
    position: 'relative',
  },
  notice: {
    position: 'absolute',
    top: 12,
    alignSelf: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#000000',
  },
  noticeText: {
    fontSize: 15,
    color: '#ffffff',
  },
});
