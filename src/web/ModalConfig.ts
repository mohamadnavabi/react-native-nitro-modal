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
}

export function makeConfig(props: NitroModalProps): ModalConfig {
  return {
    mode: props.mode,
    detents: props.detents,
    initialDetentIndex: Number.isFinite(props.initialDetentIndex)
      ? Math.trunc(props.initialDetentIndex)
      : 0,
    backdropColor: props.backdropColor,
    backdropOpacity: props.backdropOpacity,
    backdropBlurRadius: props.backdropBlurRadius,
    dismissOnBackdropPress: props.dismissOnBackdropPress,
    dismissOnSwipe: props.dismissOnSwipe,
    dismissOnBackButton: props.dismissOnBackButton,
    grabberVisible: props.grabberVisible,
    cornerRadius: props.cornerRadius >= 0 ? props.cornerRadius : null,
    contentBackgroundColor: props.contentBackgroundColor ?? null,
    keyboardBehavior: props.keyboardBehavior,
    popupAnimation: props.popupAnimation,
  };
}

export function isSameConfig(a: ModalConfig, b: ModalConfig): boolean {
  return (Object.keys(a) as (keyof ModalConfig)[]).every((key) =>
    key === 'detents'
      ? a.detents.join() === b.detents.join()
      : a[key] === b[key]
  );
}
