# Frontend Design System Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-15-frontend-design-system.md` — read it fully first. Its
palette values, contrast levels, spacing scale, breakpoint and layout are binding. Every hex value
in this plan is copied from it verbatim.

**PROGRESS:** Task 6/6 done

**Goal:** Replace the undesigned Mantine-default chat UI with the specified design system, so that
Phase 5's new screens are built once, into a finished system, rather than styled twice.

**Architecture:** CSS custom properties carry the palette, spacing and type scale; Mantine's theme
maps onto those same properties so Mantine components and hand-written CSS Modules cannot drift
apart. Fonts are self-hosted via `@fontsource` packages — no CDN request at runtime, which also
keeps the Fly deployment self-contained. Message bubbles are removed entirely; the thread becomes
an attribution label plus a hairline rule plus prose.

**Tech stack:** Vite 8 + React 19 + TypeScript 6 + Mantine v9 + CSS Modules. No Tailwind. This is
unchanged from Phase 1 and must stay unchanged.

Design altitude, as Phases 2–4: each task states what it must achieve and how you know it is done.
Component internals are yours within the spec's constraints.

## Global constraints

- **No Tailwind, no CSS-in-JS.** CSS Modules plus custom properties, per `AGENTS.md`.
- **Mantine v9 requires React ≥19.2.** Do not downgrade React to satisfy any library.
- **Every colour comes from a token.** No literal hex outside `tokens.css`. A hard-coded colour is
  how the contrast guarantees rot.
- **Spacing scale is `4 8 12 16 24 32 48`.** No other values. If a design needs 20px, it needs 16
  or 24.
- **One breakpoint: 640px.** Below it, full bleed with a 16px gutter. At and above, a centred
  660px column.
- **Contrast levels are contractual**, not aspirational. Task 1 adds a check that fails the build
  if a token pairing drops below its stated level.
- Frontend commands run from `frontend/`; backend from `backend/`. See `docs/DEVELOPMENT.md`.
- Commit per task; update `docs/STATUS.md` after every task.

---

### Task 1 — Tokens, fonts, and the contrast guard

Create `frontend/src/styles/tokens.css` holding the full palette as custom properties, both
themes, plus the spacing scale. Light values, from the spec:

```
--paper #F7F9F7   --ink #171E2E    --muted #414D66
--rule-row #C4CBD8   --rule-section #8792A8
--pitch #18811C   --card #F0D04C   --redcard #9A261D
--flood #1D819A   --kit #9A531D
```

Dark: ground `#10151F`, body `#E9ECF2`, pitch `#8DEC90`, card `#F0D04C`, redcard `#EC938D`.

Dark mode is selected by `prefers-color-scheme` and overridden by a `data-theme` attribute on the
root, so Task 5's toggle can win in both directions. Define the complete light palette on bare
`:root`; redefine only what changes under the media query and under `[data-theme="dark"]`.

Install `@fontsource-variable/archivo` and `@fontsource-variable/newsreader`, import the Latin
subsets, and declare the two roles as `--font-reading` (Newsreader) and `--font-ui` (Archivo).
Wire Mantine's theme (`frontend/src/theme.ts`, passed to `MantineProvider`) so its colours and
fonts resolve to these same custom properties — Mantine components must not carry a second,
divergent palette.

Add `frontend/scripts/check-contrast.mjs`: parse `tokens.css`, compute WCAG contrast for the
pairings the spec tabulates, and exit non-zero if any falls below its stated level. Wire it as an
npm script and run it in the build. The spec's numbers are the expected values — body text on
paper 15.73, muted 8.01, pitch as text 4.73, white on pitch 5.01, dark body 15.44.

**Done when:** the token file defines every colour the spec lists in both themes; no component yet
uses them; `npm run check-contrast` passes; and deliberately darkening `--paper` by a few percent
makes it fail (verify this by trying it, then reverting — a guard that has never failed is not
known to work).

### Task 2 — Layout shell and the mobile fixes

Restructure `ChatView.module.css` and `frontend/index.html` to the spec's layout.

- Root: flex column, `min-height: 100dvh`. Below 640px full bleed with a 16px gutter; at and above,
  a centred 660px column.
- Header: 48px tall on mobile, 56px above the breakpoint, product name set in `--font-ui`.
- Thread region scrolls; `scroll-padding-bottom` keeps the last message clear of the composer.

The four things the current build lacks entirely, all specified in the spec's *Mobile* section:

1. `index.html` viewport meta becomes
   `width=device-width, initial-scale=1.0, viewport-fit=cover, interactive-widget=resizes-content`.
2. Safe-area insets reach the composer (Task 4 applies them; the shell must not clip them).
3. Media queries exist at all — the single 640px breakpoint.
4. `scroll-padding-bottom` on the scrolling thread.

