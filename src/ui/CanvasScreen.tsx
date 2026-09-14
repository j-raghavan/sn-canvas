// The plugin's one screen (PRD §3 step 3, §9): the native canvas full-screen,
// under a header (Save to Note, Close), with the style panel top right, the
// action bar above the floating toolbar (FR16-FR19), and the keyboard text
// editor over whatever text the canvas is editing (FR6/FR24). Thin by design: the canvas
// session and the button presses are injected (wiring.ts, via App.tsx), and
// the action bar and style panel follow the state the canvas reports.

import React, {useEffect, useRef, useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType} from 'react-native';
import type {CanvasSession} from '../application/canvasSession';
import {INITIAL_UI_STATE, parseUiState, swatchColor, type CanvasUiState} from '../domain/styles';
import {parseTextEditRequest, type TextEditRequest} from '../domain/textEdit';
import ActionBar from './ActionBar';
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
};

// FR23: Save to Note has its own icon; the upward arrow is kept for Export to PDF.
const SAVE_TO_NOTE_ICON = require('../../assets/icons/action-save-to-note.png');
const CLOSE_ICON = require('../../assets/icons/action-close.png');

/** How long the "Added to note" confirmation stays up. */
export const NOTICE_MS = 2500;

export default function CanvasScreen({createSession, buttonEvents}: Props): React.JSX.Element {
  const [session] = useState(createSession);
  const [einkGrays] = useState(nativeEinkGrays);
  const [toolMode, setToolMode] = useState<ToolMode>('select');
  const [ui, setUi] = useState<CanvasUiState>(INITIAL_UI_STATE);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<TextEditRequest | null>(null);
  const canvasRef = useRef<CanvasViewRef>(null);

  // The plugin runtime stays warm between opens, so this screen can stay
  // mounted across them: every press re-resolves which canvas to show.
  useEffect(() => {
    session.open(buttonEvents.lastButtonId());
    return buttonEvents.onButton(buttonId => {
      session.open(buttonId);
    });
  }, [session, buttonEvents]);

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
    if (await session.insertImage()) {
      setToolMode('select');
    }
  };

  // FR12: confirm the insert, so the thumbnail isn't added twice for want of feedback.
  const saveToNote = async () => {
    if (await session.saveToNote()) {
      setNotice('Added to note');
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Canvas</Text>
        <View style={styles.headerActions}>
          <HeaderButton testID="canvas-save-to-note" label="Save to Note" icon={SAVE_TO_NOTE_ICON} onPress={saveToNote} />
          <HeaderButton testID="canvas-close" label="Close" icon={CLOSE_ICON} onPress={session.close} />
        </View>
      </View>
      <View style={styles.canvasArea}>
        <CanvasNativeView
          ref={canvasRef}
          style={StyleSheet.absoluteFill}
          toolMode={toolMode}
          onCanvasState={event => setUi(parseUiState(event.nativeEvent))}
          onEditText={event => setEditing(parseTextEditRequest(event.nativeEvent))}
        />
        <StylePanel
          style={ui.style}
          selectedType={ui.selectedType}
          swatch={color => swatchColor(color, einkGrays)}
          onChange={(property, value) => runCommand('setStyle', [property, value])}
        />
        <ActionBar ui={ui} onCommand={runCommand} onNewCanvas={session.newCanvas} />
        <Toolbar toolMode={toolMode} onToolChange={setToolMode} onInsertImage={insertImage} />
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

type HeaderButtonProps = {testID: string; label: string; icon: ImageSourcePropType; onPress: () => void};

function HeaderButton({testID, label, icon, onPress}: HeaderButtonProps): React.JSX.Element {
  return (
    <Pressable testID={testID} accessibilityLabel={label} style={styles.headerButton} onPress={onPress}>
      <Image source={icon} style={styles.headerIcon} />
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
