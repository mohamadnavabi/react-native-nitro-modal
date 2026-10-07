/// <reference lib="dom" />

import type {
  DismissReason,
  ModalContentArea,
  NitroModalMethods,
  NitroModalProps,
} from '../NitroModal.nitro';
import { canUseDOM, estimateViewport, readSafeAreaInsets } from './dom';
import { isSameConfig, makeConfig, type ModalConfig } from './ModalConfig';
import type {
  ModalElements,
  ModalPresenter,
  PresenterListener,
} from './ModalPresenter';
import { popupContentArea, PopupPresenter } from './PopupPresenter';
import { sheetContentArea, SheetPresenter } from './SheetPresenter';

type Phase = 'idle' | 'presenting' | 'presented' | 'dismissing';

/**
 * The web counterpart of the native `HybridNitroModal`: receives the host
 * view's props and drives one presenter per presentation.
 *
 * Presentation is a small state machine so that rapid `isOpen` toggles, user
 * dismissals and unmounts always settle on a consistent state, and every
 * accepted present request ends with exactly one `onDismiss`. Transitions are
 * interrupted rather than queued: closing mid-enter turns the enter around,
 * and opening mid-exit turns the exit around (that session carries on, so it
 * gets no `onDismiss` and at most one `onPresent`).
 */
export class ModalController implements PresenterListener {
  readonly methods: Pick<
    NitroModalMethods,
    'present' | 'dismiss' | 'snapToDetent'
  > = {
    present: () => this.requestPresent(),
    dismiss: () => this.requestDismiss('programmatic'),
    snapToDetent: (index: number) => {
      if (Number.isFinite(index)) {
        this.presenter?.snapToDetent(Math.trunc(index));
      }
    },
  };

  private props: NitroModalProps | null = null;
  private config: ModalConfig | null = null;
  private isOpen = false;
  private phase: Phase = 'idle';
  /** What React (or an imperative call) asked for. */
  private wantsOpen = false;
  /** A present request was accepted and its `onDismiss` is still owed. */
  private sessionActive = false;
  /** `onPresent` was sent for the current session. */
  private presentSent = false;
  private dismissReason: DismissReason = 'programmatic';
  private reconcileScheduled = false;
  private isAttached = false;
  private presenter: ModalPresenter | null = null;
  private elements: ModalElements | null = null;
  private lastContentArea: ModalContentArea | null = null;

  /** `setMounted` renders (or removes) the modal's elements. */
  constructor(private readonly setMounted: (mounted: boolean) => void) {}

  // Host view lifecycle

  attach() {
    this.isAttached = true;
    window.addEventListener('resize', this.handleWindowResize);
  }

  /** The host view unmounted. Also runs for React's strict-mode remount, so `attach` must work again. */
  detach() {
    this.isAttached = false;
    window.removeEventListener('resize', this.handleWindowResize);
    this.presenter?.destroy();
    this.presenter = null;
    this.phase = 'idle';
    this.wantsOpen = false;
    this.sessionActive = false;
    this.presentSent = false;
    this.dismissReason = 'programmatic';
    // Applied again from the props on the next `update`.
    this.isOpen = false;
    this.config = null;
    this.lastContentArea = null;
    this.setMounted(false);
  }

  /** The host view rendered with new props. */
  update(props: NitroModalProps) {
    this.props = props;
    const next = makeConfig(props);
    if (!this.config || !isSameConfig(next, this.config)) {
      this.config = next;
      if (this.presenter) {
        this.presenter.update(next);
      } else {
        this.reportEstimatedContentArea();
      }
    }
    if (props.isOpen !== this.isOpen) {
      this.isOpen = props.isOpen;
      if (props.isOpen) {
        this.requestPresent();
      } else {
        this.requestDismiss('programmatic');
      }
    }
  }

  elementsDidMount(elements: ModalElements) {
    this.elements = elements;
    if (this.presenter && !this.presenter.isShown) {
      this.presenter.show(elements);
    }
  }

  elementsWillUnmount() {
    this.elements = null;
  }

  contentDidRender() {
    this.presenter?.contentDidRender();
  }

  /** Escape key, routed by React Native Web's `Modal` to the topmost modal. */
  handleBackPress = () => {
    // Already on its way out: the press is spent.
    if (!this.isVisible) return;
    this.props?.onBackButtonPress?.();
    if (!this.config?.dismissOnBackButton) return;
    this.requestDismiss('backButton');
    this.reconcile();
  };

