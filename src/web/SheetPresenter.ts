/// <reference lib="dom" />

import type { SheetDetent } from '../NitroModal.nitro';
import { AnimatedValue, INSTANT, type Motion } from './AnimatedValue';
import {
  findVerticalScroller,
  isTextInput,
  prefersReducedMotion,
  suppressNextClick,
  type Insets,
  type Size,
} from './dom';
import type { ModalConfig } from './ModalConfig';
import { ModalPresenter, type PresenterListener } from './ModalPresenter';

/** Gap kept above a modal sheet at its tallest. */
const TOP_GAP = 10;
/** How far below its lowest detent the sheet must be pulled to refresh. */
const REFRESH_THRESHOLD = 56;
/**
 * Extra sheet height below the viewport so it stays attached to the bottom
 * edge while rubber-banding upward.
 */
const OVERSCROLL = 400;
const DEFAULT_CORNER_RADIUS = 16;
const MAX_WIDTH = 640;
/** Mouse movement before a press turns into a drag. */
const POINTER_SLOP = 6;
/** Pointer samples used for the release velocity. */
const VELOCITY_WINDOW_MS = 100;

const ENTER: Motion = { type: 'spring', response: 0.4, dampingRatio: 0.9 };
const SETTLE: Motion = { type: 'spring', response: 0.4, dampingRatio: 0.9 };
/** Underdamped and stopped at the bottom edge, so the exit ends on a fast frame instead of a slow tail. */
const EXIT: Motion = { type: 'spring', response: 0.45, dampingRatio: 0.8 };

interface Drag {
  input: 'touch' | 'pointer';
  /** Touch identifier or pointer id. */
  id: number;
  startX: number;
  startY: number;
  lastY: number;
  /** The innermost vertical scroll container under the start point. */
  scroller: HTMLElement | null;
  /** The sheet claimed the gesture and follows it. */
  active: boolean;
  /** Unconstrained sheet top, before rubber-banding. */
  rawTop: number;
  /** Whether the last movement moved the sheet rather than the scroller. */
  sheetDrove: boolean;
  /**
   * The scroller moved during this gesture. It then keeps the downward part
   * too: scrolled content goes back to its top, and only a new gesture moves
   * the sheet.
   */
  contentScrolled: boolean;
  /** Started with the sheet resting on its lowest detent, so pulling it further down refreshes. */
  canRefresh: boolean;
  /** Pulled far enough that letting go refreshes. */
  refreshArmed: boolean;
  samples: { time: number; y: number }[];
}

function sheetWidth(available: number, isInline: boolean): number {
  return isInline ? available : Math.min(available, MAX_WIDTH);
}

/** Tallest height the content may occupy (excludes the bottom safe area). */
function maximumDetentValue(
  viewportHeight: number,
  insets: Insets,
  isInline: boolean
): number {
  const gap = isInline ? 0 : TOP_GAP;
  return Math.max(0, viewportHeight - insets.top - insets.bottom - gap);
}

/** Content height at `detent`; `fitContent` uses the measured `fitting` height. */
function detentHeight(
  detent: SheetDetent,
  maximum: number,
  fitting: number
): number {
  switch (detent) {
    case 'small':
      return maximum * 0.25;
    case 'medium':
      return maximum * 0.5;
    case 'large':
      return maximum;
    case 'fitContent':
      return Math.min(Math.max(fitting, 1), maximum);
    default:
      return Number.isFinite(detent)
        ? Math.min(Math.max(detent, 1), maximum)
        : maximum;
  }
}

/** Height the content may occupy at the largest of `detents`. */
function contentHeight(detents: SheetDetent[], maximum: number): number {
  if (detents.length === 0) return maximum;
  // `fitContent` content can grow up to the maximum.
  return Math.max(
    ...detents.map((detent) => detentHeight(detent, maximum, maximum))
  );
}

/** Best guess of the content area before the sheet is on screen. */
export function sheetContentArea(
  detents: SheetDetent[],
  viewport: Size,
  insets: Insets,
  isInline: boolean
): Size {
  return {
    width: sheetWidth(viewport.width, isInline),
    height: contentHeight(
      detents,
      maximumDetentValue(viewport.height, insets, isInline)
    ),
  };
}

