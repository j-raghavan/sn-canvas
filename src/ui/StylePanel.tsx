// The style panel (FR19), top right as in tldraw: colour, opacity, fill, dash
// and size, behind a toggle that shows the colour in use, so the panel covers
// the canvas only while it is wanted. It shows the style the canvas reports
// (the selection's, or the one new elements get) and sends each change as a
// single property, which the canvas applies to the selection and to what is
// drawn next.

import React, {useState} from 'react';
import {
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
  type ImageSourcePropType,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import {
  COLORS,
  DASHES,
  FILLS,
  IMAGE_DASHES,
  OPACITY_STEPS,
  SIZES,
  colorName,
  opacityStepIndex,
  type CanvasStyle,
  type ColorId,
  type DashId,
  type FillId,
  type StyleProperty,
} from '../domain/styles';

const FILL_OPTIONS: Record<FillId, {label: string; icon: ImageSourcePropType}> = {
  none: {label: 'No fill', icon: require('../../assets/icons/fill-none.png')},
  semi: {label: 'Semi fill', icon: require('../../assets/icons/fill-semi.png')},
  solid: {label: 'Solid fill', icon: require('../../assets/icons/fill-solid.png')},
  pattern: {label: 'Pattern fill', icon: require('../../assets/icons/fill-pattern.png')},
};

const DASH_OPTIONS: Record<DashId, {label: string; icon: ImageSourcePropType}> = {
  draw: {label: 'Hand-drawn', icon: require('../../assets/icons/dash-draw.png')},
  dashed: {label: 'Dashed', icon: require('../../assets/icons/dash-dashed.png')},
  dotted: {label: 'Dotted', icon: require('../../assets/icons/dash-dotted.png')},
  solid: {label: 'Solid', icon: require('../../assets/icons/dash-solid.png')},
  none: {label: 'No outline', icon: require('../../assets/icons/dash-none.png')},
};

// An image (FR22) takes the outline only: its frame, which it can leave off, and no fill.
const IMAGE = 'image';

const CHEVRON_DOWN = require('../../assets/icons/chevron-down.png');
const CHEVRON_UP = require('../../assets/icons/chevron-up.png');

// The opacity slider's steps each take an equal share of the track; the knob sits at a step's center.
const STEP_SHARE = 100 / OPACITY_STEPS.length;
const stepCenter = (index: number) => `${STEP_SHARE * (index + 0.5)}%` as const;

type Props = {
  style: CanvasStyle;
  /** The selected element's type, if any: an image shows its own options. */
  selectedType?: string | null;
  /** The colour a swatch shows; the screen supplies the canvas's e-ink gray. */
  swatch: (color: ColorId) => string;
  onChange: (property: StyleProperty, value: string) => void;
  /** The panel is opening: the hints go, the way the ⋮ menu and the zoom control put them away. */
  onOpen: () => void;
};

export default function StylePanel({style, selectedType = null, swatch, onChange, onOpen}: Props): React.JSX.Element {
  const [isOpen, setOpen] = useState(false);

  const toggle = () => {
    // Outside the updater, which has to stay pure: React may run it more than once, and this reaches
    // into the screen. Closing is not an opening, so the hints are not put away twice.
    if (!isOpen) {
      onOpen();
    }
    setOpen(!isOpen);
  };
  const opacityIndex = opacityStepIndex(style.opacity);
  const isImage = selectedType === IMAGE;
  const dashes = isImage ? IMAGE_DASHES : DASHES;
  return (
    // box-none: only the toggle and the open panel take touches; the canvas gets the rest.
    <View style={styles.wrapper} pointerEvents="box-none">
      <Pressable
        testID="style-toggle"
        accessibilityLabel={isOpen ? 'Hide styles' : 'Show styles'}
        accessibilityState={{expanded: isOpen}}
        style={styles.toggle}
        onPress={toggle}>
        <View style={[styles.swatch, {backgroundColor: swatch(style.color)}]} />
        <Image source={isOpen ? CHEVRON_UP : CHEVRON_DOWN} style={styles.chevron} />
      </Pressable>
      {isOpen && (
        <View style={styles.panel}>
          <View style={styles.colors}>
            {COLORS.map(color => (
              <Option
                key={color.id}
                testID={`style-color-${color.id}`}
                label={color.name}
                active={color.id === style.color}
                activeStyle={styles.swatchActive}
                onPress={() => onChange('color', color.id)}>
                <View style={[styles.swatch, {backgroundColor: swatch(color.id)}]} />
              </Option>
            ))}
          </View>
          {/* Twelve grays alone don't identify a colour on e-ink, so the panel names it. */}
          <Text style={styles.caption}>{colorName(style.color)}</Text>
          <View style={styles.slider}>
            <View style={styles.sliderTrack} />
            <View style={[styles.sliderFill, {width: `${STEP_SHARE * opacityIndex}%`}]} />
            <View testID="style-opacity-knob" style={[styles.sliderKnob, {left: stepCenter(opacityIndex)}]} />
            <View style={styles.sliderSteps}>
              {OPACITY_STEPS.map(step => (
                <Pressable
                  key={step}
                  testID={`style-opacity-${step}`}
                  accessibilityLabel={`Opacity ${Math.round(step * 100)}%`}
                  style={styles.sliderStep}
                  onPress={() => onChange('opacity', String(step))}
                />
              ))}
            </View>
          </View>
          <View style={styles.divider} />
          {!isImage && (
            <View style={styles.row}>
              {FILLS.map(fill => (
                <Option
                  key={fill}
                  testID={`style-fill-${fill}`}
                  label={FILL_OPTIONS[fill].label}
                  active={fill === style.fill}
                  onPress={() => onChange('fill', fill)}>
                  <Image source={FILL_OPTIONS[fill].icon} style={styles.icon} />
                </Option>
              ))}
            </View>
          )}
          <View style={styles.row}>
            {dashes.map(dash => (
              <Option
                key={dash}
                testID={`style-dash-${dash}`}
                label={DASH_OPTIONS[dash].label}
                active={dash === style.dash}
                onPress={() => onChange('dash', dash)}>
                <Image source={DASH_OPTIONS[dash].icon} style={styles.icon} />
              </Option>
            ))}
          </View>
          <View style={styles.row}>
            {SIZES.map(size => (
              <Option
                key={size}
                testID={`style-size-${size}`}
                label={`Size ${size.toUpperCase()}`}
                active={size === style.size}
                onPress={() => onChange('size', size)}>
                <Text style={styles.sizeText}>{size.toUpperCase()}</Text>
              </Option>
            ))}
          </View>
        </View>
      )}
    </View>
  );
}

type OptionProps = {
  testID: string;
  label: string;
  active: boolean;
  /** How the option in use is marked; a light background and thin outline unless given. */
  activeStyle?: StyleProp<ViewStyle>;
  onPress: () => void;
  children: React.ReactNode;
};

function Option({testID, label, active, activeStyle, onPress, children}: OptionProps): React.JSX.Element {
  return (
    <Pressable
      testID={testID}
      accessibilityLabel={label}
      accessibilityState={{selected: active}}
      style={[styles.option, active && (activeStyle ?? styles.optionActive)]}
      onPress={onPress}>
      {children}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrapper: {
    position: 'absolute',
    top: 12,
    right: 12,
    alignItems: 'flex-end',
  },
  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 40,
    paddingHorizontal: 10,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#000000',
    backgroundColor: '#ffffff',
  },
  chevron: {
    width: 18,
    height: 18,
    marginLeft: 6,
    tintColor: '#000000',
  },
  panel: {
    marginTop: 6,
    width: 196,
    padding: 8,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#cccccc',
    backgroundColor: '#ffffff',
  },
  colors: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  row: {
    flexDirection: 'row',
  },
  option: {
    width: 44,
    height: 40,
    marginVertical: 2,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  // Subtle, as in tldraw, but with an outline so it still reads on e-ink.
  optionActive: {
    backgroundColor: '#e6e6e6',
    borderWidth: 1.5,
    borderColor: '#666666',
  },
  // A thin ring round the swatch, leaving its own gray (even black) in view.
  swatchActive: {
    borderWidth: 2,
    borderColor: '#444444',
    borderRadius: 20,
  },
  swatch: {
    width: 22,
    height: 22,
    borderRadius: 11,
  },
  caption: {
    fontSize: 12,
    color: '#000000',
    textAlign: 'center',
    marginVertical: 4,
  },
  slider: {
    height: 28,
    marginHorizontal: 4,
    marginBottom: 2,
    justifyContent: 'center',
  },
  sliderTrack: {
    position: 'absolute',
    left: stepCenter(0),
    right: stepCenter(0),
    height: 2,
    backgroundColor: '#bbbbbb',
  },
  sliderFill: {
    position: 'absolute',
    left: stepCenter(0),
    height: 2,
    backgroundColor: '#000000',
  },
  sliderKnob: {
    position: 'absolute',
    width: 18,
    height: 18,
    marginLeft: -9,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: '#000000',
    backgroundColor: '#ffffff',
  },
  sliderSteps: {
    ...StyleSheet.absoluteFillObject,
    flexDirection: 'row',
  },
  sliderStep: {
    flex: 1,
  },
  divider: {
    height: 1,
    backgroundColor: '#cccccc',
    marginVertical: 6,
  },
  icon: {
    width: 22,
    height: 22,
    tintColor: '#000000',
  },
  sizeText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#000000',
  },
});
