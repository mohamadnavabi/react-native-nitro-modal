import type {
  KeyboardBehavior,
  ModalMode,
  NitroModalProps,
  PopupAnimation,
  SheetDetent,
} from '../NitroModal.nitro';

/** Snapshot of the props that shape a presentation. */
export interface ModalConfig {
  mode: ModalMode;
  /**
   * A `bottomSheet` rendered in place, filling the host view: no backdrop,
   * the page behind stays interactive, and the user can't dismiss it.
   */
  isInline: boolean;
  detents: SheetDetent[];
  initialDetentIndex: number;
  /** 0xAARRGGBB, from `processColor`. */
  backdropColor: number;
  backdropOpacity: number;
  backdropBlurRadius: number;
  dismissOnBackdropPress: boolean;
  dismissOnSwipe: boolean;
  dismissOnBackButton: boolean;
  grabberVisible: boolean;
  /** `null` uses the default. */
  cornerRadius: number | null;
  /** 0xAARRGGBB; `null` uses the system background. */
  contentBackgroundColor: number | null;
  keyboardBehavior: KeyboardBehavior;
  popupAnimation: PopupAnimation;
  pullToRefreshEnabled: boolean;
  /** A refresh is in progress; `null` shows no refresh indicator. */
  refreshing: boolean | null;
  /** 0xAARRGGBB; `null` uses the default. */
  refreshIndicatorColor: number | null;
}

export function makeConfig(props: NitroModalProps): ModalConfig {
  // An inline sheet is part of the page; the user can't dismiss it.
  const isInline = props.isInline && props.mode === 'bottomSheet';
  return {
    mode: props.mode,
    isInline,
    detents: props.detents,
    initialDetentIndex: Number.isFinite(props.initialDetentIndex)
      ? Math.trunc(props.initialDetentIndex)
      : 0,
    backdropColor: props.backdropColor,
    backdropOpacity: props.backdropOpacity,
    backdropBlurRadius: props.backdropBlurRadius,
    dismissOnBackdropPress: props.dismissOnBackdropPress && !isInline,
    dismissOnSwipe: props.dismissOnSwipe && !isInline,
    dismissOnBackButton: props.dismissOnBackButton && !isInline,
    grabberVisible: props.grabberVisible,
    cornerRadius: props.cornerRadius >= 0 ? props.cornerRadius : null,
    contentBackgroundColor: props.contentBackgroundColor ?? null,
    keyboardBehavior: props.keyboardBehavior,
    popupAnimation: props.popupAnimation,
    pullToRefreshEnabled: props.pullToRefreshEnabled,
    refreshing: props.refreshing ?? null,
    refreshIndicatorColor: props.refreshIndicatorColor ?? null,
  };
}

export function isSameConfig(a: ModalConfig, b: ModalConfig): boolean {
  return (Object.keys(a) as (keyof ModalConfig)[]).every((key) =>
    key === 'detents'
      ? a.detents.join() === b.detents.join()
      : a[key] === b[key]
  );
}
