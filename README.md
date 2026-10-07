<div align="center">

# react-native-nitro-modal

**Truly native bottom sheets and popups for React Native, powered by [Nitro Modules](https://nitro.margelo.com/).**

[![npm version](https://img.shields.io/npm/v/react-native-nitro-modal.svg?style=flat-square)](https://www.npmjs.com/package/react-native-nitro-modal)
[![npm downloads](https://img.shields.io/npm/dm/react-native-nitro-modal.svg?style=flat-square)](https://www.npmjs.com/package/react-native-nitro-modal)
[![license](https://img.shields.io/npm/l/react-native-nitro-modal.svg?style=flat-square)](LICENSE)
![platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android%20%7C%20Web-lightgrey.svg?style=flat-square)

</div>

---

`react-native-nitro-modal` renders your React content inside native modal presentations — an edge-attached UIKit sheet on iOS and Material `BottomSheetBehavior` on Android — so gestures, detent snapping, keyboard handling and transitions are handled natively, not re-implemented in JavaScript. On the web (React Native Web), the same API renders a DOM sheet and popup with matching behavior.

## Features

- 📄 **Native bottom sheets** with multiple detents (`small`, `medium`, `large`, `fitContent`) and swipe-to-dismiss
- 🪟 **Centered popups** with native `scale` / `fade` transitions
- 📏 **Content-sized sheets** — `fitContent` measures your React content and grows with it
- ⌨️ **Keyboard aware** — the sheet or card follows the keyboard animation (`pan` or `resize`)
- 🌫️ **Customizable backdrop** — color, opacity and blur
- 🎛️ **Controlled or imperative** — drive it with an `isOpen` prop or through a `ref`
- 🔔 **Rich events** — present, dismiss (with reason), detent change, backdrop press, back button
- ⚡ **Built on Nitro** — JSI-backed native views with no bridge overhead
- 🟦 **Fully typed** TypeScript API

## Requirements

| Dependency                     | Version                                      |
| ------------------------------ | -------------------------------------------- |
| React Native                   | New Architecture enabled                     |
| `react-native-nitro-modules`   | `^0.37.1`                                    |
| iOS                            | 15.0+ (16.0+ for `small` and `fitContent`)   |
| Android                        | API 24 (Android 7.0)+                        |
| Web                            | via `react-native-web` (tested with 0.21)    |

> [!NOTE]
> Other platforms (e.g. macOS, Windows) are not supported. There the component renders nothing and logs a one-time warning.

## Installation

```sh
# npm
npm install react-native-nitro-modal react-native-nitro-modules

# yarn
yarn add react-native-nitro-modal react-native-nitro-modules
```

Then install the iOS pods:

```sh
cd ios && pod install
```

**Expo:** the library contains native code, so it works in a [development build](https://docs.expo.dev/develop/development-builds/introduction/) (`npx expo prebuild`) but not in Expo Go.

## Quick start

```tsx
import { useState } from 'react';
import { Button, Text, View } from 'react-native';
import { NitroModal } from 'react-native-nitro-modal';

export function Example() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button title="Open sheet" onPress={() => setOpen(true)} />

      <NitroModal
        isOpen={open}
        detents={['fitContent']}
        showGrabber
        onDismiss={() => setOpen(false)}
      >
        <View style={{ padding: 24 }}>
          <Text>Hello from a native bottom sheet 👋</Text>
        </View>
      </NitroModal>
    </>
  );
}
```

> [!IMPORTANT]
> In controlled mode, the user can close the modal natively (swipe, backdrop tap, back button). Always set your state back to `false` in `onDismiss`, otherwise `isOpen` will be out of sync.

## Usage

### Bottom sheet with detents

Detents are listed smallest first. `initialDetentIndex` selects where the sheet opens.

```tsx
<NitroModal
  isOpen={open}
  detents={['medium', 'large']}
  initialDetentIndex={0}
  showGrabber
  backdropOpacity={0.25}
  backdropBlur={12}
  onDetentChange={(index) => console.log('detent', index)}
  onDismiss={() => setOpen(false)}
>
  <FlatList data={rows} renderItem={renderRow} nestedScrollEnabled />
</NitroModal>
```

### Popup

```tsx
<NitroModal
  isOpen={confirmOpen}
  mode="popup"
  popupAnimation="scale"
  dismissOnBackdropPress={false}
  onDismiss={() => setConfirmOpen(false)}
>
  <View style={{ width: 300, padding: 24 }}>
    <Text>Delete item?</Text>
    <Button title="Cancel" onPress={() => setConfirmOpen(false)} />
  </View>
</NitroModal>
```

### Imperative (uncontrolled) usage

Omit `isOpen` and control the modal through a ref.

```tsx
import { useRef } from 'react';
import { NitroModal, type NitroModalRef } from 'react-native-nitro-modal';

const sheet = useRef<NitroModalRef>(null);

<Button title="Open" onPress={() => sheet.current?.present()} />

<NitroModal ref={sheet} detents={['small', 'medium']}>
  <Button title="Expand" onPress={() => sheet.current?.snapToDetent(1)} />
  <Button title="Close" onPress={() => sheet.current?.dismiss()} />
</NitroModal>
```

### Keyboard handling

```tsx
<NitroModal isOpen={open} keyboardBehavior="resize" onDismiss={close}>
  <TextInput placeholder="Type something…" />
</NitroModal>
```

| Value      | Behavior                                                                                         |
| ---------- | ------------------------------------------------------------------------------------------------ |
| `'pan'`    | The sheet/card moves above the keyboard in sync with its animation. Content keeps its size.      |
| `'resize'` | Like `pan`, and the area given to the content shrinks so scrollable content fits above the keyboard. |
| `'none'`   | The keyboard is ignored.                                                                         |

## API

### `<NitroModal />` props

| Prop                     | Type                                                 | Default            | Description                                                                                       |
| ------------------------ | ---------------------------------------------------- | ------------------ | ------------------------------------------------------------------------------------------------- |
| `isOpen`                 | `boolean`                                            | —                  | Controls visibility. Leave undefined to use the [ref API](#nitromodalref) instead.                |
| `mode`                   | `'bottomSheet' \| 'popup'`                           | `'bottomSheet'`    | How the modal is presented.                                                                       |
| `detents`                | `SheetDetent[]`                                      | `['fitContent']`   | Sheet heights, smallest first. Android uses at most three.                                        |
| `initialDetentIndex`     | `number`                                             | `0`                | Index into `detents` the sheet opens at.                                                          |
| `backdropColor`          | `ColorValue`                                         | `'black'`          | Backdrop color.                                                                                   |
| `backdropOpacity`        | `number`                                             | `0.4`              | Backdrop opacity, `0`–`1`.                                                                        |
| `backdropBlur`           | `number`                                             | `0`                | Blur radius (dp/pt/px) behind the modal. `0` disables it. See [platform notes](#platform-notes).  |
| `dismissOnBackdropPress` | `boolean`                                            | `true`             | Tapping the backdrop closes the modal.                                                            |
| `dismissOnSwipe`         | `boolean`                                            | `true`             | Swiping down closes a bottom sheet.                                                               |
| `dismissOnBackButton`    | `boolean`                                            | `true`             | The Android back button/gesture (Escape on web) closes the modal.                                 |
| `showGrabber`            | `boolean`                                            | `false`            | Shows the drag handle on a bottom sheet.                                                          |
| `cornerRadius`           | `number`                                             | platform default   | Corner radius of the sheet/card.                                                                  |
| `backgroundColor`        | `ColorValue`                                         | system surface     | Background of the sheet/card.                                                                     |
| `keyboardBehavior`       | `'pan' \| 'resize' \| 'none'`                        | `'pan'`            | How the modal reacts to the software keyboard.                                                    |
| `popupAnimation`         | `'scale' \| 'fade' \| 'none'`                        | `'scale'`          | Enter/exit transition of a popup.                                                                 |
| `contentContainerStyle`  | `StyleProp<ViewStyle>`                               | —                  | Style of the view that wraps `children` inside the modal.                                         |
| `testID`                 | `string`                                             | —                  | Applied to the content container.                                                                 |
| `ref`                    | `Ref<NitroModalRef>`                                 | —                  | Imperative handle.                                                                                |

### Events

| Prop                | Signature                                | Description                                                                        |
| ------------------- | ---------------------------------------- | ---------------------------------------------------------------------------------- |
| `onPresent`         | `() => void`                             | The present transition finished.                                                   |
| `onDismiss`         | `(reason: DismissReason) => void`        | The modal is fully gone. Fires exactly once per presentation.                      |
| `onDetentChange`    | `(index: number) => void`                | A bottom sheet settled on a different detent.                                      |
| `onBackdropPress`   | `() => void`                             | The backdrop was tapped (fires even when `dismissOnBackdropPress` is `false`).     |
| `onBackButtonPress` | `() => void`                             | The Android hardware/gesture back (Escape on web) was pressed.                     |

### `NitroModalRef`

| Method                        | Description                                                                         |
| ----------------------------- | ----------------------------------------------------------------------------------- |
| `present()`                   | Opens the modal. Uncontrolled usage only; ignored with a warning when `isOpen` is set. |
| `dismiss()`                   | Closes the modal. `onDismiss` then fires with `'programmatic'`.                     |
| `snapToDetent(index: number)` | Animates a bottom sheet to `detents[index]`.                                        |

### Types

```ts
type ModalMode = 'bottomSheet' | 'popup';

type SheetDetent =
  | 'small'      // ~25% of the available height
  | 'medium'     // ~50% of the available height
  | 'large'      // the full available height
  | 'fitContent'; // the measured height of your content

type KeyboardBehavior = 'pan' | 'resize' | 'none';

type PopupAnimation = 'scale' | 'fade' | 'none';

type DismissReason = 'programmatic' | 'swipe' | 'backdrop' | 'backButton';
```

All types are exported from the package root:

```ts
import type {
  DismissReason,
  KeyboardBehavior,
  ModalMode,
  NitroModalProps,
  NitroModalRef,
  PopupAnimation,
  SheetDetent,
} from 'react-native-nitro-modal';
```

## How it works

`<NitroModal>` mounts a zero-size placeholder in your React tree. When opened, the native side presents a real view controller (iOS) or window (Android) and hosts your React children inside it. Children mount when the modal opens and stay mounted until the native exit animation completes, so content never disappears mid-transition. The native side also reports the exact area available to the content (accounting for rotation, keyboard and sheet size), and the content container is sized accordingly.

## Platform notes

**iOS**

- Bottom sheets are a custom edge-attached presentation (native pan gesture, spring snapping and scroll-view hand-off) instead of `UISheetPresentationController`, which on iOS 26+ always floats partial-height sheets inset from the screen edges. All detents work on every supported iOS version.
- UIKit has no public blur-radius API, so `backdropBlur` is approximated by blending a thin system material.

**Android**

- Bottom sheets use Material Components' `BottomSheetBehavior`, which supports at most three detents.
- `backdropBlur` requires Android 12 (API 31)+; it is ignored on older versions.
- `onBackButtonPress` and `dismissOnBackButton` apply to both the hardware back button and the system back gesture.
- Predictive back (Android 14+, when the app opts in or targets SDK 36) previews the exit while the gesture runs: the sheet uses Material's bottom-sheet animation and the popup scales down.
- Transitions are interruptible: closing during the enter animation turns it around, and reopening during the exit animation brings the same modal back (no extra `onDismiss`/`onPresent`). Once the exit starts, taps and back presses reach the screen below, as with a native dialog.

**Web**

- Renders through React Native Web's `Modal`, so it stacks with your other modals, traps focus while open and returns it on close. Nothing is rendered in place.
- Bottom sheets can be dragged with touch or mouse, with the same detent snapping, fling-to-dismiss and hand-off to scrollable content as on iOS. A touch that starts on content already scrolled down scrolls it natively instead of moving the sheet.
- The Escape key acts as the back button: it fires `onBackButtonPress` and, unless `dismissOnBackButton` is `false`, dismisses the topmost modal with reason `'backButton'`. The browser's history back is not intercepted.
- `backdropBlur` uses CSS `backdrop-filter`. Without `backgroundColor`, the sheet/card uses the CSS `Canvas` system color, which follows your page's `color-scheme`.
- `keyboardBehavior` follows the on-screen keyboard through the `visualViewport` API (mobile browsers). Safe-area insets are respected when the page uses `viewport-fit=cover`.
- Sheets are at most 640px wide and centered. Animations are skipped when the user prefers reduced motion.

**Colors**

- `backdropColor` and `backgroundColor` accept any color string or number supported by `processColor`. `PlatformColor` and `DynamicColorIOS` values are not supported yet.

## Example app

The repository includes an example app that covers content-sized sheets, multi-detent sheets with lists, popups and ref-driven usage.

```sh
yarn
yarn example ios      # or: yarn example android / yarn example web
```

## Contributing

Contributions are welcome! See the [contributing guide](CONTRIBUTING.md) to learn how to set up the development workflow and send a pull request, and please follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE) © [Mohammad Navabi](https://github.com/mohamadnavabi)

---

Built with [Nitro Modules](https://nitro.margelo.com/) and [create-react-native-library](https://github.com/callstack/react-native-builder-bob).
