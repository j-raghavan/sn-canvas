// The canvases made in the open note (#30, #31): each shown by its thumbnail
// and when it was made, the one on screen marked, any other a tap away. A
// canvas stays within reach here whatever became of its thumbnail in the note.

import React from 'react';
import {Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import type {NoteCanvas} from '../application/canvasSession';

type Props = {
  canvases: readonly NoteCanvas[];
  onPick: (canvasId: string) => void;
  onClose: () => void;
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

export default function CanvasList({canvases, onPick, onClose}: Props): React.JSX.Element {
  return (
    <View testID="canvas-list" style={styles.overlay}>
      <View style={styles.card}>
        <View style={styles.header}>
          <Text style={styles.title}>Canvases in this note</Text>
          <Pressable testID="canvas-list-close" accessibilityLabel="Close the list" style={styles.close} onPress={onClose}>
            <Text style={styles.closeText}>Done</Text>
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.grid}>
          {canvases.map(canvas => (
            <Pressable
              key={canvas.canvasId}
              testID={`canvas-list-${canvas.canvasId}`}
              accessibilityLabel={`Canvas made ${madeLabel(canvas.madeAt)}${canvas.isShown ? ', shown' : ''}`}
              style={[styles.tile, canvas.isShown && styles.tileShown]}
              onPress={() => onPick(canvas.canvasId)}>
              {/* A canvas never saved to the note has no thumbnail file yet: the frame stays empty. */}
              <Image source={{uri: `file://${canvas.thumbnail}`}} style={styles.thumbnail} resizeMode="contain" />
              <Text style={styles.label} numberOfLines={1}>
                {madeLabel(canvas.madeAt)}
              </Text>
              {canvas.isShown && <Text style={styles.shown}>Shown now</Text>}
            </Pressable>
          ))}
        </ScrollView>
      </View>
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
  // E-ink shows weight, not colour: the canvas on screen gets the heavy frame.
  tileShown: {
    borderWidth: 3,
    borderColor: '#000000',
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
});
