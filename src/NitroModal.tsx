import {
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type Ref,
} from 'react';
import {
  processColor,
  StyleSheet,
  useWindowDimensions,
  View,
  type ColorValue,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import type {
  DismissReason,
  KeyboardBehavior,
  ModalContentArea,
  ModalMode,
  NitroModal as NitroModalHybridView,
  PopupAnimation,
  SheetDetent,
} from './NitroModal.nitro';
import { callback, NitroModalView } from './NitroModalView';

export interface NitroModalRef {
  /** Opens the modal. Only for uncontrolled usage (no `isOpen` prop). */
  present(): void;
  /** Closes the modal. `onDismiss` then fires with `'programmatic'`. */
  dismiss(): void;
  /** Animates a `bottomSheet` to `detents[index]`. */
  snapToDetent(index: number): void;
}

export interface NitroModalProps {
  /**
   * Controls visibility. Leave undefined to drive the modal through `ref`
   * instead. When the user dismisses it natively (swipe, backdrop, back
   * button), `onDismiss` fires and you should set this back to `false`.
   */
  isOpen?: boolean;
  /** @default 'bottomSheet' */
  mode?: ModalMode;
  /** Sheet heights, smallest first. Android uses at most three. @default ['fitContent'] */
  detents?: SheetDetent[];
  /** @default 0 */
  initialDetentIndex?: number;
  /** @default 'black' */
  backdropColor?: ColorValue;
  /** @default 0.4 */
  backdropOpacity?: number;
  /** Blur radius (dp/pt/px) behind the modal. Android 12+; approximated on iOS. @default 0 */
  backdropBlur?: number;
  /** @default true */
  dismissOnBackdropPress?: boolean;
  /** Swipe down to dismiss a `bottomSheet`. @default true */
  dismissOnSwipe?: boolean;
  /** Android back button/gesture (Escape on web) dismisses the modal. @default true */
  dismissOnBackButton?: boolean;
  /** @default false */
  showGrabber?: boolean;
  /** Corner radius of the sheet/card. Defaults to the platform style. */
  cornerRadius?: number;
  /** Sheet/card background. Defaults to the system surface color. */
  backgroundColor?: ColorValue;
  /** @default 'pan' */
  keyboardBehavior?: KeyboardBehavior;
  /** @default 'scale' */
  popupAnimation?: PopupAnimation;
  /** Style of the view that wraps `children` inside the modal. */
  contentContainerStyle?: StyleProp<ViewStyle>;
  onPresent?: () => void;
  onDismiss?: (reason: DismissReason) => void;
  onDetentChange?: (index: number) => void;
  onBackdropPress?: () => void;
  onBackButtonPress?: () => void;
  children?: ReactNode;
  testID?: string;
  ref?: Ref<NitroModalRef>;
}

const DEFAULT_DETENTS: SheetDetent[] = ['fitContent'];
const BLACK = 0xff000000;

function toNativeColor(color: ColorValue | undefined): number | undefined {
  if (color == null) return undefined;
  const processed = processColor(color);
  // PlatformColor / DynamicColorIOS objects are not supported.
  return typeof processed === 'number' ? processed : undefined;
}

export function NitroModal({
  isOpen,
  mode = 'bottomSheet',
  detents = DEFAULT_DETENTS,
  initialDetentIndex = 0,
  backdropColor = 'black',
  backdropOpacity = 0.4,
  backdropBlur = 0,
  dismissOnBackdropPress = true,
  dismissOnSwipe = true,
  dismissOnBackButton = true,
  showGrabber = false,
  cornerRadius,
  backgroundColor,
  keyboardBehavior = 'pan',
  popupAnimation = 'scale',
  contentContainerStyle,
  onPresent,
  onDismiss,
  onDetentChange,
  onBackdropPress,
  onBackButtonPress,
  children,
  testID,
  ref,
}: NitroModalProps) {
  const isControlled = isOpen !== undefined;
  const [internalOpen, setInternalOpen] = useState(false);
  const open = isControlled ? isOpen : internalOpen;

  // Children mount when the modal opens and stay mounted until native reports
  // that it is fully gone, so the exit animation still shows them.
  const [sessionActive, setSessionActive] = useState(open);
  const [prevOpen, setPrevOpen] = useState(open);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) setSessionActive(true);
  }
  const renderContent = open || sessionActive;

  const [area, setArea] = useState<ModalContentArea | null>(null);
  const hybridRef = useRef<NitroModalHybridView | null>(null);

  // Native callbacks are created once (stable props, no native updates on
  // re-render) and read the latest handlers from this ref.
  const latest = useRef({
    isControlled,
    onPresent,
    onDismiss,
    onDetentChange,
    onBackdropPress,
    onBackButtonPress,
  });
  useLayoutEffect(() => {
    latest.current = {
      isControlled,
      onPresent,
      onDismiss,
      onDetentChange,
      onBackdropPress,
      onBackButtonPress,
    };
  });

  const nativeCallbacks = useMemo(
    () => ({
      hybridRef: callback((instance: NitroModalHybridView) => {
        hybridRef.current = instance;
      }),
      onPresent: callback(() => {
        setSessionActive(true);
        latest.current.onPresent?.();
      }),
      onDismiss: callback((reason: DismissReason) => {
        setSessionActive(false);
        if (!latest.current.isControlled) setInternalOpen(false);
        latest.current.onDismiss?.(reason);
      }),
      onDetentChange: callback((index: number) => {
        latest.current.onDetentChange?.(index);
      }),
      onBackdropPress: callback(() => {
        latest.current.onBackdropPress?.();
      }),
      onBackButtonPress: callback(() => {
        latest.current.onBackButtonPress?.();
      }),
      onContentAreaChange: callback((next: ModalContentArea) => {
        setArea((current) =>
          current?.width === next.width && current.height === next.height
            ? current
            : next
        );
      }),
    }),
    []
  );

  useImperativeHandle(
    ref,
    () => ({
      present: () => {
        if (latest.current.isControlled) {
          console.warn(
            'NitroModal: present() is ignored while `isOpen` is controlled.'
          );
          return;
        }
        setInternalOpen(true);
      },
      dismiss: () => {
        if (latest.current.isControlled) {
          hybridRef.current?.dismiss();
        } else {
          setInternalOpen(false);
        }
      },
      snapToDetent: (index: number) => {
        hybridRef.current?.snapToDetent(index);
      },
    }),
    []
  );

  // Arrays are diffed by identity, so keep one instance per distinct value.
  const detentsKey = detents.join(',');
  const nativeDetents = useMemo(
    () => [...new Set(detentsKey.split(','))] as SheetDetent[],
    [detentsKey]
  );

  // Native reports the exact area; this only covers the first frame.
  const windowSize = useWindowDimensions();
  const contentArea = area ?? {
    width: mode === 'popup' ? windowSize.width - 32 : windowSize.width,
    height:
      mode === 'popup' ? windowSize.height - 160 : windowSize.height * 0.85,
  };
  const containerStyle: ViewStyle =
    mode === 'popup'
      ? { maxWidth: contentArea.width, maxHeight: contentArea.height }
      : nativeDetents.includes('fitContent')
        ? { width: contentArea.width, maxHeight: contentArea.height }
        : { width: contentArea.width, height: contentArea.height };

  return (
    <NitroModalView
      style={styles.host}
      isOpen={open}
      mode={mode}
      detents={nativeDetents}
      initialDetentIndex={initialDetentIndex}
      backdropColor={toNativeColor(backdropColor) ?? BLACK}
      backdropOpacity={backdropOpacity}
      backdropBlurRadius={backdropBlur}
      dismissOnBackdropPress={dismissOnBackdropPress}
      dismissOnSwipe={dismissOnSwipe}
      dismissOnBackButton={dismissOnBackButton}
      grabberVisible={showGrabber}
      cornerRadius={cornerRadius ?? -1}
      contentBackgroundColor={toNativeColor(backgroundColor)}
      keyboardBehavior={keyboardBehavior}
      popupAnimation={popupAnimation}
      {...nativeCallbacks}
    >
      {renderContent ? (
        <View
          collapsable={false}
          testID={testID}
          style={[styles.container, containerStyle, contentContainerStyle]}
        >
          {children}
        </View>
      ) : null}
    </NitroModalView>
  );
}

const styles = StyleSheet.create({
  // Zero-size placeholder in the React tree; the content renders natively in
  // the modal's own window / view controller.
  host: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: 0,
    height: 0,
  },
  // Pinned at the origin: native hosts it at the top of the sheet / the
  // card, and uses its measured size for `fitContent` and the card size.
  container: {
    position: 'absolute',
    left: 0,
    top: 0,
  },
});
