/// <reference lib="dom" />

import { AnimatedValue, INSTANT, type Motion } from './AnimatedValue';
import { prefersReducedMotion, type Insets, type Size } from './dom';
import { ModalPresenter } from './ModalPresenter';

const MARGIN = 16;
const DEFAULT_CORNER_RADIUS = 16;
const ENTER_SCALE = 0.9;

const ENTER: Motion = { type: 'spring', response: 0.3, dampingRatio: 0.82 };
const EXIT: Motion = { type: 'timing', duration: 200, easing: (t) => t * t };

interface Rect extends Size {
  x: number;
  y: number;
}

/** Area a card may occupy, clear of the safe area and the part of the keyboard above it. */
function availableRect(
  viewport: Size,
  insets: Insets,
  keyboardHeight: number
): Rect {
  const covered = Math.max(0, keyboardHeight - insets.bottom);
  return {
    x: insets.left + MARGIN,
    y: insets.top + MARGIN,
    width: Math.max(
      0,
      viewport.width - insets.left - insets.right - 2 * MARGIN
    ),
    height: Math.max(
      0,
      viewport.height - insets.top - insets.bottom - 2 * MARGIN - covered
    ),
  };
}

/** Best guess of the content area before the popup is on screen. */
export function popupContentArea(viewport: Size, insets: Insets): Size {
  const { width, height } = availableRect(viewport, insets, 0);
  return { width, height };
}

/** `popup` mode: a centered card over the backdrop with a fade/scale transition. */
export class PopupPresenter extends ModalPresenter {
  /** 0 hidden, 1 presented. */
  private readonly progress = new AnimatedValue(0, 0.001, (value) =>
    this.render(value)
  );

  protected didShow() {
    this.layout();
    this.progress.set(0);
  }

  protected animateIn() {
    this.progress.animateTo(1, this.isAnimated ? ENTER : INSTANT, {
      onEnd: () => this.notifyPresented(),
    });
  }

  protected animateOut(onEnd: () => void) {
    this.progress.animateTo(0, this.isAnimated ? EXIT : INSTANT, { onEnd });
  }

  protected configDidChange() {
    this.layout();
    this.render(this.progress.value);
  }

  protected viewportDidChange() {
    this.layout();
  }

  protected contentSizeDidChange() {
    this.layout();
  }

  protected stopAnimations() {
    this.progress.stop();
  }

  private get isAnimated(): boolean {
    return this.config.popupAnimation !== 'none' && !prefersReducedMotion();
  }

  private layout() {
    const { surface, content, grabber } = this.elements!;
    const behavior = this.config.keyboardBehavior;
    const available = availableRect(
      this.viewport,
      this.insets,
      behavior === 'none' ? 0 : this.keyboardHeight
    );
    const width = Math.min(this.contentSize.width, available.width);
    const height = Math.min(this.contentSize.height, available.height);
    Object.assign(surface.style, {
      left: `${available.x + (available.width - width) / 2}px`,
      top: `${available.y + (available.height - height) / 2}px`,
      width: `${width}px`,
      height: `${height}px`,
      borderRadius: `${this.config.cornerRadius ?? DEFAULT_CORNER_RADIUS}px`,
    });
    grabber.style.display = 'none';

    // The content host also bounds the content's own (shrink-to-fit) width.
    const area =
      behavior === 'resize'
        ? available
        : availableRect(this.viewport, this.insets, 0);
    content.style.width = `${area.width}px`;
    content.style.height = `${area.height}px`;
    this.reportContentArea(area.width, area.height);
  }

  private render(progress: number) {
    if (!this.elements) return;
    const { surface, backdrop } = this.elements;
    const visible = Math.min(Math.max(progress, 0), 1);
    const animation = this.config.popupAnimation;
    backdrop.style.opacity = String(visible);
    surface.style.opacity = animation === 'none' ? '1' : String(visible);
    const scale =
      animation === 'scale' ? ENTER_SCALE + (1 - ENTER_SCALE) * progress : 1;
    surface.style.transform = `scale(${scale})`;
  }
}
