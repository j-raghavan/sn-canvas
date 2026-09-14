// The keyboard text editor (FR6/FR24): a TextInput laid exactly over the text
// being edited, opened when the canvas starts an edit. Done, or the keyboard
// going away, hands the text back once; the canvas hides its own copy of that
// text while the editor is open.

import React, {useRef, useState} from 'react';
import {Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import type {TextEditRequest} from '../domain/textEdit';

type Props = {
  request: TextEditRequest;
  onDone: (text: string) => void;
};

// Narrow table cells still get room to type in.
const MIN_WIDTH = 140;

export default function TextEditor({request, onDone}: Props): React.JSX.Element {
  const [text, setText] = useState(request.text);
  const isFinished = useRef(false);
  const finish = () => {
    if (isFinished.current) {
      return;
    }
    isFinished.current = true;
    onDone(text);
  };
  return (
    <View style={[styles.frame, {left: request.left, top: request.top, width: Math.max(request.width, MIN_WIDTH)}]}>
      <TextInput
        testID="text-editor-input"
        style={[
          styles.input,
          // The canvas insets text by a third of its size; match it so the text doesn't jump.
          {fontSize: request.fontSize, minHeight: request.height, padding: request.fontSize / 3},
          request.isNote && styles.note,
        ]}
        value={text}
        onChangeText={setText}
        onBlur={finish}
        multiline
        autoFocus
      />
      <Pressable testID="text-editor-done" accessibilityLabel="Done" style={styles.done} onPress={finish}>
        <Text style={styles.doneText}>Done</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    position: 'absolute',
    alignItems: 'flex-end',
  },
  input: {
    alignSelf: 'stretch',
    color: '#000000',
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#666666',
    textAlignVertical: 'top',
  },
  note: {
    backgroundColor: '#eeeeee',
  },
  done: {
    marginTop: 4,
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#000000',
    backgroundColor: '#ffffff',
  },
  doneText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#000000',
  },
});