function rubberBand(offset: number, dimension: number): number {
  return (1 - 1 / ((offset * 0.55) / dimension + 1)) * dimension;
}

function findTouch(touches: TouchList, id: number): Touch | undefined {
  for (let i = 0; i < touches.length; i++) {
    const touch = touches.item(i);
    if (touch?.identifier === id) return touch;
  }
  return undefined;
}

/**
 * `bottomSheet` mode: an edge-attached sheet with detents, dragging,
 * swipe-to-dismiss and hand-off to scrollable content, like the iOS
 * implementation.
 */
export class SheetPresenter extends ModalPresenter {
  private selectedIndex = 0;
  /** The sheet sits below the viewport (before presenting / while dismissing). */
  private isOffscreen = true;
  private drag: Drag | null = null;
  /** Vertical velocity of the swipe that requested the dismissal. */
  private releaseVelocity = 0;
  /** The sheet's top edge in the viewport. */
  private readonly top = new AnimatedValue(0, 0.5, (value) =>
    this.render(value)
  );

  constructor(config: ModalConfig, listener: PresenterListener) {
    super(config, listener);
    this.selectedIndex = Math.min(
      Math.max(config.initialDetentIndex, 0),
      this.detents.length - 1
    );
  }

  snapToDetent(index: number) {
    if (index < 0 || index >= this.detents.length) return;
    this.selectedIndex = index;
    this.animateToRest();
    this.listener.onDetentChange(index);
  }

  protected didShow() {
    const { surface } = this.elements!;
    surface.addEventListener('touchstart', this.handleTouchStart, {
      passive: true,
    });
    surface.addEventListener('touchmove', this.handleTouchMove, {
      passive: false,
    });
    surface.addEventListener('touchend', this.handleTouchEnd);
    surface.addEventListener('touchcancel', this.handleTouchEnd);
    surface.addEventListener('pointerdown', this.handlePointerDown);
    this.layout();
    this.top.set(this.viewport.height);
  }

  protected animateIn() {
    this.isOffscreen = false;
    this.settle(ENTER);
  }

  protected animateOut(onEnd: () => void) {
    this.cancelDrag();
    this.isOffscreen = true;
    const velocity = this.releaseVelocity;
    this.releaseVelocity = 0;
    this.top.animateTo(this.viewport.height, this.motion(EXIT), {
      velocity: velocity > 0 ? velocity : undefined,
      stopAtTarget: true,
      onEnd,
    });
  }

  protected configDidChange() {
    this.selectedIndex = Math.min(this.selectedIndex, this.detents.length - 1);
    this.layout();
    this.animateToRest();
  }

  protected viewportDidChange() {
    this.layout();
    this.animateToRest();
  }

  protected contentSizeDidChange() {
    if (this.config.detents.includes('fitContent')) {
      this.animateToRest();
    }
  }

  protected stopAnimations() {
    this.top.stop();
  }

  protected teardown() {
    super.teardown();
    this.cancelDrag();
    const surface = this.elements?.surface;
    surface?.removeEventListener('touchstart', this.handleTouchStart);
    surface?.removeEventListener('touchmove', this.handleTouchMove);
    surface?.removeEventListener('touchend', this.handleTouchEnd);
    surface?.removeEventListener('touchcancel', this.handleTouchEnd);
    surface?.removeEventListener('pointerdown', this.handlePointerDown);
  }

  // Layout

  private get detents(): SheetDetent[] {
    return this.config.detents.length > 0
      ? this.config.detents
      : ['fitContent'];
  }

  private get maximumDetentValue(): number {
    return maximumDetentValue(
      this.viewport.height,
      this.insets,
      this.config.isInline
    );
  }

  private get keyboardLift(): number {
    return this.config.keyboardBehavior === 'none'
      ? 0
      : Math.max(0, this.keyboardHeight - this.insets.bottom);
  }

  private height(detent: SheetDetent): number {
    return detentHeight(
      detent,
      this.maximumDetentValue,
      this.contentSize.height
    );
  }

