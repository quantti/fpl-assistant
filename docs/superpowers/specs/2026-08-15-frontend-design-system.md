# FPL Assistant — Frontend Design System Spec

**Status:** Approved — design agreed 2026-08-15, not yet implemented.

**Scope:** The visual identity and layout system for the existing chat frontend, sized so that
Phase 5 (auth, billing, server-side history) can add screens without a second design round.
This spec covers *how the frontend looks and is structured*. It does not change any backend
behaviour.

**Relationship to other docs:** `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`
established the frontend stack (Vite + React 19 + TypeScript + Mantine v9 + CSS Modules, no
Tailwind). That stack is unchanged. Where this spec differs from the Phase 1 plan's component
CSS, this spec wins.

---

## Why

The frontend shipped in Phase 1 Tasks 9–13 is functional and undesigned: Mantine defaults, a
generic blue message bubble, one breakpoint's worth of padding used at every size, and no visual
identity of any kind. Two things force the issue now:

1. **Phase 5 adds screens.** Login, subscribe, and conversation history all need somewhere to
   live. Designing them one at a time produces drift; designing the system once does not.
2. **Mobile is actively bad.** Measured below — roughly a third of a phone's screen width is
   spent on chrome that carries no information.

## What is actually on screen

The design follows from what the app really renders, which is **prose, not data**.

Verified 2026-08-15:

- `system-prompt.st` contains no instruction to produce tables. The model answers in
  natural language.
- Tool output (`GW1: vs MUN (A) | FDR 4`) is consumed by the *model*, never displayed. Stats
  support the answer; they are not the answer.
- `AssistantMessage` renders with `react-markdown` **without `remark-gfm`**, so GFM tables do
  not render as tables at all — a pipe table would appear as a run of literal text.

So the interface is a single column of readable prose, occasionally a list, plus a composer.
Everything else is chrome and should be minimal.

> An earlier draft of this design was built around a "matchday teamsheet" concept — ruled data
> rows, condensed headers, squad-number numerals. It was discarded: it solved for table-dense
> output the app does not produce. Recorded here so it is not proposed again.

## Palette

Two primaries — **pitch green** and **card yellow** — with the remaining colours placed on the
arc those two define, plus one cool counterweight and a navy-tinted neutral. Seven colours.
Every value below was generated from a hue/lightness ramp and its contrast measured, not
picked by eye.

### Light

| Role | Hex | Contrast on paper | Level |
|---|---|---|---|
| Paper (background) | `#F7F9F7` | — | — |
| Ink (body text) | `#171E2E` | 15.73 | AAA |
| Muted text | `#414D66` | 8.01 | AAA |
| Rule — row | `#C4CBD8` | 1.54 | decorative |
| Rule — section | `#8792A8` | 2.96 | boundary |
| **Pitch** — links, primary action | `#18811C` | 4.73 | AA |
| Pitch, white label on it | `#FFFFFF` | 5.01 | AA |
| **Card** — caution, highlight | `#F0D04C` | — (fill) | — |
| Card, ink label on it | `#171E2E` | 10.96 | AAA |
| **Red card** — danger | `#9A261D` | 7.44 | AAA |
| Red card, white label on it | `#FFFFFF` | 7.87 | AAA |
| **Floodlight** — info | `#1D819A` | 4.26 | large/UI only |
| **Kit** — reserved data series | `#9A531D` | 5.48 | AA |

### Dark

| Role | Hex | Contrast on `#10151F` | Level |
|---|---|---|---|
| Ground | `#10151F` | — | — |
| Body text | `#E9ECF2` | 15.44 | AAA |
| Pitch — links | `#8DEC90` | 12.65 | AAA |
| Card — caution | `#F0D04C` | 12.04 | AAA |
| Red card — danger | `#EC938D` | 7.94 | AAA |

### Three corrections the measurements forced

These are recorded because each looks like a cosmetic tweak and is not:

- **Pitch darkened from `#1D9A22` to `#18811C`.** The original measured 3.49 against paper —
  enough for large text only, failing for body-size links and for a white button label. The
  difference is invisible by eye and decisive for accessibility.
- **Card yellow lightened from `#DAB82F` to `#F0D04C`.** The original sat at *exactly the same
  luminance* as pitch green (ratio 1.03), so the two primaries were indistinguishable in
  greyscale and to some colourblind users. The new value separates them at 2.19.
- **A lime bridge colour was dropped.** It sat between the two primaries and did nothing
  neither already did. Eight colours became seven.

Two honest limits: row rules at 1.54 do not meet 3:1, which WCAG does not require of decorative
separators — section boundaries use the stronger `#8792A8`. Floodlight at 4.26 is 0.24 short of
body text and is therefore for icons and large text, or must be darkened before any body use.

### Rejected: the official FPL palette

The Premier League palette (`#38003C` aubergine, `#00FF85` mint, `#04F5FF`, `#E90052`) was
considered and rejected by the owner. Two reasons beyond taste: the aubergine-plus-acid-green
combination is one of the three looks generative design currently defaults to, so the
brand-faithful choice and the generic choice were the same choice; and a Stripe-billed product
using the league's exact palette invites the reading that it is an official product.

## Typography

Deliberately the least load-bearing decision here — the families can be swapped later without
touching the layout system.

| Role | Family | Used for |
|---|---|---|
| Reading | Newsreader (Production Type, OFL) | Assistant answers, future articles |
| Interface | Archivo (Omnibus-Type, OFL, variable wght + wdth) | User messages, labels, chrome, buttons |

