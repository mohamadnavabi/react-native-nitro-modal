import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';

/**
 * How the modal is presented.
 * - `bottomSheet`: native sheet (iOS `UISheetPresentationController`,
 *   Android `BottomSheetBehavior`) with detents and swipe-to-dismiss.
 * - `popup`: centered dialog with a native fade/scale transition.
 */
export type ModalMode = 'bottomSheet' | 'popup';

/**
 * Resting heights of a `bottomSheet`.
 * - `small`: ~25% of the available height.
 * - `medium`: ~50% of the available height.
 * - `large`: the full available height.
 * - `fitContent`: the measured height of the React content.
 */
export type SheetDetent = 'small' | 'medium' | 'large' | 'fitContent';

/**
 * How the modal reacts to the software keyboard.
 * - `pan`: the sheet/card is translated above the keyboard, in sync with the
 *   keyboard animation. The content keeps its size.
 * - `resize`: like `pan`, and the area reported to the content shrinks so
 *   scrollable content can fit above the keyboard.
 * - `none`: the keyboard is ignored.
 */
export type KeyboardBehavior = 'pan' | 'resize' | 'none';

/** Enter/exit transition of a `popup`. */
export type PopupAnimation = 'scale' | 'fade' | 'none';

/** What caused the modal to close. */
export type DismissReason =
  'programmatic' | 'swipe' | 'backdrop' | 'backButton';

/** Size (dp/pt) available to the React content inside the presented modal. */
export interface ModalContentArea {
  width: number;
  height: number;
}

export interface NitroModalProps extends HybridViewProps {
  /** Whether the modal should be presented. */
  isOpen: boolean;
  mode: ModalMode;
  /** Detents of a `bottomSheet`, smallest first. At most 3 are used on Android. */
  detents: SheetDetent[];
  /** Index into `detents` that the sheet opens at. */
  initialDetentIndex: number;
  /** Backdrop color as a processed ARGB color (see `processColor`). */
  backdropColor: number;
  /** Opacity of the backdrop color, 0-1. */
  backdropOpacity: number;
  /** Blur radius (dp/pt) applied behind the modal. 0 disables the blur. */
  backdropBlurRadius: number;
  dismissOnBackdropPress: boolean;
  dismissOnSwipe: boolean;
  dismissOnBackButton: boolean;
  grabberVisible: boolean;
  /** Corner radius of the sheet/card. A negative value uses the platform default. */
  cornerRadius: number;
  /** Sheet/card background as a processed ARGB color. Omit for the system background. */
  contentBackgroundColor?: number;
  keyboardBehavior: KeyboardBehavior;
  popupAnimation: PopupAnimation;

  /** The present transition finished. */
  onPresent?: () => void;
  /** The modal is fully gone. Fired exactly once for every presentation request. */
  onDismiss?: (reason: DismissReason) => void;
  /** A `bottomSheet` settled on a different detent. */
  onDetentChange?: (index: number) => void;
  onBackdropPress?: () => void;
  /** Android hardware/gesture back. */
  onBackButtonPress?: () => void;
  /** The area available to the content changed (rotation, keyboard, sheet sizing). */
  onContentAreaChange?: (area: ModalContentArea) => void;
}

export interface NitroModalMethods extends HybridViewMethods {
  present(): void;
  dismiss(): void;
  /** Animates a `bottomSheet` to `detents[index]`. */
  snapToDetent(index: number): void;
}

export type NitroModal = HybridView<NitroModalProps, NitroModalMethods>;
