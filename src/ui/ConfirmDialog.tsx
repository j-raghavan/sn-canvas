// A question over the canvas and its controls, answered before anything else
// is tappable, as Clear canvas asks one.

import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';

type Props = {
  /** Prefixes every testID: `<testID>`, `<testID>-cancel`, `<testID>-action`. */
  testID: string;
  title: string;
  body: string;
  cancelLabel: string;
  /** Says what cancelling keeps, for screen readers. */
  cancelAccessibilityLabel: string;
  actionLabel: string;
  actionAccessibilityLabel: string;
  onCancel: () => void;
  onAction: () => void;
};

export default function ConfirmDialog({
  testID,
  title,
  body,
  cancelLabel,
  cancelAccessibilityLabel,
  actionLabel,
  actionAccessibilityLabel,
  onCancel,
  onAction,
}: Props): React.JSX.Element {
  return (
    <View testID={testID} style={styles.overlay}>
      <View style={styles.card}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.body}>{body}</Text>
        <View style={styles.actions}>
          <Pressable
            testID={`${testID}-cancel`}
            accessibilityLabel={cancelAccessibilityLabel}
            style={styles.button}
            onPress={onCancel}>
            <Text style={styles.buttonText}>{cancelLabel}</Text>
          </Pressable>
          <Pressable
            testID={`${testID}-action`}
            accessibilityLabel={actionAccessibilityLabel}
            style={[styles.button, styles.buttonPrimary]}
            onPress={onAction}>
            <Text style={[styles.buttonText, styles.buttonTextPrimary]}>{actionLabel}</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  // Over the canvas and its controls: nothing else is tappable while the question stands.
  overlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.15)',
  },
  card: {
    width: 320,
    paddingHorizontal: 20,
    paddingVertical: 18,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#000000',
    backgroundColor: '#ffffff',
  },
  title: {
    fontSize: 17,
    fontWeight: '600',
    color: '#000000',
  },
  body: {
    marginTop: 6,
    fontSize: 14,
    color: '#444444',
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: 18,
  },
  button: {
    marginLeft: 8,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#000000',
  },
  buttonPrimary: {
    backgroundColor: '#000000',
  },
  buttonText: {
    fontSize: 15,
    color: '#000000',
  },
  buttonTextPrimary: {
    color: '#ffffff',
  },
});
