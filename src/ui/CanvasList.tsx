// The canvases made in the open note (#30, #31): each shown by its thumbnail
// and when it was made, the one on screen marked, any other a tap away. A
// canvas stays within reach here whatever became of its thumbnail in the note.
//
// Select deletes one or more (#74): the canvas on screen is never offered,
// since there is nothing to put in its place once it is gone. Only offered
// at all when [onDeleteSelected] is given, which the link picker (#2) does
// not: linking to a canvas is not the place to be deleting one.

import React, {useState} from 'react';
import {Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import type {NoteCanvas} from '../application/canvasSession';
import ConfirmDialog from './ConfirmDialog';

type Props = {
  canvases: readonly NoteCanvas[];
  onPick: (canvasId: string) => void;
  onClose: () => void;
  onDeleteSelected?: (canvasIds: readonly string[]) => void;
};

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** When a canvas was made, as the list names it: "19 Sep 2026, 13:01", on the device's clock. */
export function madeLabel(madeAt: number | null): string {
  if (madeAt === null) {
    return 'Not saved to the note yet';
  }
  const made = new Date(madeAt);
  const time = [made.getHours(), made.getMinutes()].map(part => String(part).padStart(2, '0')).join(':');
  return `${made.getDate()} ${MONTHS[made.getMonth()]} ${made.getFullYear()}, ${time}`;
}

export default function CanvasList({canvases, onPick, onClose, onDeleteSelected}: Props): React.JSX.Element {
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [confirming, setConfirming] = useState(false);

  // The one shown has nothing to put in its place once it is gone, so it is never offered.
  const selectable = canvases.filter(canvas => !canvas.isShown);
  const allSelected = selectable.length > 0 && selectable.every(canvas => selected.has(canvas.canvasId));

  const stopSelecting = () => {
    setSelecting(false);
    setSelected(new Set());
  };

  const toggle = (canvasId: string) =>
    setSelected(current => {
      const next = new Set(current);
      if (next.has(canvasId)) {
        next.delete(canvasId);
      } else {
        next.add(canvasId);
      }
      return next;
    });

  const toggleSelectAll = () => setSelected(allSelected ? new Set() : new Set(selectable.map(canvas => canvas.canvasId)));

  const tap = (canvasId: string, isShown: boolean) => {
    if (!selecting) {
      onPick(canvasId);
    } else if (!isShown) {
      toggle(canvasId);
    }
  };

  const confirmDelete = () => {
    setConfirming(false);
    onDeleteSelected?.([...selected]);
    stopSelecting();
  };

  return (
    <View testID="canvas-list" style={styles.overlay}>
      <View style={styles.card}>
        <View style={styles.header}>
          <Text style={styles.title}>Canvases in this note</Text>
          {selecting ? (
            <Pressable
              testID="canvas-list-cancel-select"
              accessibilityLabel="Stop selecting canvases"
              style={styles.close}
              onPress={stopSelecting}>
              <Text style={styles.closeText}>Cancel</Text>
            </Pressable>
          ) : (
            <View style={styles.headerButtons}>
              {onDeleteSelected !== undefined && selectable.length > 0 && (
                <Pressable
                  testID="canvas-list-select"
                  accessibilityLabel="Select canvases to delete"
                  style={[styles.close, styles.selectButton]}
                  onPress={() => setSelecting(true)}>
                  <Text style={styles.closeText}>Select</Text>
                </Pressable>
              )}
              <Pressable testID="canvas-list-close" accessibilityLabel="Close the list" style={styles.close} onPress={onClose}>
                <Text style={styles.closeText}>Done</Text>
              </Pressable>
            </View>
          )}
        </View>
        {selecting && (
          <View style={styles.selectionBar}>
            <Text style={styles.selectionCount}>{selected.size} selected</Text>
            <View style={styles.headerButtons}>
              <Pressable
                testID="canvas-list-select-all"
                accessibilityLabel={allSelected ? 'Select none' : 'Select all'}
                style={[styles.close, styles.selectButton]}
                onPress={toggleSelectAll}>
                <Text style={styles.closeText}>{allSelected ? 'Select none' : 'Select all'}</Text>
              </Pressable>
              <Pressable
                testID="canvas-list-delete"
                accessibilityLabel="Delete the selected canvases"
                disabled={selected.size === 0}
                style={[styles.close, styles.deleteButton, selected.size === 0 && styles.deleteButtonDisabled]}
                onPress={() => setConfirming(true)}>
                <Text style={[styles.closeText, styles.deleteButtonText]}>Delete</Text>
              </Pressable>
            </View>
          </View>
        )}
        <ScrollView contentContainerStyle={styles.grid}>
          {canvases.map(canvas => (
            <Pressable
              key={canvas.canvasId}
              testID={`canvas-list-${canvas.canvasId}`}
              accessibilityLabel={`Canvas made ${madeLabel(canvas.madeAt)}${canvas.isShown ? ', shown' : ''}${
                selecting && selected.has(canvas.canvasId) ? ', selected' : ''
              }`}
              accessibilityState={{selected: selecting && selected.has(canvas.canvasId)}}
              disabled={selecting && canvas.isShown}
              style={[
                styles.tile,
                canvas.isShown && styles.tileShown,
                selecting && selected.has(canvas.canvasId) && styles.tileSelected,
                selecting && canvas.isShown && styles.tileNotSelectable,
              ]}
              onPress={() => tap(canvas.canvasId, canvas.isShown)}>
              {/* A canvas never saved to the note has no thumbnail file yet: the frame stays empty. */}
              <Image source={{uri: `file://${canvas.thumbnail}`}} style={styles.thumbnail} resizeMode="contain" />
              <Text style={styles.label} numberOfLines={1}>
                {madeLabel(canvas.madeAt)}
              </Text>
              {canvas.isShown && <Text style={styles.shown}>Shown now</Text>}
              {selecting && !canvas.isShown && (
                <Text style={styles.checkbox}>{selected.has(canvas.canvasId) ? '☑' : '☐'}</Text>
              )}
            </Pressable>
          ))}
        </ScrollView>
      </View>
      {confirming && (
        <ConfirmDialog
          testID="canvas-list-delete-confirm"
          title={selected.size === 1 ? 'Delete this canvas?' : `Delete ${selected.size} canvases?`}
          body="They come out of this note's list for good, along with anything drawn on them."
          cancelLabel="Cancel"
          cancelAccessibilityLabel="Keep the selected canvases"
          actionLabel="Delete"
          actionAccessibilityLabel="Delete the selected canvases"
          onCancel={() => setConfirming(false)}
          onAction={confirmDelete}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.15)',
  },
  card: {
    width: '86%',
    maxHeight: '80%',
    paddingHorizontal: 20,
    paddingVertical: 18,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#000000',
    backgroundColor: '#ffffff',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  title: {
    fontSize: 17,
    fontWeight: '600',
    color: '#000000',
  },
  headerButtons: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  close: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#000000',
  },
  closeText: {
    fontSize: 15,
    color: '#000000',
  },
  selectButton: {
    marginRight: 8,
  },
  selectionBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  selectionCount: {
    fontSize: 14,
    color: '#000000',
  },
  deleteButton: {
    backgroundColor: '#000000',
  },
  deleteButtonDisabled: {
    opacity: 0.3,
  },
  deleteButtonText: {
    color: '#ffffff',
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  tile: {
    width: 180,
    margin: 6,
    padding: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#999999',
  },
  // E-ink shows weight, not colour: the canvas on screen gets the heavy frame, a selected one the dashed one.
  tileShown: {
    borderWidth: 3,
    borderColor: '#000000',
  },
  tileSelected: {
    borderWidth: 3,
    borderStyle: 'dashed',
    borderColor: '#000000',
  },
  tileNotSelectable: {
    opacity: 0.5,
  },
  thumbnail: {
    width: '100%',
    height: 140,
    backgroundColor: '#ffffff',
  },
  label: {
    marginTop: 6,
    fontSize: 13,
    color: '#000000',
  },
  shown: {
    fontSize: 12,
    fontWeight: '600',
    color: '#000000',
  },
  checkbox: {
    marginTop: 4,
    fontSize: 16,
    color: '#000000',
  },
});
