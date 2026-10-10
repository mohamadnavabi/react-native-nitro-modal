/// <reference lib="dom" />

export interface Size {
  width: number;
  height: number;
}

export interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

export const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

export const canUseDOM =
  typeof window !== 'undefined' && typeof document !== 'undefined';

/** Width and height of the pull-to-refresh indicator. */
export const REFRESH_INDICATOR_SIZE = 24;

/** Converts a color from `processColor` (0xAARRGGBB) to CSS, scaling its alpha by `opacity`. */
export function toCSSColor(argb: number, opacity = 1): string {
  // As an unsigned 32-bit value, whatever the sign it was passed with.
  const value = ((argb % 2 ** 32) + 2 ** 32) % 2 ** 32;
  const channel = (shift: number) => Math.floor(value / 2 ** shift) % 256;
  const alpha = (channel(24) / 255) * Math.min(Math.max(opacity, 0), 1);
  return `rgba(${channel(16)}, ${channel(8)}, ${channel(0)}, ${Number(alpha.toFixed(3))})`;
}

let safeAreaProbe: HTMLElement | null = null;

/**
 * `env(safe-area-inset-*)`, read through a hidden probe element. Non-zero on
 * notched devices when the page opts in with `viewport-fit=cover`.
 */
export function readSafeAreaInsets(): Insets {
  if (!safeAreaProbe?.isConnected) {
    if (!document.body) return ZERO_INSETS;
    const probe = document.createElement('div');
    probe.setAttribute('aria-hidden', 'true');
    probe.style.cssText =
      'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;' +
      'padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) ' +
      'env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px);';
    document.body.appendChild(probe);
    safeAreaProbe = probe;
  }
  const style = getComputedStyle(safeAreaProbe);
  return {
    top: parseFloat(style.paddingTop) || 0,
    right: parseFloat(style.paddingRight) || 0,
    bottom: parseFloat(style.paddingBottom) || 0,
    left: parseFloat(style.paddingLeft) || 0,
  };
}

/** The part of the safe area insets that `element` overlaps. */
export function inlineInsets(element: Element): Insets {
  const safeArea = readSafeAreaInsets();
  const rect = element.getBoundingClientRect();
  const clamp = (value: number, max: number) =>
    Math.min(Math.max(value, 0), max);
  return {
    top: clamp(safeArea.top - rect.top, rect.height),
    right: clamp(rect.right - (window.innerWidth - safeArea.right), rect.width),
    bottom: clamp(
      rect.bottom - (window.innerHeight - safeArea.bottom),
      rect.height
    ),
    left: clamp(safeArea.left - rect.left, rect.width),
  };
}

/**
 * Height of the on-screen keyboard over a layout viewport `layoutHeight` tall.
 * Mobile browsers keep the layout viewport and shrink the visual one.
 */
export function readKeyboardHeight(layoutHeight: number): number {
  const visual = window.visualViewport;
  // Pinch zoom shrinks the visual viewport too.
  if (!visual || Math.abs(visual.scale - 1) > 0.01) return 0;
  const covered = layoutHeight - visual.offsetTop - visual.height;
  return covered >= 1 ? Math.round(covered) : 0;
}

/** The size of the viewport before anything is on screen. */
export function estimateViewport(): Size {
  return {
    width: document.documentElement.clientWidth || window.innerWidth,
    height: window.innerHeight,
  };
}

function isVerticalScroller(node: Element): node is HTMLElement {
  if (!(node instanceof HTMLElement) || node.scrollHeight <= node.clientHeight)
    return false;
  const { overflowY } = getComputedStyle(node);
  return overflowY === 'auto' || overflowY === 'scroll';
}

/** The innermost element from `target` up to (not including) `root` that scrolls vertically. */
export function findVerticalScroller(
  target: EventTarget | null,
  root: Element
): HTMLElement | null {
  let node = target instanceof Element ? target : null;
  for (; node && node !== root; node = node.parentElement) {
    if (isVerticalScroller(node)) return node;
  }
  return null;
}

/** Whether a scroller between `target` and `root` can scroll further by `deltaY`. */
export function canScrollWithin(
  target: EventTarget | null,
  root: Element,
  deltaY: number
): boolean {
  let node = target instanceof Element ? target : null;
  for (; node && node !== root; node = node.parentElement) {
    if (!isVerticalScroller(node)) continue;
    const canScroll =
      deltaY < 0
        ? node.scrollTop > 0
        : node.scrollTop + node.clientHeight < node.scrollHeight - 1;
    if (canScroll) return true;
  }
  return false;
}

export function isTextInput(target: EventTarget | null): boolean {
  return (
    target instanceof Element &&
    target.closest(
      'input, textarea, select, [contenteditable]:not([contenteditable="false"])'
    ) != null
  );
}

/** Swallows the click that follows a mouse drag, so the content under the pointer isn't pressed. */
export function suppressNextClick() {
  const swallow = (event: Event) => {
    event.stopPropagation();
    event.preventDefault();
  };
  window.addEventListener('click', swallow, { capture: true, once: true });
  // A click, if any, is dispatched in the same task as the pointerup.
  setTimeout(() => {
    window.removeEventListener('click', swallow, { capture: true });
  }, 0);
}

export function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

let clipValue: string | null = null;

/**
 * `overflow: clip` rounds the corners without making the element a scroll
 * container, which focusing an input could otherwise scroll.
 */
export function clipOverflow(): string {
  clipValue ??=
    typeof CSS !== 'undefined' && CSS.supports('overflow', 'clip')
      ? 'clip'
      : 'hidden';
  return clipValue;
}
