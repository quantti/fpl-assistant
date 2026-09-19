/** The subset of VisualViewport the decision needs; a plain object so it can be tested. */
export interface ViewportMetrics {
  height: number;
  offsetTop: number;
  scale: number;
}

/**
 * Smallest gap between the window and the visual viewport that is read as a keyboard. The
 * collapsing URL bar is under 100px on every iPhone; the smallest keyboard is over 250.
 */
export const KEYBOARD_MIN_INSET = 100;

/**
 * The height the app shell should take, in CSS px, or null when CSS's own `100dvh` is the
 * right answer.
 *
 * WebKit does not shrink the layout viewport for the software keyboard, so `100dvh` stays
 * full-height and the keyboard paints over the composer. The visual viewport does shrink,
 * and that is the number to size the shell to — but only for a keyboard: a pinch-zoom shrinks
 * the visual viewport too, and sizing the shell to a zoomed-in window would wreck the page
 * on every desktop browser.
 */
export function keyboardAwareHeight(vv: ViewportMetrics, innerHeight: number): number | null {
  if (vv.scale > 1.01) return null;
  const inset = innerHeight - vv.height;
  if (inset < KEYBOARD_MIN_INSET) return null;
  return vv.height;
}