**Done when:** at 320, 375, 640 and 1024px the page has no horizontal scrollbar, the gutter is 16px
below the breakpoint, the column is centred and 660px wide above it, and the iOS keyboard resizes
the layout rather than covering the composer. Verify in headless Chromium at each width — the
Phase 1 Task 13 verification used Puppeteer against the real backend through the Vite proxy, and
this is the same setup.

### Task 3 — The transcript thread

Rewrite `MessageThread` and `AssistantMessage` to the spec's signature form. **Remove the bubbles
entirely** — this is the change that recovers 32–50% of text width on a phone, and it is not
achieved by shrinking padding.

- Each turn is an attribution label (`--font-ui`, small caps, letterspaced) plus a hairline rule
  plus the message body. The user's label is `--muted`; the assistant's is `--pitch`.
- Assistant prose renders in `--font-reading` at 18px with a line height around 1.65; the user's
  message renders in `--font-ui`.
- Turns are separated by 24px of vertical rhythm. No background colours, no borders, no radii, no
  shadows on either role.
- The provenance line every stats tool emits gets its own muted treatment, so a completed-season
  caveat is visibly an aside rather than body text.

Add `remark-gfm` to the `react-markdown` pipeline. The spec records this as a known adjacent
defect: without it, a GFM table the model emits unprompted renders as literal pipe characters.
Style the resulting `table`, `th` and `td` with the two rule tokens — `--rule-row` between rows,
`--rule-section` under the header.

Delete `--mantine-color-blue-6` and every other Mantine default colour from these files.

**Done when:** an assistant answer containing a markdown table, a list and inline emphasis renders
correctly in both themes; the measured text width at 375px is 343px rather than 259px; and no
message has a background colour.

### Task 4 — Composer

Restyle `MessageInput`. Sticky to the bottom of the shell, `--paper` background, top rule in
`--rule-section`, `padding-bottom: max(12px, env(safe-area-inset-bottom))`.

The textarea grows with its content to a maximum of roughly five rows and then scrolls internally.
The send button uses `--pitch` with a white label — the pairing the spec measures at 5.01, which is
why the button colour is `#18811C` and not the lighter green that fails.

Preserve the IME composition guard from Phase 1 Task 10: Enter must not submit mid-composition.
That was a review finding, and restyling is exactly where such a guard gets dropped.

**Done when:** the composer sits above the iOS home indicator, grows and stops at five rows,
submits on Enter but never during IME composition, and the send button's focus ring is visible
under keyboard navigation.

### Task 5 — Theme toggle and the quality floor

Add a theme toggle to the header. It writes `data-theme` on the root and persists the choice; with
no stored choice, `prefers-color-scheme` decides. Both directions must work — a user on a dark OS
must be able to force light.

Then the spec's quality floor, applied across every component built above:

- Responsive down to 320px.
- A visible focus ring on every interactive element, in `--pitch`, never `outline: none` without a
  replacement.
- `prefers-reduced-motion` respected. The streaming cursor is the only animation in the app; under
  reduced motion it stops blinking and stays solid.

**Done when:** the toggle survives a reload in both directions, every interactive element is
reachable and visibly focused by keyboard alone, and the cursor stops animating under
`prefers-reduced-motion: reduce`.

### Task 6 — Verification, deploy, and documentation

Verify against the real backend, not a mock, at 375px and 1024px in headless Chromium:

- A question that returns prose renders as a transcript, in the reading face, with the provenance
  line visibly an aside.
- A question that returns a markdown table renders a real table.
- Both themes, no console errors, no horizontal scrollbar at either width.
- `npm run check-contrast` passes.

Build the Docker image and confirm the fonts are inside it — `.dockerignore` has caught this class
of problem before, where root-anchored `docs`/`*.md` patterns would have shipped an image with an
empty strategy corpus. Fonts arriving from `node_modules` through the Vite build are a new path and
have never been verified in the image.

Deploy with `fly deploy --ha=false --remote-only` and update `docs/STATUS.md` with what exists and
any deviations.

**Done when:** the deployed application serves the redesigned UI, both themes work, and the
documentation matches what shipped.

> **Review checkpoint.** Request a full review before considering this plan complete.

## Pre-implementation verification

Confirm before starting, because each would change a task:

- `@fontsource-variable/archivo` and `@fontsource-variable/newsreader` both publish a variable
  Latin subset. If either ships only static weights, Task 1's single-file assumption is wrong and
  the weights must be enumerated.
- Mantine v9's theme accepts CSS custom properties as colour values. If it insists on literal
  colours, Task 1 needs a generated bridge rather than direct references.
- `remark-gfm`'s current major version is compatible with the installed `react-markdown` 10.
