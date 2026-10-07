/// <reference lib="dom" />

import {
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { Modal } from 'react-native';
import type { ReactNativeView } from 'react-native-nitro-modules';
import type { NitroModalMethods, NitroModalProps } from './NitroModal.nitro';
import { ModalController } from './web/ModalController';

interface WebNitroModalViewProps extends NitroModalProps {
  hybridRef?: (instance: NitroModalMethods) => void;
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
 */
function WebNitroModalView(props: WebNitroModalViewProps) {
  const { hybridRef, children } = props;
  const [mounted, setMounted] = useState(false);
  const [controller] = useState(() => new ModalController(setMounted));
  const overlay = useRef<HTMLDivElement>(null);
  const backdrop = useRef<HTMLDivElement>(null);
  const surface = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const grabber = useRef<HTMLDivElement>(null);

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
    });
    return () => controller.elementsWillUnmount();
  }, [controller, mounted]);

  useLayoutEffect(() => {
    if (mounted) {
      controller.contentDidRender();
    }
  });

  if (!mounted) return null;

  // Styles set here never change; the presenters write sizes, positions,
  // colors and animated values straight to the elements.
  return (
    <Modal
      visible
      transparent
      animationType="none"
      onRequestClose={controller.handleBackPress}
    >
      {/* Focusable so the modal's focus trap lands here first rather than on the first input. */}
      <div ref={overlay} tabIndex={-1} style={styles.overlay}>
        <div ref={backdrop} aria-hidden style={styles.backdrop} />
        <div ref={surface} style={styles.surface}>
          <div ref={content} style={styles.content}>
            {children}
          </div>
          <div ref={grabber} aria-hidden style={styles.grabber} />
        </div>
      </div>
    </Modal>
  );
}

const styles = {
  overlay: {
    position: 'fixed',
    inset: 0,
    outline: 'none',
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
} satisfies Record<string, CSSProperties>;

export const NitroModalView = WebNitroModalView as unknown as ReactNativeView<
  NitroModalProps,
  NitroModalMethods
>;
