# KAR-22 — iOS keyboard covers the composer: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is tracked on the single `**PROGRESS:**` line below, per `AGENTS.md` — no per-step checkboxes.

**PROGRESS:** Task 3/4 done — Task 1's WebKit check done (259770 still NEW, 2026-06-07); the device reproduction and Task 4's checklist are the owner's, on an iPhone

**Goal:** On iPhone Safari, the composer stays visible above the software keyboard, and the thread keeps scrolling inside the shell, without changing anything on browsers that already honour `interactive-widget=resizes-content`.

**Architecture:** The shell is sized by CSS (`height: 100dvh`). WebKit does not shrink the layout viewport for the keyboard, so on iOS the shell stays full-height and the keyboard paints over its bottom, where the composer is. The fix is a JS fallback: a hook reads `window.visualViewport`, and when the visual viewport is shorter than the window by more than a keyboard-sized amount, writes that height to a CSS custom property (`--app-height`) that the shell and column use in place of `100dvh`. Everything else — the thread's internal scroll, the composer's safe-area padding — keeps working because the column is simply shorter. The decision the ticket asks for is taken in Task 1 against a real device, not assumed.

**Tech Stack:** React 19, Vite 8, CSS Modules; Vitest (new, for the one pure function).

**Spec:** Linear KAR-22 (https://linear.app/quantti/issue/KAR-22). No design doc: the ticket is the spec.

## Global Constraints

- `AGENTS.md` conventions apply: Mantine v9 + CSS Modules, no Tailwind; agents push a branch and open a PR, never push to `master`.
- The `interactive-widget=resizes-content` viewport meta in `frontend/index.html` stays: it is the correct fix wherever it is implemented, and the fallback must be a no-op there.
- No new runtime dependency. Vitest is a dev dependency.
- Frontend has no tests today. This plan adds the runner for one pure function; it does not add DOM testing libraries.

## File structure

| File | Responsibility |
|---|---|
| `frontend/src/hooks/viewportHeight.ts` | Pure decision: given viewport metrics, the shell height in px or `null` (= use CSS). Unit-tested. |
| `frontend/src/hooks/viewportHeight.test.ts` | The tests for it. |
| `frontend/src/hooks/useKeyboardAwareViewport.ts` | Thin effect: subscribes to `visualViewport`, applies the decision to `--app-height`, keeps the thread pinned to its bottom if it was there. Not unit-tested; verified on device in Task 4. |
| `frontend/src/App.tsx` | Calls the hook once, above both `ChatView` and `SignIn` — the sign-in form has the same keyboard over its password field. |
| `frontend/src/components/ChatView/ChatView.module.css` | `.shell` and `.root` read `var(--app-height, 100dvh)`. |
| `frontend/src/components/SignIn/SignIn.module.css` | `min-height: var(--app-height, 100dvh)`. |
| `frontend/src/components/MessageThread/MessageThread.tsx` | `data-thread` on the scrolling element so the hook can find it without a ref crossing components. |
| `frontend/vitest.config.ts`, `frontend/package.json` | Test runner. Separate config because `vite.config.ts` throws without `VITE_SUPABASE_ANON_KEY`. |

---

### Task 1: Reproduce on a device and decide

The ticket says "needs a decision on whether to add a JS fallback or accept the behaviour". The decision is made on evidence, not on the bug tracker.

**Files:** none changed.

- **Step 1: Reproduce.** On an iPhone (Safari, and Chrome for iOS — also WebKit), sign in at `https://fpl-sidekick.com`, open a conversation with a few messages, tap the composer. Record: iOS version; whether the composer is visible above the keyboard; whether the header is still visible; whether the page has scrolled (drag down — does a blank band appear above the header?).
- **Step 2: Check whether WebKit has shipped `interactive-widget`.** Open https://bugs.webkit.org/show_bug.cgi?id=259770. If it is RESOLVED FIXED and the device's iOS is at or past that version, and Step 1 showed the composer visible: **stop here.** Add a `docs/STATUS.md` entry saying so (iOS version, date, what was observed), close KAR-22 with the same note, and do not do Tasks 2–4.
- **Step 3: Otherwise, record the failure in one sentence** (e.g. "iOS 26.0 Safari: keyboard covers composer, header scrolls off, page scrollable by ~300px") — it goes into the STATUS entry in Task 4 — and continue.

**Done when:** the observation is written down and the branch is decided.

---

### Task 2: Vitest, and the failing test for the pure decision

**Files:**
- Modify: `frontend/package.json` (devDependencies, scripts)
- Create: `frontend/vitest.config.ts`
- Create: `frontend/src/hooks/viewportHeight.test.ts`

**Interfaces:**
- Produces: `keyboardAwareHeight(vv: ViewportMetrics, innerHeight: number): number | null` and `KEYBOARD_MIN_INSET: number` from `frontend/src/hooks/viewportHeight.ts` (implemented in Task 3).

- **Step 1: Add vitest.**

```bash
cd frontend && npm install --save-dev vitest
```

Add to `package.json` `scripts`:

```json
"test": "vitest run"
```

- **Step 2: Create `frontend/vitest.config.ts`.** Separate from `vite.config.ts`, which throws when `VITE_SUPABASE_ANON_KEY` is unset — a test run must not need a Supabase key.

```ts
import { defineConfig } from 'vitest/config'

// Kept apart from vite.config.ts on purpose: that file refuses to load without
// VITE_SUPABASE_ANON_KEY, and a unit test of a pure function has no business needing one.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
```

- **Step 3: Write the failing test.**

```ts
// frontend/src/hooks/viewportHeight.test.ts
import { describe, expect, it } from 'vitest'
import { keyboardAwareHeight, KEYBOARD_MIN_INSET } from './viewportHeight'

const window = 844 // iPhone 14 CSS px

describe('keyboardAwareHeight', () => {
  it('leaves the layout viewport in charge when nothing is covering it', () => {
    expect(keyboardAwareHeight({ height: 844, offsetTop: 0, scale: 1 }, window)).toBeNull()
  })

  it('sizes the shell to the visual viewport when a keyboard-sized inset appears', () => {
    // iOS keyboard on a 14: roughly 336px
    expect(keyboardAwareHeight({ height: 508, offsetTop: 0, scale: 1 }, window)).toBe(508)
  })

  it('ignores insets smaller than a keyboard, such as the URL bar collapsing', () => {
    expect(keyboardAwareHeight({ height: window - KEYBOARD_MIN_INSET + 1, offsetTop: 0, scale: 1 }, window))
      .toBeNull()
  })

  it('ignores a pinch-zoom, which also shrinks the visual viewport', () => {
    expect(keyboardAwareHeight({ height: 400, offsetTop: 120, scale: 2.1 }, window)).toBeNull()
  })

  it('treats the threshold itself as a keyboard', () => {
    expect(keyboardAwareHeight({ height: window - KEYBOARD_MIN_INSET, offsetTop: 0, scale: 1 }, window))
      .toBe(window - KEYBOARD_MIN_INSET)
  })
})
```

- **Step 4: Run it and watch it fail.**

Run: `cd frontend && npm test`
Expected: FAIL — `Cannot find module './viewportHeight'`.

- **Step 5: Commit** (test and runner only; the red test is the point).

```bash
git add frontend/package.json frontend/package-lock.json frontend/vitest.config.ts frontend/src/hooks/viewportHeight.test.ts
git commit -m "test: add vitest and the keyboard-inset decision test"
```

---

### Task 3: The decision, the hook, and the CSS variable

**Files:**
- Create: `frontend/src/hooks/viewportHeight.ts`
- Create: `frontend/src/hooks/useKeyboardAwareViewport.ts`
- Modify: `frontend/src/App.tsx` (import + one call)
- Modify: `frontend/src/components/ChatView/ChatView.module.css:21-37`
- Modify: `frontend/src/components/SignIn/SignIn.module.css:5`
- Modify: `frontend/src/components/MessageThread/MessageThread.tsx` (one attribute)

**Interfaces:**
- Consumes: nothing.
- Produces: `useKeyboardAwareViewport(): void`; CSS custom property `--app-height` on `<html>`, present only while a keyboard is up; `data-thread` attribute on the thread's scrolling element.

- **Step 1: Implement the pure decision.**

```ts
// frontend/src/hooks/viewportHeight.ts

/** The subset of VisualViewport the decision needs; a plain object so it can be tested. */
export interface ViewportMetrics {
  height: number
  offsetTop: number
  scale: number
}

/**
 * Smallest gap between the window and the visual viewport that is read as a keyboard. The
 * collapsing URL bar is under 100px on every iPhone; the smallest keyboard is over 250.
 */
export const KEYBOARD_MIN_INSET = 100

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
  if (vv.scale > 1.01) return null
  const inset = innerHeight - vv.height
  if (inset < KEYBOARD_MIN_INSET) return null
  return vv.height
}
```

- **Step 2: Run the tests.**

Run: `cd frontend && npm test`
Expected: 5 passed.

- **Step 3: Implement the hook.**

```ts
// frontend/src/hooks/useKeyboardAwareViewport.ts
import { useEffect } from 'react'
import { keyboardAwareHeight } from './viewportHeight'

const PROPERTY = '--app-height'
/** How close to the bottom, in px, still counts as "reading the latest message". */
const BOTTOM_TOLERANCE = 40

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
    const vv = window.visualViewport
    if (!vv) return
    const root = document.documentElement

    const apply = () => {
      const height = keyboardAwareHeight(
        { height: vv.height, offsetTop: vv.offsetTop, scale: vv.scale },
        window.innerHeight,
      )
      // Read before the height changes: once the thread is shorter, "was at the bottom" is
      // no longer measurable. A reader who had scrolled up is left where they were.
      const thread = document.querySelector<HTMLElement>('[data-thread]')
      const wasAtBottom = thread !== null
        && thread.scrollHeight - thread.scrollTop - thread.clientHeight < BOTTOM_TOLERANCE
      if (height === null) {
        root.style.removeProperty(PROPERTY)
      } else {
        root.style.setProperty(PROPERTY, `${height}px`)
        // WebKit also scrolls the layout viewport to bring the focused field into view. With
        // the shell now no taller than the visual viewport that scroll only pushes the header
        // off the top, so undo it. If the device checklist (Task 4) shows this fighting Safari
        // — visible jitter while typing — replace it with
        // `root.style.setProperty('--app-offset', `${vv.offsetTop}px`)` and a
        // `transform: translateY(var(--app-offset, 0))` on .shell, which follows the scroll
        // instead of undoing it.
        if (window.scrollY !== 0) window.scrollTo(0, 0)
      }
      if (thread && wasAtBottom) {
        requestAnimationFrame(() => { thread.scrollTop = thread.scrollHeight })
      }
    }

    vv.addEventListener('resize', apply)
    vv.addEventListener('scroll', apply)
    apply()
    return () => {
      vv.removeEventListener('resize', apply)
      vv.removeEventListener('scroll', apply)
      root.style.removeProperty(PROPERTY)
    }
  }, [])
}
```

- **Step 4: Wire it into App, and tag the thread.** In `App.tsx`, call it once at the top of the component so both the chat and the sign-in form are covered (the sign-in page has the same keyboard over its password field):

```ts
import { useKeyboardAwareViewport } from './hooks/useKeyboardAwareViewport'
// ...
export default function App() {
  useKeyboardAwareViewport()
  const { session, loading } = useSession()
```

In `MessageThread.tsx`, add `data-thread` to the element that carries the module's scrolling class (`overflow-y: auto` in `MessageThread.module.css`) — read the component to find it; it is the root `div` of the thread:

```tsx
<div className={classes.thread} data-thread>
```

- **Step 5: Make the CSS read the variable.** In `SignIn.module.css` line 5, `min-height: 100dvh` becomes `min-height: var(--app-height, 100dvh)`. In `ChatView.module.css` change both height declarations and extend the header comment:

```css
/* `height: 100dvh` (not `min-height`) is required for `.thread`'s ... (existing text) ...
 *
 * `--app-height` is written by useKeyboardAwareViewport only while an iOS software keyboard
 * is up — WebKit ignores interactive-widget=resizes-content (bug 259770), so dvh stays
 * full-height there and the keyboard would cover the composer. Elsewhere the property is
 * absent and the fallback is exactly what it was. */
.shell {
  display: flex;
  height: var(--app-height, 100dvh);
  background: var(--paper);
}

.root {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: var(--app-height, 100dvh);
  background: var(--paper);
  color: var(--ink);
}
```

- **Step 6: Type-check and build.**

Run: `cd frontend && npx tsc -b && npm run build`
Expected: no errors. (`build` also runs `check-contrast`; nothing here changes colours.)

- **Step 7: Desktop regression check.** `npm run dev`, open in Chrome: pinch-zoom (trackpad) the page — the shell must not shrink; DevTools → toggle device toolbar → iPhone: no `--app-height` on `<html>` until a keyboard-sized resize happens (DevTools cannot simulate one; that is Task 4).

- **Step 8: Commit.**

```bash
git add frontend/src/hooks/viewportHeight.ts frontend/src/hooks/useKeyboardAwareViewport.ts \
        frontend/src/App.tsx frontend/src/components/ChatView/ChatView.module.css \
        frontend/src/components/SignIn/SignIn.module.css \
        frontend/src/components/MessageThread/MessageThread.tsx
git commit -m "fix: size the shell to the visual viewport while the iOS keyboard is up"
```

---

### Task 4: Device verification, docs, PR

**Files:**
- Modify: `docs/STATUS.md` (append an entry)

- **Step 1: Deploy the branch somewhere a phone can reach.** Either `npm run dev` with the phone on the same network (`vite --host`), or open the PR and test after merge on `fpl-sidekick.com` — the owner's call; a PR is the normal path.

- **Step 2: Run the checklist on the iPhone from Task 1** and record each line's result:

| Check | Expected |
|---|---|
| Tap the composer | Composer sits directly above the keyboard; header still visible |
| Tap the composer with the thread scrolled to its bottom | The latest message is still visible above the composer (the hook re-pins the bottom) |
| Tap the composer with the thread scrolled up | The reading position is kept; nothing jumps |
| Type a sentence | No jitter or bounce of the header/composer while typing — if there is, switch to the `translateY(offsetTop)` variant noted in the hook and re-run this row |
| Type three lines (autosize) | Composer grows upward; thread shrinks; nothing hidden |
| Sign-in page: tap the password field | Field visible above the keyboard; the Sign in button reachable |
| Dismiss the keyboard | Shell returns to full height; no blank band; `--app-height` gone from `<html>` |
| Scroll the thread with the keyboard up | The thread scrolls, the page does not |
| Rotate to landscape and back | Layout follows; no stuck height |
| Open the sidebar with the keyboard up | Overlay covers the visible area, not a phantom area under the keyboard |
| Android Chrome (any) | Unchanged from before this branch |

- **Step 3: STATUS.md entry.** Append under a heading `## iOS keyboard: a visual-viewport fallback for the composer (2026-MM-DD)`: the Task 1 observation (iOS version, what was wrong), the decision (fallback, not accept — FPL's audience is phone-first and a composer under the keyboard is the first thing an iPhone tester meets), the mechanism in two sentences, and the checklist results. Note that `frontend` now has a test runner and one test.

- **Step 4: Open the PR.** Body ≤ 8 lines per `AGENTS.md`: what changed, why (WebKit 259770), how verified (device + checklist), link to this plan and KAR-22.

- **Step 5: Linear.** Comment on KAR-22 with the device result and the PR link; move to Done after merge.

**Done when:** every checklist row passes on the device, the PR is merged, KAR-22 is Done.

---

## Self-review notes

- Spec coverage: the ticket asks for a decision (Task 1) and, if fallback, an implementation (Tasks 2–3) — both covered; "accept" is the Task 1 exit.
- The hook is deliberately untested in isolation: it is event wiring around a tested function, and a jsdom harness for `visualViewport` would test the harness. The device checklist is the test.
- Type consistency: `keyboardAwareHeight(ViewportMetrics, number): number | null` is used identically in the test and the hook.