Both verified available under the Open Font License and self-hostable as subset `woff2`; no CDN
request at runtime. Newsreader ships tabular numerals and optical sizes and was commissioned for
"continuous on-screen reading in content-rich environments". Archivo's width axis supplies a
condensed cut for labels from the same file.

The split is semantic rather than decorative: the assistant *writes* considered analysis, the
user *types* a quick question. It also removes the need for bubbles or background colours to
tell the two apart.

Reading measure is **~66 characters (≈660px)**. The current 800px column runs past 90
characters per line at 18px, which is tiring in a serif.

## Layout

```
Spacing scale:  4  8  12  16  24  32  48        (no other values)

Breakpoint:     one, at 640px
  < 640px       full bleed, 16px gutter
  ≥ 640px       centred 660px column
```

```
MOBILE 375px                     DESKTOP >=640px
+-----------------------+        +----------------------------------+
| FPL ASSISTANT     (*) | 48px   |      FPL ASSISTANT         (*)   | 56px
+-----------------------+        +----------------------------------+
|                       |        |                                  |
| YOU  ------------     |        |        YOU  --------------       |
| Should I keep Salah?  |        |        Should I keep Salah?      |
|                       | 24px   |                                  |
| ASSISTANT  -------    | gap    |        ASSISTANT  ---------      |
| Salah's xG over the   |        |        Salah's xG over the...    |
| last five is 2.41...  |        |                                  |
|                       |        |        <-- 660px column -->      |
+-----------------------+        +----------------------------------+
| Ask something...  [>] |        |      Ask something...      [>]   |
+-----------------------+        +----------------------------------+
```

### The signature

The conversation is set as a **printed interview**: a small-caps attribution in the interface
face, a hairline rule, then the answer in the reading face. This is how football magazines have
set manager interviews for decades — it comes from the subject's own world, it falls out of the
type decision rather than being applied on top of it, and it is the opposite of the bubbles
every other chat interface uses.

Green earns its place semantically, not decoratively: the assistant's attribution, links, the
send button, focus rings. Yellow appears only on `DOUBTFUL` player flags, red only on errors.

The provenance line every stats tool emits ("2025/26, a completed season") gets its own muted
style. It is a project-wide discipline, so it deserves a role in the design rather than being
indistinguishable from body text.

### What is deliberately absent

No bubbles, no avatars, no cards, no shadows, and no borders beyond the attribution hairline and
the two horizontal rules. Vertical rhythm and the small-caps label carry the separation.

## Mobile

The current build wastes a third of a phone screen. Measured at 375px:

| | Now | After |
|---|---|---|
| Viewport | 375px | 375px |
| − thread padding 16×2 | 343px | 343px |
| − bubble `max-width: 85%` | 291px | — |
| − bubble padding 16×2 | **259px** | **343px** |

Removing bubbles alone yields **+32% text per line** for assistant messages and **+50%** for
user messages (`max-width: 75%` plus padding, currently 229px), without adding anything.

Four things the current build lacks entirely:

1. **The viewport meta is incomplete.** It is `width=device-width, initial-scale=1.0`. It needs
   `viewport-fit=cover` for safe areas and `interactive-widget=resizes-content` so the iOS
   keyboard resizes the layout instead of covering the composer.
2. **No safe-area insets.** The composer needs
   `padding-bottom: max(12px, env(safe-area-inset-bottom))` or the iPhone home indicator sits
   on top of it.
3. **No media queries at all.** Desktop padding is used at every size.
4. **No `scroll-padding-bottom` on the thread**, so the last message can sit under the composer.

## Dark mode

Follows from the palette. Ground `#10151F`, body `#E9ECF2`. Respects `prefers-color-scheme`
with an explicit toggle overriding it, and the toggle's choice persists.

## Quality floor

Responsive to 320px, visible keyboard focus on every interactive element, `prefers-reduced-motion`
respected (the streaming cursor is the only animation), and every colour pairing above at its
stated WCAG level.

## Amendment 2026-08-15 — Phase 5 adds a conversation list

Written after this spec was approved, during Phase 5 design
(`2026-08-15-phase5-auth-billing-design.md`). Recorded here rather than silently left stale.

The owner chose **multiple conversations with a list**, not the single continuous conversation
this spec was drawn around. The layout above therefore describes the *thread* correctly but is
incomplete: there is a navigation surface it does not cover.

What holds unchanged: the palette, the type roles, the spacing scale, the 640px breakpoint, the
reading measure, and the no-bubbles thread. What needs designing when Phase 5 starts:

- **Desktop** — a list beside the 660px column. The reading measure must survive it; the list
  takes its width from the viewport, not from the column.
- **Mobile** — a drawer, since a permanent sidebar would spend the width this spec just spent
  three sections recovering. It must not reintroduce the chrome the bubble removal eliminated.
- The list's own typography (interface face, truncated titles) and its empty state.

## Out of scope

- Backend behaviour, tools, prompts, and RAG — untouched.
- Auth, billing, and history screens themselves — Phase 5. This spec sizes the system so they
  fit; it does not design them. See the amendment above for the one place that sizing turned out
  to be incomplete.
- Charts. The palette reserves two data-series colours (floodlight, kit) against the day price
  history or xG is plotted, but nothing is built now.
- A logo or wordmark beyond setting the product name in the interface face.

## Known adjacent defect

`react-markdown` is used without `remark-gfm`. Tables, strikethrough, and autolinks therefore do
not render. This is not a design decision and should be fixed alongside the work, since the model
can emit a GFM table unprompted and it currently degrades to literal pipe characters.

## Open

Nothing blocking. Typography is explicitly changeable without disturbing the layout system, and
the owner has said as much.
