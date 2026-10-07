/// <reference lib="dom" />

import {
  canScrollWithin,
  clipOverflow,
  inlineInsets,
  readKeyboardHeight,
  readSafeAreaInsets,
  toCSSColor,
  ZERO_INSETS,
  type Insets,
  type Size,
} from './dom';
import type { ModalConfig } from './ModalConfig';

/** DOM rendered by the web host view for one presentation. */
export interface ModalElements {
  /** Full-window layer holding the backdrop and the surface. */
  overlay: HTMLElement;
  backdrop: HTMLElement;
  /** The sheet or the popup card. */
  surface: HTMLElement;
  /** Hosts the React content. */
  content: HTMLElement;
  grabber: HTMLElement;
}

export interface PresenterListener {
  /** An enter transition settled: the first one, or one that reversed an exit. */
  onPresented(): void;
  /** The exit transition finished. Called at most once per presenter. */
  onDismissed(): void;
  onBackdropPress(): void;
  /** The user swiped the sheet down far enough to dismiss it. */
  onSwipeDismiss(): void;
  onDetentChange(index: number): void;
  onContentAreaChange(width: number, height: number): void;
  /** The user pulled the sheet past its lowest detent and let go. */
  onPullToRefresh(): void;
}

/**
 * Owns one presentation from show to dismiss. Subclasses lay out the surface
 * and drive the enter/exit animations; this class tracks the viewport, the
 * on-screen keyboard and the content size, and handles the backdrop and
 * teardown.
 *
 * Transitions are interruptible: dismissing mid-enter turns the enter around,
 * and `cancelDismiss` turns an exit back around. Once an exit starts the modal
 * stops taking input.
 *
 * Inline, the elements fill the host view in place of a full-window layer:
 * only the surface takes input, and the keyboard is left to the page.
 */
export abstract class ModalPresenter {
  protected elements: ModalElements | null = null;
  protected viewport: Size = { width: 0, height: 0 };
  protected insets: Insets = ZERO_INSETS;
  /** Part of the viewport covered by the on-screen keyboard, from the bottom. */
  protected keyboardHeight = 0;
  /** Measured size of the React content. */
  protected contentSize: Size = { width: 0, height: 0 };
  protected entering = false;
  protected isDismissing = false;
  private finished = false;
  private observedContent: HTMLElement | null = null;
  private resizeObserver: ResizeObserver | null = null;
  /** Inline: follows the host's size, which can change without a window resize. */
  private hostObserver: ResizeObserver | null = null;

  constructor(
    protected config: ModalConfig,
    protected readonly listener: PresenterListener
  ) {}

  get isShown(): boolean {
    return this.elements != null;
  }

  /** Takes over the rendered elements and starts the enter transition. */
  show(elements: ModalElements) {
    if (this.elements || this.finished) return;
    this.elements = elements;
    // The elements can outlive a previous presentation (an immediate re-present).
    this.setInteractive(true);
    elements.surface.style.overflow = clipOverflow();
    elements.surface.style.opacity = '';
    elements.surface.style.transform = '';
    elements.backdrop.style.display = this.config.isInline ? 'none' : '';

    window.addEventListener('resize', this.handleResize);
    if (this.config.isInline) {
      if (typeof ResizeObserver !== 'undefined') {
        this.hostObserver = new ResizeObserver(this.handleResize);
        this.hostObserver.observe(elements.overlay);
      }
    } else {
      window.visualViewport?.addEventListener('resize', this.handleKeyboard);
      window.visualViewport?.addEventListener('scroll', this.handleKeyboard);
      elements.backdrop.addEventListener('click', this.handleBackdropClick);
      elements.overlay.addEventListener('wheel', this.handleWheel, {
        passive: false,
      });
    }
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(this.handleContentResize);
    }

