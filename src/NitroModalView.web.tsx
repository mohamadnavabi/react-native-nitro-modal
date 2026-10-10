/// <reference lib="dom" />

import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { Modal, View, type StyleProp, type ViewStyle } from 'react-native';
import type { ReactNativeView } from 'react-native-nitro-modules';
import type { NitroModalMethods, NitroModalProps } from './NitroModal.nitro';
import { clipOverflow, REFRESH_INDICATOR_SIZE } from './web/dom';
import { ModalController } from './web/ModalController';

interface WebNitroModalViewProps extends NitroModalProps {
  hybridRef?: (instance: NitroModalMethods) => void;
  /** Inline: the frame the sheet fills. */
  style?: StyleProp<ViewStyle>;
  children?: ReactNode;
}

/** The web view takes plain functions; only native host components need them wrapped. */
export function callback<T>(func: T): T {
  return func;
}

/**
 * Web implementation of the hybrid view. The modal renders through React
 * Native Web's `Modal` (a portal with focus trapping, Escape handling and
 * stacking with other modals) and is laid out and animated by the presenters
 * in `./web`. In place, it renders nothing.
 *
 * An inline sheet renders in place instead: a view laid out with `style`,
 * holding the sheet's elements.
 */
function WebNitroModalView(props: WebNitroModalViewProps) {
  const { hybridRef, children } = props;
  const isInline = props.isInline && props.mode === 'bottomSheet';
  const [mounted, setMounted] = useState(false);
  const [controller] = useState(() => new ModalController(setMounted));
  const overlay = useRef<HTMLDivElement>(null);
  const backdrop = useRef<HTMLDivElement>(null);
  const surface = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const grabber = useRef<HTMLDivElement>(null);
  const refreshIndicator = useRef<HTMLDivElement>(null);
  const refreshSpinner = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    controller.attach();
    return () => controller.detach();
  }, [controller]);

  useLayoutEffect(() => {
    hybridRef?.(controller.methods as NitroModalMethods);
  }, [controller, hybridRef]);

  useLayoutEffect(() => {
    controller.update(props);
  });

  useLayoutEffect(() => {
    if (!mounted) return undefined;
    controller.elementsDidMount({
      overlay: overlay.current!,
      backdrop: backdrop.current!,
      surface: surface.current!,
      content: content.current!,
      grabber: grabber.current!,
      refreshIndicator: refreshIndicator.current!,
      refreshSpinner: refreshSpinner.current!,
    });
    return () => controller.elementsWillUnmount();
  }, [controller, mounted]);

  useLayoutEffect(() => {
    if (mounted) {
      controller.contentDidRender();
    }
  });

  const hostRef = useCallback(
    (node: unknown) => {
      controller.hostDidChange(node instanceof HTMLElement ? node : null);
    },
    [controller]
  );

  // Styles set here never change; the presenters write sizes, positions,
  // colors and animated values straight to the elements.
  const layer = mounted ? (
    // Focusable so the modal's focus trap lands here first rather than on the first input.
    <div
      ref={overlay}
      tabIndex={-1}
      style={
        isInline
          ? { ...styles.inlineOverlay, overflow: clipOverflow() }
          : styles.overlay
      }
    >
      <div ref={backdrop} aria-hidden style={styles.backdrop} />
      <div ref={refreshIndicator} aria-hidden style={styles.refreshIndicator}>
        <div ref={refreshSpinner} style={styles.refreshSpinner} />
      </div>
      <div ref={surface} style={styles.surface}>
        <div ref={content} style={styles.content}>
          {children}
        </div>
        <div ref={grabber} aria-hidden style={styles.grabber} />
      </div>
    </div>
  ) : null;

  if (isInline) {
    return (
      <View ref={hostRef} style={props.style} pointerEvents="box-none">
        {layer}
      </View>
    );
  }

  if (!layer) return null;

  return (
    <Modal
      visible
      transparent
      animationType="none"
      onRequestClose={controller.handleBackPress}
    >
      {layer}
    </Modal>
  );
}

const styles = {
  overlay: {
    position: 'fixed',
    inset: 0,
    outline: 'none',
  },
  // Fills the host view; the sheet hides below its bottom edge.
  inlineOverlay: {
    position: 'absolute',
    inset: 0,
    outline: 'none',
    pointerEvents: 'none',
  },
  backdrop: {
    position: 'absolute',
    inset: 0,
    touchAction: 'none',
  },
  surface: {
    position: 'absolute',
    left: 0,
    top: 0,
    boxSizing: 'border-box',
    willChange: 'transform',
  },
  // Pinned at the origin like the native content view; the React content is
  // positioned and sized inside it by `NitroModal`.
  content: {
    position: 'absolute',
    left: 0,
    top: 0,
  },
  grabber: {
    position: 'absolute',
    top: 5,
    left: '50%',
    width: 36,
    height: 5,
    marginLeft: -18,
    borderRadius: 2.5,
    backgroundColor: 'rgba(127, 127, 127, 0.4)',
    pointerEvents: 'none',
  },
  refreshIndicator: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: REFRESH_INDICATOR_SIZE,
    height: REFRESH_INDICATOR_SIZE,
    opacity: 0,
    pointerEvents: 'none',
  },
  // Colored through `color` by the presenter.
  refreshSpinner: {
    width: '100%',
    height: '100%',
    boxSizing: 'border-box',
    borderRadius: '50%',
    border: '2.5px solid currentColor',
    borderTopColor: 'transparent',
  },
} satisfies Record<string, CSSProperties>;

export const NitroModalView = WebNitroModalView as unknown as ReactNativeView<
  NitroModalProps,
  NitroModalMethods
>;