  /** Sheet top when resting on `detents[index]`. The bottom safe area is added below the content. */
  private restingTop(index: number): number {
    const { detents } = this;
    const detent =
      detents[Math.min(Math.max(index, 0), detents.length - 1)] ?? 'fitContent';
    const top =
      this.viewport.height -
      this.insets.bottom -
      this.keyboardLift -
      this.height(detent);
    return Math.max(
      top,
      this.insets.top + (this.config.isInline ? 0 : TOP_GAP)
    );
  }

  private get detentTops(): number[] {
    return this.detents.map((_, index) => this.restingTop(index));
  }

  private layout() {
    const { surface, content, grabber } = this.elements!;
    const width = sheetWidth(this.viewport.width, this.config.isInline);
    const radius = `${this.config.cornerRadius ?? DEFAULT_CORNER_RADIUS}px`;
    Object.assign(surface.style, {
      left: `${(this.viewport.width - width) / 2}px`,
      top: '0px',
      width: `${width}px`,
      height: `${this.viewport.height + OVERSCROLL}px`,
      borderTopLeftRadius: radius,
      borderTopRightRadius: radius,
      borderBottomLeftRadius: '0px',
      borderBottomRightRadius: '0px',
    });
    content.style.width = `${width}px`;
    content.style.height = `${this.maximumDetentValue}px`;
    grabber.style.display = this.config.grabberVisible ? '' : 'none';

    let height = contentHeight(this.config.detents, this.maximumDetentValue);
    if (this.config.keyboardBehavior === 'resize') {
      height -= this.keyboardLift;
    }
    this.reportContentArea(width, Math.max(height, 0));
    this.render(this.top.value);
  }

  private render(top: number) {
    if (!this.elements) return;
    this.elements.surface.style.transform = `translate3d(0, ${top}px, 0)`;
    this.elements.backdrop.style.opacity = String(this.backdropAlpha(top));
  }

  /** Fully dimmed at every detent; fades out below the lowest one. */
  private backdropAlpha(top: number): number {
    const bottom = this.viewport.height;
    const lowest = Math.max(...this.detentTops);
    if (!(bottom > lowest)) return top >= bottom ? 0 : 1;
    return Math.min(Math.max((bottom - top) / (bottom - lowest), 0), 1);
  }

  private motion(motion: Motion): Motion {
    return prefersReducedMotion() ? INSTANT : motion;
  }

  private settle(motion: Motion, velocity?: number) {
    this.top.animateTo(
      this.restingTop(this.selectedIndex),
      this.motion(motion),
      {
        velocity,
        // Keeps the enter's completion when a re-layout takes it over.
        onEnd: () => this.notifyPresented(),
      }
    );
  }

  private animateToRest() {
    if (!this.elements || this.drag?.active || this.isOffscreen) return;
    this.settle(SETTLE);
  }

  // Dragging

  private canDrag(): boolean {
    return !this.entering && !this.isDismissing && !this.isOffscreen;
  }

  private beginDrag(
    input: Drag['input'],
    id: number,
    x: number,
    y: number,
    target: EventTarget | null
  ) {
    this.drag = {
      input,
      id,
      startX: x,
      startY: y,
      lastY: y,
      scroller: findVerticalScroller(target, this.elements!.surface),
      active: false,
      rawTop: 0,
      sheetDrove: false,
      contentScrolled: false,
      canRefresh: false,
      refreshArmed: false,
      samples: [],
    };
  }

  /** Decides who handles a gesture once it moves: the sheet, or the content (scrolling, horizontal swipes). */
  private claim(
    drag: Drag,
    x: number,
    y: number,
    slop: number
  ): 'pending' | 'sheet' | 'content' {
    const dx = x - drag.startX;
    const dy = y - drag.startY;
    if (Math.abs(dx) <= slop && Math.abs(dy) <= slop) return 'pending';
    if (Math.abs(dx) > Math.abs(dy)) return 'content';
    const { scroller } = drag;
    if (!scroller) return 'sheet';
    // Down: scroll back to the top before collapsing the sheet. Up: expand
    // the sheet before scrolling.
    if (dy > 0) return scroller.scrollTop <= 0 ? 'sheet' : 'content';
    return this.top.value > Math.min(...this.detentTops) + 0.5
      ? 'sheet'
      : 'content';
  }