    this.measureViewport();
    this.observeContent();
    this.applyBackdrop();
    this.didShow();
    this.entering = true;
    this.animateIn();
  }

  update(config: ModalConfig) {
    const previous = this.config;
    this.config = config;
    if (!this.elements) return;
    this.applyBackdrop();
    this.configDidChange(previous);
  }

  /** The React content re-rendered; its root element may have changed. */
  contentDidRender() {
    if (this.elements && this.observeContent()) {
      this.contentSizeDidChange();
    }
  }

  /**
   * Animated dismissal, also from mid-enter. `onDismissed` follows when it
   * completes, unless `cancelDismiss` turns it around first.
   */
  dismiss() {
    const elements = this.elements;
    if (!elements || this.isDismissing || this.finished) return;
    this.isDismissing = true;
    this.entering = false;
    this.setInteractive(false);
    // Also hides the on-screen keyboard.
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && elements.overlay.contains(focused)) {
      focused.blur();
    }
    this.animateOut(() => this.finish());
  }

  /**
   * Reverses a running exit from wherever it is. `onPresented` follows once
   * it settles. Returns false when there is no exit to reverse.
   */
  cancelDismiss(): boolean {
    if (!this.elements || !this.isDismissing || this.finished) return false;
    this.isDismissing = false;
    this.setInteractive(true);
    this.entering = true;
    this.animateIn();
    return true;
  }

  /** Tears down without animating or notifying the listener. */
  destroy() {
    if (this.finished) return;
    this.finished = true;
    this.teardown();
  }

  snapToDetent(_index: number) {}

  /** The elements are in place; lay out the hidden state. */
  protected abstract didShow(): void;

  /** Animates from the current state to presented, then calls `notifyPresented`. */
  protected abstract animateIn(): void;

  /** Animates from the current state (possibly mid-enter) to gone, then calls `onEnd`. */
  protected abstract animateOut(onEnd: () => void): void;

  protected abstract configDidChange(previous: ModalConfig): void;

  /** The viewport size, safe area or keyboard height changed. */
  protected abstract viewportDidChange(): void;

  protected abstract contentSizeDidChange(): void;

  protected abstract stopAnimations(): void;

  /** For subclasses: the running enter animation settled. */
  protected notifyPresented() {
    if (!this.entering || this.isDismissing || this.finished) return;
    this.entering = false;
    this.listener.onPresented();
  }

  protected reportContentArea(width: number, height: number) {
    if (width > 0 && height > 0) {
      this.listener.onContentAreaChange(width, height);
    }
  }

  protected teardown() {
    this.stopAnimations();
    window.removeEventListener('resize', this.handleResize);
    window.visualViewport?.removeEventListener('resize', this.handleKeyboard);
    window.visualViewport?.removeEventListener('scroll', this.handleKeyboard);
    this.elements?.backdrop.removeEventListener(
      'click',
      this.handleBackdropClick
    );
    this.elements?.overlay.removeEventListener('wheel', this.handleWheel);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.hostObserver?.disconnect();
    this.hostObserver = null;
    this.observedContent = null;
  }

  private finish() {
    if (this.finished) return;
    this.finished = true;
    this.teardown();
    this.listener.onDismissed();
  }

  private applyBackdrop() {
    const { backdrop, surface } = this.elements!;
    const { config } = this;
    backdrop.style.backgroundColor = toCSSColor(
      config.backdropColor,
      config.backdropOpacity
    );
    const blur =
      config.backdropBlurRadius > 0
        ? `blur(${config.backdropBlurRadius}px)`
        : '';
    backdrop.style.setProperty('backdrop-filter', blur);
    backdrop.style.setProperty('-webkit-backdrop-filter', blur);
    // `Canvas` follows the page's `color-scheme`, like a system background.
    surface.style.backgroundColor =
      config.contentBackgroundColor != null
        ? toCSSColor(config.contentBackgroundColor)
        : 'Canvas';
  }

  /** Inline, only the surface takes input; the rest reaches the page. */
  private setInteractive(interactive: boolean) {
    const { overlay, surface } = this.elements!;
    if (this.config.isInline) {
      overlay.style.pointerEvents = 'none';
      surface.style.pointerEvents = interactive ? 'auto' : 'none';
    } else {
      overlay.style.pointerEvents = interactive ? '' : 'none';
    }
  }

  private measureViewport() {
    const { overlay } = this.elements!;
    this.viewport = {
      width: overlay.clientWidth,
      height: overlay.clientHeight,
    };
    if (this.config.isInline) {
      this.insets = inlineInsets(overlay);
      this.keyboardHeight = 0;
    } else {
      this.insets = readSafeAreaInsets();
      this.keyboardHeight = readKeyboardHeight(this.viewport.height);
    }
  }

  /** Re-targets the observer at the content's root element. Returns whether its size changed. */
  private observeContent(): boolean {
    const root = this.elements?.content.firstElementChild;
    const element = root instanceof HTMLElement ? root : null;
    if (element !== this.observedContent) {
      if (this.observedContent) {
        this.resizeObserver?.unobserve(this.observedContent);
      }
      this.observedContent = element;
      if (element) {
        this.resizeObserver?.observe(element);
      }
    }
    return this.measureContent();
  }

  private measureContent(): boolean {
    // Layout sizes, unaffected by the transition's transforms.
    const width = this.observedContent?.offsetWidth ?? 0;
    const height = this.observedContent?.offsetHeight ?? 0;
    if (width === this.contentSize.width && height === this.contentSize.height)
      return false;
    this.contentSize = { width, height };
    return true;
  }

  private handleContentResize = () => {
    if (this.measureContent()) {
      this.contentSizeDidChange();
    }
  };

  private handleResize = () => {
    if (!this.elements) return;
    this.measureViewport();
    this.viewportDidChange();
  };

  private handleKeyboard = () => {
    if (this.config.isInline) return;
    const height = readKeyboardHeight(this.viewport.height);
    if (height === this.keyboardHeight) return;
    this.keyboardHeight = height;
    this.viewportDidChange();
  };

  private handleBackdropClick = () => {
    if (!this.isDismissing && !this.finished) {
      this.listener.onBackdropPress();
    }
  };

  /** Keeps the page behind the modal from scrolling; scrollable content still scrolls. */
  private handleWheel = (event: WheelEvent) => {
    if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY))
      return;
    if (!canScrollWithin(event.target, this.elements!.overlay, event.deltaY)) {
      event.preventDefault();
    }
  };
}
