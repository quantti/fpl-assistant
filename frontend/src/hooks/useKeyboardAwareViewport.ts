import { useEffect } from 'react';
import { keyboardAwareHeight } from './viewportHeight';

const PROPERTY = '--app-height';
/** How close to the bottom, in px, still counts as "reading the latest message". */
const BOTTOM_TOLERANCE = 40;

/**
 * Keeps `--app-height` on <html> equal to the visual viewport's height while a software
 * keyboard is up, and absent otherwise. ChatView's shell and SignIn's page read it as
 * `height: var(--app-height, 100dvh)`. Mounted once in App, above both.
 *
 * Browsers that honour `interactive-widget=resizes-content` (Chrome, Firefox) shrink the
 * window itself, so the inset stays near zero and this never writes anything — the meta tag
 * stays the primary fix and this is the WebKit fallback (bug 259770).
 */
export function useKeyboardAwareViewport(): void {
  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;
    const root = document.documentElement;

    const apply = () => {
      const height = keyboardAwareHeight(
        { height: vv.height, offsetTop: vv.offsetTop, scale: vv.scale },
        window.innerHeight,
      );
      // Read before the height changes: once the thread is shorter, "was at the bottom" is
      // no longer measurable. A reader who had scrolled up is left where they were.
      const thread = document.querySelector<HTMLElement>('[data-thread]');
      const wasAtBottom =
        thread !== null &&
        thread.scrollHeight - thread.scrollTop - thread.clientHeight < BOTTOM_TOLERANCE;
      if (height === null) {
        root.style.removeProperty(PROPERTY);
      } else {
        root.style.setProperty(PROPERTY, `${height}px`);
        // WebKit also scrolls the layout viewport to bring the focused field into view. With
        // the shell now no taller than the visual viewport that scroll only pushes the header
        // off the top, so undo it. If this fights Safari on a device — visible jitter while
        // typing — the alternative is `--app-offset: ${vv.offsetTop}px` plus
        // `transform: translateY(var(--app-offset, 0))` on .shell, which follows the scroll
        // instead of undoing it.
        if (window.scrollY !== 0) window.scrollTo(0, 0);
      }
      if (thread && wasAtBottom) {
        requestAnimationFrame(() => {
          thread.scrollTop = thread.scrollHeight;
        });
      }
    };

    vv.addEventListener('resize', apply);
    vv.addEventListener('scroll', apply);
    apply();
    return () => {
      vv.removeEventListener('resize', apply);
      vv.removeEventListener('scroll', apply);
      root.style.removeProperty(PROPERTY);
    };
  }, []);
}