  private activate(drag: Drag) {
    drag.active = true;
    const isSettled = !this.top.isAnimating;
    this.top.stop();
    drag.rawTop = this.top.value;
    drag.canRefresh =
      this.config.pullToRefreshEnabled &&
      !this.config.dismissOnSwipe &&
      isSettled &&
      Math.abs(drag.rawTop - Math.max(...this.detentTops)) < 1;
    const { surface } = this.elements!;
    // A native scroll on an ancestor makes React Native Web cancel the press
    // under the pointer, so dragging from a button doesn't leave it pressed.
    surface.dispatchEvent(new Event('scroll'));
    if (drag.input === 'pointer') {
      surface.style.setProperty('user-select', 'none');
      surface.style.setProperty('-webkit-user-select', 'none');
      window.getSelection()?.removeAllRanges();
    }
  }

  private moveDrag(drag: Drag, y: number) {
    const delta = y - drag.lastY;
    drag.lastY = y;
    const time = performance.now();
    drag.samples.push({ time, y });
    while (
      drag.samples.length > 2 &&
      time - drag.samples[0]!.time > VELOCITY_WINDOW_MS
    ) {
      drag.samples.shift();
    }

    const { scroller } = drag;
    if (scroller) {
      // The browser's own scrolling is prevented for this gesture, so the
      // content is scrolled here whenever the sheet can't move. Up: expand
      // the sheet before scrolling. Down: scroll back to the top before
      // collapsing the sheet, in a separate gesture.
      const minTop = Math.min(...this.detentTops);
      const drive =
        delta < 0
          ? drag.rawTop > minTop + 0.5
          : !drag.contentScrolled && scroller.scrollTop <= 0;
      if (drive) {
        drag.rawTop = Math.max(drag.rawTop + delta, minTop);
      } else {
        scroller.scrollTop -= delta;
        if (delta !== 0) {
          drag.contentScrolled = true;
        }
      }
      drag.sheetDrove = drive;
    } else {
      drag.rawTop += delta;
      drag.sheetDrove = true;
    }
    const top = this.constrained(drag.rawTop);
    if (drag.canRefresh) {
      drag.refreshArmed =
        top - Math.max(...this.detentTops) >= REFRESH_THRESHOLD;
    }
    this.top.set(top);
  }

  private release(drag: Drag) {
    this.stopTracking();
    this.drag = null;
    if (!drag.active) return;
    if (drag.input === 'pointer') {
      suppressNextClick();
    }

    const velocity = drag.sheetDrove ? this.velocity(drag) : 0;
    const top = this.top.value;
    const tops = this.detentTops;
    const lowest = Math.max(...tops);
    // Where a fling of this velocity would come to rest.
    const projected = top + velocity * 0.2;

    if (
      this.config.dismissOnSwipe &&
      velocity >= 0 &&
      projected > lowest + (this.viewport.height - lowest) / 2
    ) {
      this.releaseVelocity = velocity;
      this.listener.onSwipeDismiss();
      if (this.isDismissing) return;
      this.releaseVelocity = 0;
    }

    const index = tops.reduce(
      (best, candidate, i) =>
        Math.abs(candidate - projected) < Math.abs(tops[best]! - projected)
          ? i
          : best,
      0
    );
    if (index !== this.selectedIndex) {
      this.selectedIndex = index;
      this.listener.onDetentChange(index);
    }
    this.settle(SETTLE, velocity);
    if (drag.refreshArmed) {
      this.listener.onPullToRefresh();
    }
  }

  /** Abandons the gesture without settling (the exit or the content takes over). */
  private cancelDrag() {
    this.stopTracking();
    this.drag = null;
  }