  // State machine

  private get isVisible(): boolean {
    return this.phase === 'presenting' || this.phase === 'presented';
  }

  private requestPresent() {
    if (!this.isAttached) return;
    this.wantsOpen = true;
    this.sessionActive = true;
    this.scheduleReconcile();
  }

  private requestDismiss(reason: DismissReason) {
    if (this.wantsOpen) {
      this.dismissReason = reason;
    }
    this.wantsOpen = false;
    this.scheduleReconcile();
  }

  /** Coalesces the requests made in one task, e.g. an open and a close in the same handler. */
  private scheduleReconcile() {
    if (this.reconcileScheduled) return;
    this.reconcileScheduled = true;
    queueMicrotask(() => {
      this.reconcileScheduled = false;
      if (this.isAttached) {
        this.reconcile();
      }
    });
  }

  private reconcile() {
    switch (this.phase) {
      case 'presenting':
      case 'presented':
        if (!this.wantsOpen) {
          this.startDismiss();
        }
        break;
      case 'dismissing':
        if (this.wantsOpen && this.presenter?.cancelDismiss()) {
          this.phase = 'presenting';
          this.dismissReason = 'programmatic';
        }
        break;
      case 'idle':
        if (this.wantsOpen) {
          this.startPresent();
        } else if (this.sessionActive) {
          this.endSession(); // Cancelled before anything appeared.
        }
        break;
    }
  }

  private startPresent() {
    const { config } = this;
    if (!canUseDOM || !config) return;
    const presenter =
      config.mode === 'popup'
        ? new PopupPresenter(config, this)
        : new SheetPresenter(config, this);
    this.presenter = presenter;
    this.phase = 'presenting';
    this.setMounted(true);
    // Still mounted from a presentation that just ended.
    if (this.elements) {
      presenter.show(this.elements);
    }
  }

  private startDismiss() {
    const { presenter } = this;
    if (!presenter?.isShown) {
      // Its elements never mounted.
      presenter?.destroy();
      this.finishDismiss();
      return;
    }
    this.phase = 'dismissing';
    presenter.dismiss();
  }

  private finishDismiss() {
    this.phase = 'idle';
    this.presenter = null;
    this.setMounted(false);
    this.endSession();
    this.reconcile();
  }

  private endSession() {
    if (!this.sessionActive) return;
    const reason = this.dismissReason;
    this.dismissReason = 'programmatic';
    this.presentSent = false;
    // A present requested while we were dismissing starts a new session.
    this.sessionActive = this.wantsOpen;
    if (this.isAttached) {
      this.props?.onDismiss?.(reason);
    }
  }

  // PresenterListener

  onPresented() {
    if (this.phase !== 'presenting') return;
    this.phase = 'presented';
    if (!this.presentSent) {
      this.presentSent = true;
      this.props?.onPresent?.();
    }
    this.reconcile();
  }

  onDismissed() {
    this.finishDismiss();
  }

  onBackdropPress() {
    this.props?.onBackdropPress?.();
    if (!this.config?.dismissOnBackdropPress || !this.isVisible) return;
    this.requestDismiss('backdrop');
    this.reconcile();
  }

  onSwipeDismiss() {
    if (!this.config?.dismissOnSwipe || !this.isVisible) return;
    this.requestDismiss('swipe');
    this.reconcile();
  }

  onDetentChange(index: number) {
    this.props?.onDetentChange?.(index);
  }

  onContentAreaChange(width: number, height: number) {
    const area = { width: Math.floor(width), height: Math.floor(height) };
    const last = this.lastContentArea;
    if (area.width <= 0 || area.height <= 0) return;
    if (last?.width === area.width && last.height === area.height) return;
    this.lastContentArea = area;
    this.props?.onContentAreaChange?.(area);
  }

  // Helpers

  /** Lets React size the content before the first presentation. */
  private reportEstimatedContentArea() {
    const { config } = this;
    if (!canUseDOM || !this.isAttached || !config) return;
    const viewport = estimateViewport();
    const insets = readSafeAreaInsets();
    const area =
      config.mode === 'popup'
        ? popupContentArea(viewport, insets)
        : sheetContentArea(config.detents, viewport, insets);
    this.onContentAreaChange(area.width, area.height);
  }

  private handleWindowResize = () => {
    if (!this.presenter) {
      this.reportEstimatedContentArea();
    }
  };
}
