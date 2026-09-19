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
import {parseElementLink} from '../domain/canvasLink';
import {noteNameOf, type TrailStep} from '../domain/linkTrail';
import {parseTextEditRequest, type TextEditRequest} from '../domain/textEdit';
import ActionBar from './ActionBar';
import CanvasList from './CanvasList';
import ConfirmDialog from './ConfirmDialog';
import HelpHints from './HelpHints';
import StylePanel from './StylePanel';
import TextEditor from './TextEditor';
import Toolbar from './Toolbar';
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
  // Clearing takes everything at once, so both ways in (the eraser's options, the ⋮ menu) ask here first.
  const [isConfirmingClear, setConfirmingClear] = useState(false);
  const canvasRef = useRef<CanvasViewRef>(null);
  // #30: the canvases made in this note, while the list of them is open.
  const [noteCanvases, setNoteCanvases] = useState<readonly NoteCanvas[] | null>(null);
  // #34: one step back along the links followed to this canvas, offered as the firmware offers its own.
  const [back, setBack] = useState<TrailStep | null>(null);
  const goBack = useCallback(() => session.goBack().then(() => setBack(session.backTo())), [session]);

  // The plugin runtime stays warm between opens, so this screen can stay mounted across them: every press
  // re-resolves which canvas to show.
  useEffect(() => {
    const openCanvas = (buttonId: number | null) => session.open(buttonId).then(() => setBack(session.backTo()));
    openCanvas(buttonEvents.lastButtonId());
    return buttonEvents.onButton(openCanvas);
  }, [session, buttonEvents]);

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
    if (link !== null && !(await session.followLink(link))) {
      setNotice('Could not open that note');
    }
  };

  const showNoteCanvases = async () => setNoteCanvases(await session.canvasesHere());

  const switchCanvas = async (canvasId: string) => {
    setNoteCanvases(null);
    await session.switchTo(canvasId);
    setBack(session.backTo());
  };

  const newCanvas = async () => {
    await session.newCanvas();
    setBack(session.backTo());
  };

  // FR12: confirm what happened, so the thumbnail isn't added twice for want of feedback, and a
  // refresh of the one already on the page doesn't look like nothing happened.
  const saveToNote = async () => {
    const saved = await session.saveToNote();
    if (saved !== null) {
      setNotice(saved === 'refreshed' ? 'Thumbnail updated' : ADDED_TO_NOTE);
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View style={styles.headerStart}>
          {back !== null && (
            <Pressable
              testID="canvas-back"
              accessibilityLabel={`Back to ${noteNameOf(back.notePath)}`}
              style={styles.backBadge}
              onPress={goBack}>
              <View style={styles.backBox}>
                <Text style={styles.backLabel} numberOfLines={1}>
                  {noteNameOf(back.notePath)}
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
          onNoteCanvases={showNoteCanvases}
          onMenuOpen={() => setShowHints(false)}
        />
        {/* Over the action bar, which is always there and sits across the middle tools: the eraser's hint has to
            cross it to reach the eraser, and only its arrow does. Still under the style panel and the toolbar,
            and the ⋮ menu dismisses the hints as it opens, so nothing a tap opens is ever drawn over. */}
        {showHints && <HelpHints />}
        <StylePanel
          style={ui.style}
          selectedType={ui.selectedType}
          swatch={color => swatchColor(color, einkGrays)}
          onChange={(property, value) => runCommand('setStyle', [property, value])}
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
        {isConfirmingClear && (
          <ConfirmDialog
            testID="canvas-clear-confirm"
            title="Clear the whole canvas?"
            body="Everything on it goes. Undo brings it back."
            cancelLabel="Cancel"
            cancelAccessibilityLabel="Keep the canvas"
            actionLabel="Clear canvas"
            actionAccessibilityLabel="Clear the canvas"
            onCancel={() => setConfirmingClear(false)}
            onAction={() => {
              setConfirmingClear(false);
              runCommand('clearCanvas');
            }}
          />
        )}
        {noteCanvases !== null && (
          <CanvasList canvases={noteCanvases} onPick={switchCanvas} onClose={() => setNoteCanvases(null)} />
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