  private stopTracking() {
    window.removeEventListener('pointermove', this.handlePointerMove);
    window.removeEventListener('pointerup', this.handlePointerUp);
    window.removeEventListener('pointercancel', this.handlePointerUp);
    const surface = this.elements?.surface;
    surface?.style.removeProperty('user-select');
    surface?.style.removeProperty('-webkit-user-select');
  }

  private velocity(drag: Drag): number {
    const now = performance.now();
    const samples = drag.samples.filter(
      (sample) => now - sample.time <= VELOCITY_WINDOW_MS
    );
    const first = samples[0];
    const last = samples[samples.length - 1];
    if (!first || !last || last.time <= first.time) return 0;
    return ((last.y - first.y) / (last.time - first.time)) * 1000;
  }

  /** Rubber-bands above the tallest detent, and below the lowest one when swiping can't dismiss. */
  private constrained(top: number): number {
    const tops = this.detentTops;
    const minTop = Math.min(...tops);
    const maxTop = Math.max(...tops);
    const dimension = Math.max(this.viewport.height, 1);
    if (top < minTop) {
      return minTop - rubberBand(minTop - top, dimension);
    }
    if (!this.config.dismissOnSwipe && top > maxTop) {
      return maxTop + rubberBand(top - maxTop, dimension);
    }
    return top;
  }

  // Touch input. Touch events (rather than pointer events) let the sheet
  // cancel the browser's scrolling when it claims the gesture.

  private handleTouchStart = (event: TouchEvent) => {
    if (this.drag) {
      // A second finger before the sheet moved: leave it to the browser.
      if (this.drag.input === 'touch' && !this.drag.active) {
        this.drag = null;
      }
      return;
    }
    const touch = event.touches.length === 1 ? event.touches[0] : undefined;
    if (!touch || !this.canDrag()) return;
    this.beginDrag(
      'touch',
      touch.identifier,
      touch.clientX,
      touch.clientY,
      event.target
    );
  };

  private handleTouchMove = (event: TouchEvent) => {
    const drag = this.drag;
    if (drag?.input !== 'touch') return;
    const touch = findTouch(event.changedTouches, drag.id);
    if (!touch) return;
    if (!drag.active) {
      // Once the browser has started scrolling, the gesture is its.
      const owner = event.cancelable
        ? this.claim(drag, touch.clientX, touch.clientY, 0)
        : 'content';
      if (owner === 'pending') return;
      if (owner === 'content') {
        this.drag = null;
        return;
      }
      this.activate(drag);
    }
    if (event.cancelable) {
      event.preventDefault();
    }
    this.moveDrag(drag, touch.clientY);
  };

  private handleTouchEnd = (event: TouchEvent) => {
    const drag = this.drag;
    if (drag?.input !== 'touch' || !findTouch(event.changedTouches, drag.id))
      return;
    this.release(drag);
  };

  // Mouse and pen input.

  private handlePointerDown = (event: PointerEvent) => {
    if (
      event.pointerType === 'touch' ||
      event.button !== 0 ||
      this.drag ||
      !this.canDrag() ||
      // Leave text selection in inputs alone.
      isTextInput(event.target)
    ) {
      return;
    }
    this.beginDrag(
      'pointer',
      event.pointerId,
      event.clientX,
      event.clientY,
      event.target
    );
    window.addEventListener('pointermove', this.handlePointerMove);
    window.addEventListener('pointerup', this.handlePointerUp);
    window.addEventListener('pointercancel', this.handlePointerUp);
  };

  private handlePointerMove = (event: PointerEvent) => {
    const drag = this.drag;
    if (drag?.input !== 'pointer' || event.pointerId !== drag.id) return;
    if (!drag.active) {
      const owner = this.claim(
        drag,
        event.clientX,
        event.clientY,
        POINTER_SLOP
      );
      if (owner === 'pending') return;
      if (owner === 'content') {
        this.cancelDrag();
        return;
      }
      this.activate(drag);
    }
    this.moveDrag(drag, event.clientY);
  };

  private handlePointerUp = (event: PointerEvent) => {
    const drag = this.drag;
    if (drag?.input !== 'pointer' || event.pointerId !== drag.id) return;
    this.release(drag);
  };
}
