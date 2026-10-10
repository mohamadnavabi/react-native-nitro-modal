import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';

/**
 * How the modal is presented.
 * - `bottomSheet`: edge-attached native sheet (iOS custom presentation,
 *   Android `BottomSheetBehavior`) with detents and swipe-to-dismiss.
 * - `popup`: centered dialog with a native fade/scale transition.
 */
export type ModalMode = 'bottomSheet' | 'popup';

/**
 * Named resting heights of a `bottomSheet`.
 * - `small`: ~25% of the available height.
 * - `medium`: ~50% of the available height.
 * - `large`: the full available height.
 * - `fitContent`: the measured height of the React content.
 */
export type NamedSheetDetent = 'small' | 'medium' | 'large' | 'fitContent';

/**
 * A resting height of a `bottomSheet`: a named height, or the height
 * (dp/pt) of the content above the bottom safe area, clamped to the
 * available height.
 */
export type SheetDetent = NamedSheetDetent | number;

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
  /**
   * A `bottomSheet` that lives inside the host view's own bounds instead of
   * being presented over the app: no backdrop, touches outside the sheet
   * reach the views behind it, and the user can't dismiss it.
   */
  isInline: boolean;
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
  /** Width (dp/pt) of the border drawn around the sheet/card, above the content. 0 draws none. */
  borderWidth: number;
  /** Border color as a processed ARGB color. */
  borderColor?: number;
  /** Sheet/card background as a processed ARGB color. Omit for the system background. */
  contentBackgroundColor?: number;
  keyboardBehavior: KeyboardBehavior;
  popupAnimation: PopupAnimation;
  /**
   * Pulling a `bottomSheet` down past its lowest detent calls
   * `onPullToRefresh`. Needs `dismissOnSwipe` off (always the case inline).
   */
  pullToRefreshEnabled: boolean;
  /**
   * A refresh is in progress: the sheet rests pulled down below its lowest
   * detent with an activity indicator above it. Native starts refreshing on
   * its own when a pull is released. `undefined` shows no indicator.
   */
  refreshing?: boolean;
  /** Refresh indicator color as a processed ARGB color. Omit for the platform default. */
  refreshIndicatorColor?: number;

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
  /**
   * The user pulled the sheet down past its lowest detent and let go. The
   * gesture must start with the sheet resting on that detent.
   */
  onPullToRefresh?: () => void;
  /**
   * Inline sheets: where the sheet's top rests within the host view (or
   * will, once it settles). Lets React lay the content out where it is shown.
   */
  onRestingTopChange?: (top: number) => void;
}

export interface NitroModalMethods extends HybridViewMethods {
  present(): void;
  dismiss(): void;
  /** Animates a `bottomSheet` to `detents[index]`. */
  snapToDetent(index: number): void;
}

export type NitroModal = HybridView<NitroModalProps, NitroModalMethods>;
