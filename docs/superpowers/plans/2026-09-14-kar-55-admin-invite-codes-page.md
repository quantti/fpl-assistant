# KAR-55 — Admin page for invite codes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is the single `**PROGRESS:**` line below, updated once per task — no per-step checkboxes.

**PROGRESS:** Task 0/4 done.

Issue: [KAR-55](https://linear.app/quantti/issue/KAR-55/admin-page-for-invite-codes).
Baseline: `origin/master` at `ad86657` (KAR-54 merged). Branch `kar-55-admin-page`, worktree
`.claude/worktrees/invite-code-limits`.

**Goal:** An `/admin` page in the frontend that operates the KAR-54 invite-code API — see places taken, list codes with their server-computed state, disable/enable, move a ceiling, read redemptions, release burnt places, and issue a new code — so none of that is `curl` or SQL any more.

**Architecture:** One new route in the hand-written router, one `useProfile` hook read once per signed-in shell so `AppMenu` can show an **Admin** item, and one `AdminPage` component tree (`AdminPage` → `CodeDetails` per expanded row, `NewCodeForm` below the table) over a pure `inviteCodes.ts` module holding the wire types and the display/body helpers. Every mutation re-renders from the row the server returns; nothing in the browser computes a code's state. The API decides access: a 403 on the list renders one sentence and no table.

**Tech Stack:** Vite 8 + React 19 + TypeScript (strict) + Mantine v9 (`Button`, `TextInput`, `NativeSelect`, `Loader`) + CSS Modules; vitest 4 with jsdom for component tests, plain node env for pure helpers. `apiFetch` for every request.

**Spec:** the Linear issue KAR-55 (its description is the spec; there is no separate file). The design voice is `docs/superpowers/specs/2026-08-15-frontend-design-system.md`.

## Global Constraints

- Design system: interface face (`--font-ui`), small-caps section labels through the shared `typography.label` class in `src/styles/typography.module.css`, hairline rules (`1px solid var(--rule-row)` / `var(--rule-section)`), **no cards, no shadows, no literal hex colours** — tokens only. Green (`--pitch`) only on actions and focus, red (`--redcard`) only on errors. `npm run build` runs `check-contrast` and must pass.
- The page lives in the existing 660px column (`ChatView`'s `.root`); under 640px the codes table becomes one stacked block per code and the page never scrolls horizontally.
- Every request goes through `apiFetch`. Every mutation replaces the affected row with the `CodeView` the server returns — never optimistic local state.
- State words, exactly: `OPEN` → `Open`, `DISABLED` → `Disabled`, `EXHAUSTED` → `Used up`, `EXPIRED` → `Expired`, `NOT_YET_VALID` → `Not yet valid`.
- Not-available wording, exactly: `This page is not available for this account.` — used for any 403, whether the admin set is empty or the user is not in it. Nothing in the browser decides who is an admin; the menu item is a convenience.
- 401 is never reported (`apiFetch` has already ended the session).
- Copy from the spec, verbatim: *Nobody has used this code yet.* · *A code with that name already exists.* · *That could not be saved. Nothing was changed — try again shortly.* · *Release burnt places* · *Released 1 place. Reconciled: testi.*
- Tests: the `HeaderNavigation.test.ts` shape — `// @vitest-environment jsdom`, `createRoot` + `act`, `vi.mock` for `apiFetch`, `router` and `@mantine/core`. No testing-library. Existing tests stay green; `HeaderNavigation.test.ts` asserts `APP_MENU_LINKS` is exactly `['Usage', 'Settings']`, so the Admin item is **not** added to that array.
- Out of scope (do not build): editing label/note/validity of an existing code, deleting codes, per-redemption emails, any other admin function, an admin layout or navigation beyond the one menu item.
- Commit subjects: one line, imperative, ~60 chars. Attribution trailer from the session reminder on every commit. Run `npm test` from `frontend/` before each commit; `npm run build` before the last one.

## Rulings made while planning (spec silent or ambiguous)

- **Dates in the new-code form are the admin's local days.** `validFrom` is sent as the local start of that day, `validUntil` as the local end of that day (`23:59:59`), both converted to ISO instants. Cost if wrong: a code expires one time-zone offset early or late; the API accepts any instant so nothing breaks.
- **The places line always carries the burnt count**, `0 burnt` included — one shape, and the zero is information. Cost if wrong: one redundant word.
- **Rows muted: `DISABLED` and `EXHAUSTED` only**, as the spec lists them. Expired and not-yet-valid rows stay in ink.
- **`SettingsPage` is untouched**; it keeps its inline profile type and its own fetch. `Profile` is added to `types.ts` for the hook and the menu.
- **After `release-burnt` the list is re-fetched** — the release response carries no counts, and the spec says the line updates.
- **Redemptions are sorted newest-first in the browser** whatever order the server sends, so the requirement does not depend on an SQL `ORDER BY` this plan cannot see.

## File structure

| File | Responsibility |
|---|---|
| `frontend/src/router.ts`, `router.test.ts` | `'/admin'` in `Route` and `routeFromPath`. |
| `frontend/src/types.ts` | `Profile { fplTeamId: number \| null; admin: boolean }`. |
| `frontend/src/auth/useProfile.ts` (new) | `GET /api/profile` once per mount; `Profile \| null`. |
| `frontend/src/components/AppMenu/AppMenu.tsx`, `AppMenu.test.ts` (new) | `admin` prop → conditional **Admin** item. |
| `frontend/src/components/ChatView/ChatView.tsx` | Reads `useProfile`, passes `admin` to `AppMenu`, renders `AdminPage` for `/admin`. |
| `frontend/src/components/AdminPage/inviteCodes.ts`, `inviteCodes.test.ts` (new) | Wire types, `STATE_WORDS`, `NOT_AVAILABLE`, `usedText`, `placesLine`, `benefitText`, `releaseNote`, `shortUserId`, `createBody`, `problemDetail`, `byNewest`. Pure; node-env tests. |
| `frontend/src/components/AdminPage/AdminPage.tsx`, `AdminPage.module.css`, `AdminPage.test.ts` (new) | The page: places line + release, codes table, expansion, form slot. |
| `frontend/src/components/AdminPage/CodeDetails.tsx` (new) | Expanded row: ceiling field + redemptions list. |
| `frontend/src/components/AdminPage/NewCodeForm.tsx` (new) | The form below the table. |
| `docs/STATUS.md`, `docs/DEVELOPMENT.md` | Entry for KAR-55; the page named beside the API. |

Wire shapes (from `InviteCodeAdminController`, serialised with `NON_NULL`, so optional numbers are **absent**, not null):

```
GET  /api/admin/invite-codes                 → { accountCap, placesTaken, burnt, codes: CodeView[] }
POST /api/admin/invite-codes                 → 201 CodeView | 400 {detail} | 409 {detail}
POST /api/admin/invite-codes/{id}/disable    → CodeView
POST /api/admin/invite-codes/{id}/enable     → CodeView
PUT  /api/admin/invite-codes/{id}/max-uses   { maxUses } → CodeView | 400 {detail} | 409 {detail}
DELETE /api/admin/invite-codes/{id}/max-uses → CodeView
GET  /api/admin/invite-codes/{id}/redemptions → [{ id, userId: string|null, redeemedAt }]
POST /api/admin/invite-codes/release-burnt   → { released, reconciled: string[] }
CodeView = { id, code, label, note?, maxUses?, uses, remaining?, accounts, burnt, lastRedeemedAt?,
             benefitKind: 'access'|'discount_percent', benefitValue?, active, validFrom?, validUntil?,
             state: 'OPEN'|'DISABLED'|'EXHAUSTED'|'NOT_YET_VALID'|'EXPIRED', createdAt }
```
Error bodies are Spring problem details (`spring.mvc.problemdetails.enabled: true`): JSON with a `detail` string. A 403 from the security filter may have an empty body.

---

### Task 1: Route, profile hook and the Admin menu item

**Files:**
- Modify: `frontend/src/router.ts`, `frontend/src/router.test.ts`
- Modify: `frontend/src/types.ts`
- Create: `frontend/src/auth/useProfile.ts`
- Modify: `frontend/src/components/AppMenu/AppMenu.tsx`
- Create: `frontend/src/components/AppMenu/AppMenu.test.ts`
- Modify: `frontend/src/components/ChatView/ChatView.tsx`

**Interfaces:**
- Produces: `Route` includes `'/admin'`; `Profile` in `types.ts`; `useProfile(): Profile | null`; `AppMenu` props `{ route: Route; admin: boolean }`.
- Task 2 adds the `/admin` branch in `ChatView`'s page switch; this task only wires the menu.

- [ ] **Step 1: Failing router test**

Append to `frontend/src/router.test.ts` inside the `describe`:

```ts
  it('recognizes the admin deep link', () => {
    expect(routeFromPath('/admin')).toBe('/admin')
  })
```

Run: `cd frontend && npx vitest run src/router.test.ts` → the new case FAILS (`'/'` received).

- [ ] **Step 2: Route**

In `frontend/src/router.ts`: `export type Route = '/' | '/my-team' | '/shortlist' | '/usage' | '/settings' | '/admin'` and add `|| path === '/admin'` to `routeFromPath`. Update the doc comment's "five top-level views" to "six". Run the router test → PASS.

- [ ] **Step 3: Profile type and hook**

Append to `frontend/src/types.ts`:

```ts
/**
 * What `GET /api/profile` answers. `admin` is for showing the Admin link; the API refuses a
 * non-admin regardless of what the page shows, so nothing in the browser decides access on it.
 */
export interface Profile {
  fplTeamId: number | null
  admin: boolean
}
```

Create `frontend/src/auth/useProfile.ts`:

```ts
import { useEffect, useState } from 'react'
import { apiFetch } from './apiFetch'
import { Profile } from '../types'

/**
 * The signed-in account's profile, read once per mount of the signed-in shell. Null until it
 * arrives and null if it cannot be read — the only thing that hangs on it is whether the Admin
 * item is shown, and an absent item costs an admin one typed URL, while a wrong one costs a
 * non-admin nothing (the API answers 403 and the page says so).
 */
export function useProfile(): Profile | null {
  const [profile, setProfile] = useState<Profile | null>(null)

  useEffect(() => {
    let cancelled = false
    apiFetch('/api/profile')
      .then(async response => {
        if (!response.ok) return
        const next: Profile = await response.json()
        if (!cancelled) setProfile(next)
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  return profile
}
```

- [ ] **Step 4: Failing AppMenu test**

Create `frontend/src/components/AppMenu/AppMenu.test.ts`:

```ts
// @vitest-environment jsdom

import React, { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'

vi.mock('../../auth/supabaseClient', () => ({ supabase: { auth: { signOut: vi.fn() } } }))
vi.mock('../../router', async importOriginal => {
  const actual = await importOriginal<typeof import('../../router')>()
  return { ...actual, navigate: vi.fn() }
})
// Mantine's Menu needs a MantineProvider and a portal; neither is what this test is about. The
// stand-ins render every item as a plain button so the dropdown's contents can be read directly.
vi.mock('@mantine/core', () => {
  const Menu = ({ children }: { children: React.ReactNode }) => React.createElement('div', null, children)
  Menu.Target = ({ children }: { children: React.ReactNode }) => React.createElement('div', null, children)
  Menu.Dropdown = ({ children }: { children: React.ReactNode }) => React.createElement('div', null, children)
  Menu.Item = ({ children, onClick }: { children: React.ReactNode; onClick?: () => void }) =>
    React.createElement('button', { type: 'button', onClick }, children)
  Menu.Divider = () => React.createElement('hr')
  return {
    Menu,
    Burger: () => React.createElement('button', { type: 'button' }, 'Menu'),
    useMantineColorScheme: () => ({ setColorScheme: vi.fn() }),
    useComputedColorScheme: () => 'light',
  }
})
import { navigate } from '../../router'
import AppMenu from './AppMenu'

let root: ReturnType<typeof createRoot> | undefined

beforeAll(() => {
  ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  root = undefined
  document.body.replaceChildren()
  vi.mocked(navigate).mockReset()
})

function mount(admin: boolean): HTMLDivElement {
  const host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  act(() => root!.render(React.createElement(AppMenu, { route: '/', admin })))
  return host
}

function labels(host: HTMLElement): string[] {
  return Array.from(host.querySelectorAll('button')).map(button => button.textContent ?? '')
}

it('offers Admin only to an admin, and it opens /admin', () => {
  const host = mount(true)
  expect(labels(host)).toContain('Admin')

  const admin = Array.from(host.querySelectorAll('button')).find(b => b.textContent === 'Admin')!
  act(() => admin.click())
  expect(vi.mocked(navigate).mock.calls).toEqual([['/admin']])
})

it('has no Admin item for everyone else', () => {
  const host = mount(false)
  expect(labels(host)).not.toContain('Admin')
})
```

Run: `npx vitest run src/components/AppMenu/AppMenu.test.ts` → FAILS (no Admin item; TypeScript complains about the `admin` prop — both expected).

- [ ] **Step 5: AppMenu**

In `AppMenu.tsx`: `interface Props { route: Route; admin: boolean }`, destructure `admin`, and after the `APP_MENU_LINKS.map(...)` block add:

```tsx
        {/* A convenience, not a gate: the API answers 403 to a non-admin whatever this shows,
            and the page says so. Kept out of APP_MENU_LINKS, which is the fixed list. */}
        {admin && <Menu.Item onClick={() => navigate('/admin')}>Admin</Menu.Item>}
```

Extend the component doc comment with one line: "Admin appears only when the profile says so."

- [ ] **Step 6: ChatView passes the flag**

In `ChatView.tsx`: `import { useProfile } from '../../auth/useProfile'`; inside the component `const profile = useProfile()`; render `<AppMenu route={route} admin={profile?.admin ?? false} />`. Do not add the `/admin` page branch yet (Task 2).

- [ ] **Step 7: Verify and commit**

Run: `cd frontend && npm test` → all green, including `HeaderNavigation.test.ts` (`APP_MENU_LINKS` unchanged). Run `npx tsc -b` → clean.

```bash
git add frontend/src/router.ts frontend/src/router.test.ts frontend/src/types.ts frontend/src/auth/useProfile.ts frontend/src/components/AppMenu frontend/src/components/ChatView/ChatView.tsx
git commit -m "Add the /admin route and the Admin menu item for admins"
```

---

### Task 2: The admin page — places, codes table, disable/enable

**Files:**
- Create: `frontend/src/components/AdminPage/inviteCodes.ts`, `inviteCodes.test.ts`
- Create: `frontend/src/components/AdminPage/AdminPage.tsx`, `AdminPage.module.css`, `AdminPage.test.ts`
- Modify: `frontend/src/components/ChatView/ChatView.tsx`

**Interfaces:**
- Produces: everything exported from `inviteCodes.ts` below; `AdminPage` default export (no props); in `AdminPage`, `replaceCode(view)` and `appendCode(view)` state updaters and an `expandedId` — Task 3 mounts `CodeDetails` in the expanded row, Task 4 mounts `NewCodeForm` below the table. Leave both slots as the comments below mark them.

- [ ] **Step 1: Failing helper tests**

Create `frontend/src/components/AdminPage/inviteCodes.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  byNewest, createBody, placesLine, releaseNote, shortUserId, STATE_WORDS, usedText,
} from './inviteCodes'

describe('invite-code display helpers', () => {
  it('names every server state with the page wording', () => {
    expect(STATE_WORDS).toEqual({
      OPEN: 'Open', DISABLED: 'Disabled', EXHAUSTED: 'Used up', EXPIRED: 'Expired',
      NOT_YET_VALID: 'Not yet valid',
    })
  })

  it('shows uses over the ceiling, or over infinity without one', () => {
    expect(usedText({ uses: 7, maxUses: 100 })).toBe('7 / 100')
    expect(usedText({ uses: 7 })).toBe('7 / ∞')
  })

  it('writes the places line', () => {
    expect(placesLine({ accountCap: 30, placesTaken: 12, burnt: 1 })).toBe('12 of 30 places taken · 1 burnt')
  })

  it('reports a release, its reconciliation, and a grace-period hold', () => {
    expect(releaseNote({ released: 1, reconciled: ['testi'] }, 0)).toBe('Released 1 place. Reconciled: testi.')
    expect(releaseNote({ released: 2, reconciled: [] }, 0)).toBe('Released 2 places.')
    expect(releaseNote({ released: 0, reconciled: [] }, 1)).toBe(
      'Nothing released: the remaining burnt places are younger than the grace period and may'
      + ' still be sign-ups in progress.',
    )
    expect(releaseNote({ released: 0, reconciled: [] }, 0)).toBe('Nothing to release.')
  })

  it('shortens a user id to eight characters', () => {
    expect(shortUserId('0f3a9c12-aaaa-bbbb-cccc-000000000000')).toBe('0f3a9c12')
  })

  it('orders redemptions newest first', () => {
    const older = { id: 1, userId: null, redeemedAt: '2026-09-01T10:00:00Z' }
    const newer = { id: 2, userId: 'u', redeemedAt: '2026-09-02T10:00:00Z' }
    expect([older, newer].sort(byNewest)).toEqual([newer, older])
  })
})

describe('createBody', () => {
  const blank = {
    code: 'podcast', label: '', maxUses: '', benefitKind: 'access' as const, benefitValue: '',
    validFrom: '', validUntil: '', note: '',
  }

  it('sends null for an empty ceiling and empty optional text', () => {
    expect(createBody(blank)).toEqual({
      code: 'podcast', label: null, maxUses: null, benefitKind: 'access', benefitValue: null,
      validFrom: null, validUntil: null, note: null,
    })
  })

  it('sends the discount percentage only for a discount code', () => {
    expect(createBody({ ...blank, benefitKind: 'discount_percent', benefitValue: '20' }))
      .toMatchObject({ benefitKind: 'discount_percent', benefitValue: 20 })
    expect(createBody({ ...blank, benefitValue: '20' })).toMatchObject({ benefitValue: null })
  })

  it('sends dates as the local start and end of the chosen days', () => {
    const body = createBody({ ...blank, maxUses: '50', validFrom: '2026-10-01', validUntil: '2026-10-31' })
    expect(body.maxUses).toBe(50)
    expect(new Date(body.validFrom!).getTime()).toBe(new Date('2026-10-01T00:00:00').getTime())
    expect(new Date(body.validUntil!).getTime()).toBe(new Date('2026-10-31T23:59:59').getTime())
  })
})
```

Run: `npx vitest run src/components/AdminPage/inviteCodes.test.ts` → FAILS (module missing).

- [ ] **Step 2: The helpers module**

Create `frontend/src/components/AdminPage/inviteCodes.ts`:

```ts
/**
 * The invite-code admin API as the page sees it, plus the pure wording helpers the page and the
 * form share. Optional numbers are absent on the wire (the server serialises NON_NULL), so they
 * are optional here rather than nullable — "no ceiling" is a missing field, not a zero.
 */

export type CodeState = 'OPEN' | 'DISABLED' | 'EXHAUSTED' | 'NOT_YET_VALID' | 'EXPIRED'
export type BenefitKind = 'access' | 'discount_percent'

export interface InviteCodeView {
  id: number
  code: string
  label: string
  note?: string
  maxUses?: number
  uses: number
  remaining?: number
  accounts: number
  burnt: number
  lastRedeemedAt?: string
  benefitKind: BenefitKind
  benefitValue?: number
  active: boolean
  validFrom?: string
  validUntil?: string
  state: CodeState
  createdAt: string
}

export interface InviteCodeList {
  accountCap: number
  placesTaken: number
  burnt: number
  codes: InviteCodeView[]
}

/** `userId` is null for a burnt place — a sign-up that reserved the place and never finished. */
export interface Redemption {
  id: number
  userId: string | null
  redeemedAt: string
}

export interface ReleaseResult {
  released: number
  reconciled: string[]
}

export const NOT_AVAILABLE = 'This page is not available for this account.'
export const SAVE_FAILED = 'That could not be saved. Nothing was changed — try again shortly.'

/** The server's state, in the page's words. Rendered from the response, never computed here. */
export const STATE_WORDS: Record<CodeState, string> = {
  OPEN: 'Open',
  DISABLED: 'Disabled',
  EXHAUSTED: 'Used up',
  EXPIRED: 'Expired',
  NOT_YET_VALID: 'Not yet valid',
}

export function usedText(code: Pick<InviteCodeView, 'uses' | 'maxUses'>): string {
  return `${code.uses} / ${code.maxUses === undefined ? '∞' : code.maxUses}`
}

export function placesLine(list: Pick<InviteCodeList, 'accountCap' | 'placesTaken' | 'burnt'>): string {
  return `${list.placesTaken} of ${list.accountCap} places taken · ${list.burnt} burnt`
}

export function benefitText(code: Pick<InviteCodeView, 'benefitKind' | 'benefitValue'>): string {
  return code.benefitKind === 'discount_percent' ? `${code.benefitValue}% off` : 'Access'
}

/** @param burntAfter the list's burnt count after the release, re-read from the server. */
export function releaseNote(result: ReleaseResult, burntAfter: number): string {
  if (result.released === 0) {
    return burntAfter > 0
      ? 'Nothing released: the remaining burnt places are younger than the grace period and may'
        + ' still be sign-ups in progress.'
      : 'Nothing to release.'
  }
  const places = `Released ${result.released} ${result.released === 1 ? 'place' : 'places'}.`
  return result.reconciled.length === 0 ? places : `${places} Reconciled: ${result.reconciled.join(', ')}.`
}

export function shortUserId(userId: string): string {
  return userId.slice(0, 8)
}

export function byNewest(a: Redemption, b: Redemption): number {
  return b.redeemedAt.localeCompare(a.redeemedAt)
}

/** The new-code form's fields as typed — strings, so an empty field is '' and not a NaN. */
export interface NewCodeFields {
  code: string
  label: string
  maxUses: string
  benefitKind: BenefitKind
  benefitValue: string
  validFrom: string
  validUntil: string
  note: string
}

export interface CreateBody {
  code: string
  label: string | null
  maxUses: number | null
  benefitKind: BenefitKind
  benefitValue: number | null
  validFrom: string | null
  validUntil: string | null
  note: string | null
}

/**
 * Dates are the admin's local days: a code valid until the 31st is valid through the 31st where
 * the admin sits, so the end of that day is what is sent. The server takes any instant.
 */
export function createBody(fields: NewCodeFields): CreateBody {
  const text = (value: string) => (value.trim() === '' ? null : value.trim())
  return {
    code: fields.code.trim(),
    label: text(fields.label),
    maxUses: fields.maxUses === '' ? null : Number(fields.maxUses),
    benefitKind: fields.benefitKind,
    benefitValue: fields.benefitKind === 'discount_percent' && fields.benefitValue !== ''
      ? Number(fields.benefitValue)
      : null,
    validFrom: fields.validFrom === '' ? null : new Date(`${fields.validFrom}T00:00:00`).toISOString(),
    validUntil: fields.validUntil === '' ? null : new Date(`${fields.validUntil}T23:59:59`).toISOString(),
    note: text(fields.note),
  }
}

/** The `detail` of a Spring problem-details body, or null when the body is not one. */
export async function problemDetail(response: Response): Promise<string | null> {
  try {
    const body: { detail?: unknown } = await response.json()
    return typeof body.detail === 'string' ? body.detail : null
  } catch {
    return null
  }
}
```

Run the helper tests → PASS.

- [ ] **Step 3: Failing page tests**

Create `frontend/src/components/AdminPage/AdminPage.test.ts`. The Mantine stand-ins forward `name`, `value`, `onChange`, `type`, `placeholder` so the form tests (Task 3, Task 4) can type into fields; write them now so later tasks only add cases.

```ts
// @vitest-environment jsdom

import React, { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }))
vi.mock('@mantine/core', () => {
  type ButtonProps = React.ComponentProps<'button'> & { loading?: boolean; variant?: string; size?: string }
  type InputProps = React.ComponentProps<'input'> & { label?: React.ReactNode; description?: React.ReactNode }
  type SelectProps = React.ComponentProps<'select'> & {
    label?: React.ReactNode
    data: { value: string; label: string }[]
  }
  return {
    Loader: () => React.createElement('span', null, 'Loading'),
    Button: ({ children, loading: _loading, variant: _variant, size: _size, ...rest }: ButtonProps) =>
      React.createElement('button', rest, children),
    TextInput: ({ label, description: _description, ...rest }: InputProps) =>
      React.createElement('label', null, label, React.createElement('input', rest)),
    NativeSelect: ({ label, data, ...rest }: SelectProps) =>
      React.createElement('label', null, label, React.createElement('select', rest,
        data.map(option => React.createElement('option', { key: option.value, value: option.value }, option.label)))),
  }
})
import { apiFetch } from '../../auth/apiFetch'
import AdminPage from './AdminPage'
import { InviteCodeList, InviteCodeView } from './inviteCodes'

const apiFetchMock = vi.mocked(apiFetch)
let root: ReturnType<typeof createRoot> | undefined
let host: HTMLDivElement

export const podcast: InviteCodeView = {
  id: 7, code: 'podcast', label: 'Podcast', maxUses: 100, uses: 7, remaining: 93, accounts: 7,
  burnt: 0, benefitKind: 'access', active: true, state: 'OPEN', createdAt: '2026-09-01T00:00:00Z',
}
export const spent: InviteCodeView = {
  id: 8, code: 'launch', label: 'Launch', maxUses: 5, uses: 5, remaining: 0, accounts: 4, burnt: 1,
  benefitKind: 'discount_percent', benefitValue: 20, active: true, state: 'EXHAUSTED',
  createdAt: '2026-08-01T00:00:00Z', validUntil: '2026-12-31T23:59:59Z',
}
export const list: InviteCodeList = { accountCap: 30, placesTaken: 12, burnt: 1, codes: [podcast, spent] }

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Routes each request by method and path; anything unrouted fails the test loudly. */
export function serve(routes: Record<string, () => Response>) {
  apiFetchMock.mockImplementation(async (path, init) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    const route = routes[key]
    if (!route) throw new Error(`unexpected request ${key}`)
    return route()
  })
}

export async function flush() {
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) })
}

export async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  await act(async () => { root!.render(React.createElement(AdminPage)) })
  await flush()
  return host
}

export function button(label: string): HTMLButtonElement | undefined {
  return Array.from(host.querySelectorAll('button')).find(b => b.textContent === label)
}

export function rowOf(code: string): HTMLTableRowElement {
  const row = Array.from(host.querySelectorAll('tbody tr')).find(tr => tr.textContent?.includes(code))
  if (!row) throw new Error(`no row for ${code}`)
  return row as HTMLTableRowElement
}

/** React only notices a value set through the element's own setter followed by an input event. */
export function type(name: string, value: string) {
  const field = host.querySelector<HTMLInputElement | HTMLSelectElement>(`[name="${name}"]`)
  if (!field) throw new Error(`no field ${name}`)
  const proto = field instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(field, value)
  act(() => { field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true })) })
}

export function calls(): string[] {
  return apiFetchMock.mock.calls.map(([path, init]) => `${init?.method ?? 'GET'} ${path}`)
}

export function bodyOf(key: string): unknown {
  const call = apiFetchMock.mock.calls.find(([path, init]) => `${init?.method ?? 'GET'} ${path}` === key)
  return JSON.parse(String(call![1]!.body))
}

beforeAll(() => {
  ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true
})

beforeEach(() => apiFetchMock.mockReset())
afterEach(() => {
  if (root) act(() => root!.unmount())
  root = undefined
  document.body.replaceChildren()
})

it('renders the places line and one row per code with the server state words', async () => {
  serve({ 'GET /api/admin/invite-codes': () => json(200, list) })
  await mount()

  expect(host.textContent).toContain('12 of 30 places taken · 1 burnt')
  const rows = host.querySelectorAll('tbody tr')
  expect(rows).toHaveLength(2)
  expect(rowOf('podcast').textContent).toContain('7 / 100')
  expect(rowOf('podcast').textContent).toContain('Open')
  expect(rowOf('launch').textContent).toContain('Used up')
  expect(rowOf('launch').textContent).toContain('(1 burnt)')
  expect(rowOf('launch').textContent).toContain('20% off')
  expect(rowOf('podcast').textContent).not.toContain('burnt')
})

it('disables through the API and takes the new state from the response', async () => {
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'POST /api/admin/invite-codes/7/disable': () => json(200, { ...podcast, active: false, state: 'DISABLED' }),
  })
  await mount()

  const disable = Array.from(rowOf('podcast').querySelectorAll('button')).find(b => b.textContent === 'Disable')!
  await act(async () => { disable.click() })
  await flush()

  expect(calls()).toContain('POST /api/admin/invite-codes/7/disable')
  expect(rowOf('podcast').textContent).toContain('Disabled')
  expect(Array.from(rowOf('podcast').querySelectorAll('button')).map(b => b.textContent)).toContain('Enable')
})

it('says the page is not available on a 403 and shows no table', async () => {
  serve({ 'GET /api/admin/invite-codes': () => new Response(null, { status: 403 }) })
  await mount()

  expect(host.textContent).toContain('This page is not available for this account.')
  expect(host.querySelector('table')).toBeNull()
})

it('offers Release burnt places only when something is burnt, and reports the result', async () => {
  serve({ 'GET /api/admin/invite-codes': () => json(200, { ...list, burnt: 0 }) })
  await mount()
  expect(button('Release burnt places')).toBeUndefined()
  act(() => root!.unmount())
  root = undefined
  document.body.replaceChildren()

  let released = false
  serve({
    'GET /api/admin/invite-codes': () => json(200, released ? { ...list, burnt: 0, placesTaken: 11 } : list),
    'POST /api/admin/invite-codes/release-burnt': () => { released = true; return json(200, { released: 1, reconciled: ['testi'] }) },
  })
  await mount()
  await act(async () => { button('Release burnt places')!.click() })
  await flush()

  expect(host.textContent).toContain('Released 1 place. Reconciled: testi.')
  expect(host.textContent).toContain('11 of 30 places taken · 0 burnt')
})
```

Run: `npx vitest run src/components/AdminPage/AdminPage.test.ts` → FAILS (component missing).

- [ ] **Step 4: The page**

Create `frontend/src/components/AdminPage/AdminPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Button, Loader } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import {
  InviteCodeList, InviteCodeView, NOT_AVAILABLE, ReleaseResult, STATE_WORDS, benefitText,
  placesLine, releaseNote, usedText,
} from './inviteCodes'

type Page =
  | { kind: 'loading' }
  | { kind: 'unavailable' }
  | { kind: 'failed' }
  | { kind: 'ready'; list: InviteCodeList }

type Notice = { kind: 'note' | 'error'; message: string } | null

const COLUMNS = 8

/**
 * Operating the invite codes: places taken, every code with the state the server computed for
 * it, disable/enable, and (Tasks 3 and 4) a code's ceiling and redemptions, and a form for a new
 * one.
 *
 * The API decides who may be here. The menu link is a convenience; a 403 on the list renders one
 * sentence and no table, the same sentence whether the admin set is empty or this account is not
 * in it. Every mutation re-renders from the row the server returns, because `state` is computed
 * there from the same rule sign-up refuses by and the page must not disagree with the gate.
 */
export default function AdminPage() {
  const [page, setPage] = useState<Page>({ kind: 'loading' })
  const [notice, setNotice] = useState<Notice>(null)
  const [busy, setBusy] = useState(false)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  async function load(): Promise<InviteCodeList | null> {
    const response = await apiFetch('/api/admin/invite-codes')
    if (response.status === 403) {
      setPage({ kind: 'unavailable' })
      return null
    }
    if (!response.ok) {
      setPage({ kind: 'failed' })
      return null
    }
    const list: InviteCodeList = await response.json()
    setPage({ kind: 'ready', list })
    return list
  }

  useEffect(() => {
    load().catch(() => setPage({ kind: 'failed' }))
  }, [])

  function replaceCode(view: InviteCodeView) {
    setPage(current => current.kind !== 'ready' ? current : {
      kind: 'ready',
      list: { ...current.list, codes: current.list.codes.map(code => (code.id === view.id ? view : code)) },
    })
  }

  // Task 4's form appends the created row here.
  function appendCode(view: InviteCodeView) {
    setPage(current => current.kind !== 'ready' ? current : {
      kind: 'ready',
      list: { ...current.list, codes: [...current.list.codes, view] },
    })
  }

  async function toggle(code: InviteCodeView) {
    setBusy(true)
    setNotice(null)
    try {
      const response = await apiFetch(
        `/api/admin/invite-codes/${code.id}/${code.active ? 'disable' : 'enable'}`,
        { method: 'POST' },
      )
      if (response.ok) {
        replaceCode(await response.json())
      } else if (response.status === 403) {
        setNotice({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setNotice({ kind: 'error', message: 'That change did not go through. The code is as it was — try again shortly.' })
      }
    } catch {
      setNotice({ kind: 'error', message: 'That change did not go through. The code is as it was — try again shortly.' })
    } finally {
      setBusy(false)
    }
  }

  async function releaseBurnt() {
    setBusy(true)
    setNotice(null)
    try {
      const response = await apiFetch('/api/admin/invite-codes/release-burnt', { method: 'POST' })
      if (response.ok) {
        const result: ReleaseResult = await response.json()
        // The release answers with what it did, not with the counts; the line is re-read.
        const list = await load()
        setNotice({ kind: 'note', message: releaseNote(result, list?.burnt ?? 0) })
      } else if (response.status === 403) {
        setNotice({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setNotice({ kind: 'error', message: 'Nothing was released — try again shortly.' })
      }
    } catch {
      setNotice({ kind: 'error', message: 'Nothing was released — try again shortly.' })
    } finally {
      setBusy(false)
    }
  }

  if (page.kind === 'loading') {
    return <div className={classes.page}><Loader /></div>
  }
  if (page.kind === 'unavailable') {
    return <div className={classes.page}><p className={classes.muted}>{NOT_AVAILABLE}</p></div>
  }
  if (page.kind === 'failed') {
    return <div className={classes.page}><p className={classes.error}>Invite codes could not be loaded.</p></div>
  }

  const { list } = page
  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Admin</h1>

      <section className={classes.section}>
        <h2 className={`${typography.label} ${classes.label}`}>Places</h2>
        <div className={classes.places}>
          <p className={classes.line}>{placesLine(list)}</p>
          {list.burnt > 0 && (
            <Button size="compact-sm" variant="subtle" onClick={releaseBurnt} disabled={busy}>
              Release burnt places
            </Button>
          )}
        </div>
      </section>

      <section className={classes.section}>
        <h2 className={`${typography.label} ${classes.label}`}>Codes</h2>
        <table className={classes.table}>
          <thead>
            <tr>
              <th>Code</th><th>Label</th><th>Used</th><th>Accounts</th><th>State</th>
              <th>Valid until</th><th>Benefit</th><th><span className={classes.visuallyHidden}>Action</span></th>
            </tr>
          </thead>
          <tbody>
            {list.codes.map(code => {
              const muted = code.state === 'DISABLED' || code.state === 'EXHAUSTED'
              const expanded = expandedId === code.id
              return (
                <CodeRowGroup key={code.id} code={code} muted={muted} expanded={expanded} busy={busy}
                  onToggleExpand={() => setExpandedId(expanded ? null : code.id)}
                  onToggleActive={() => toggle(code)}
                  onChange={replaceCode}
                />
              )
            })}
          </tbody>
        </table>
      </section>

      {notice && (
        <p className={notice.kind === 'error' ? classes.error : classes.muted}
           role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.message}
        </p>
      )}

      {/* Task 4: <NewCodeForm onCreated={appendCode} /> goes here. */}
      {void appendCode}
    </div>
  )
}

interface RowProps {
  code: InviteCodeView
  muted: boolean
  expanded: boolean
  busy: boolean
  onToggleExpand: () => void
  onToggleActive: () => void
  onChange: (view: InviteCodeView) => void
}

/**
 * One code as a row, and its details as a second row while expanded. The row itself is the
 * click target (the spec's "clicking a row"); the code cell holds a button so the same thing is
 * reachable from the keyboard, and its click bubbles to the row rather than toggling twice. The
 * action button stops propagation, or every disable would also expand.
 */
function CodeRowGroup({ code, muted, expanded, busy, onToggleExpand, onToggleActive, onChange }: RowProps) {
  return (
    <>
      <tr className={muted ? classes.mutedRow : undefined} onClick={onToggleExpand}>
        <td data-label="Code">
          <button type="button" className={classes.codeButton} aria-expanded={expanded}>{code.code}</button>
        </td>
        <td data-label="Label">{code.label}</td>
        <td data-label="Used">{usedText(code)}</td>
        <td data-label="Accounts">
          {code.accounts}{code.burnt > 0 && <span className={classes.muted}> ({code.burnt} burnt)</span>}
        </td>
        <td data-label="State">{STATE_WORDS[code.state]}</td>
        <td data-label="Valid until">
          {code.validUntil === undefined ? '—' : new Date(code.validUntil).toLocaleDateString()}
        </td>
        <td data-label="Benefit">{benefitText(code)}</td>
        <td data-label="Action" className={classes.actionCell}>
          <Button size="compact-sm" variant="subtle" disabled={busy}
            onClick={event => { event.stopPropagation(); onToggleActive() }}>
            {code.active ? 'Disable' : 'Enable'}
          </Button>
        </td>
      </tr>
      {expanded && (
        <tr className={classes.detailsRow}>
          <td colSpan={COLUMNS}>
            {/* Task 3: <CodeDetails code={code} onChange={onChange} /> replaces this. */}
            {void onChange}
          </td>
        </tr>
      )}
    </>
  )
}
```

The two `{void …}` expressions keep `appendCode` and `onChange` referenced so `tsc` does not reject the slots as unused; Tasks 3 and 4 remove them.

- [ ] **Step 5: The stylesheet**

Create `frontend/src/components/AdminPage/AdminPage.module.css`:

```css
.page {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-24);
  padding: var(--space-32) var(--space-16);
  font-family: var(--font-ui);
  color: var(--ink);
  overflow-y: auto;
}

.title {
  margin: 0;
  font-size: var(--text-lg);
}

.section {
  display: flex;
  flex-direction: column;
  gap: var(--space-12);
}

/* typography.label sets no colour on purpose (see that file); a section label here is chrome. */
.label {
  margin: 0;
  color: var(--muted);
}

.places {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-8) var(--space-16);
}

.line,
.muted,
.error {
  margin: 0;
  font-size: var(--text-sm);
}

.muted {
  color: var(--muted);
}

.error {
  color: var(--redcard);
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.table th {
  text-align: left;
  font-weight: 600;
  color: var(--muted);
  padding: var(--space-4) var(--space-8) var(--space-8) 0;
  border-bottom: 1px solid var(--rule-section);
}

.table td {
  padding: var(--space-8) var(--space-8) var(--space-8) 0;
  border-bottom: 1px solid var(--rule-row);
  vertical-align: top;
}

.table tbody tr:not(.detailsRow) {
  cursor: pointer;
}

.mutedRow {
  color: var(--muted);
}

.codeButton {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  color: inherit;
  cursor: pointer;
}

.codeButton:focus-visible {
  outline: 2px solid var(--pitch);
  outline-offset: 2px;
}

.actionCell {
  text-align: right;
  white-space: nowrap;
}

.detailsRow td {
  padding-block: var(--space-16);
}

.visuallyHidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

/* One stacked block per code: the label line first, then key/value lines, so the column never
   scrolls sideways. The header row is what the data-labels replace. */
@media (max-width: 639px) {
  .table thead {
    display: none;
  }

  .table,
  .table tbody,
  .table tr,
  .table td {
    display: block;
  }

  .table tr {
    padding-block: var(--space-12);
    border-bottom: 1px solid var(--rule-row);
  }

  .table td {
    display: flex;
    justify-content: space-between;
    gap: var(--space-16);
    padding: var(--space-4) 0;
    border-bottom: 0;
  }

  .table td::before {
    content: attr(data-label);
    color: var(--muted);
  }

  .detailsRow td::before {
    content: none;
  }

  .actionCell {
    justify-content: flex-end;
  }

  .actionCell::before {
    content: none;
  }
}
```

- [ ] **Step 6: Route it**

In `ChatView.tsx`: `import AdminPage from '../AdminPage/AdminPage'` and add `: route === '/admin' ? (<AdminPage />)` to the page switch, after the `/settings` branch.

- [ ] **Step 7: Verify and commit**

Run: `cd frontend && npm test` → all green. `npx tsc -b` → clean. Check the four AdminPage cases in particular; if the release test's second mount races, the `flush()` after the click is where to look (two awaited fetches: release, then list).

```bash
git add frontend/src/components/AdminPage frontend/src/components/ChatView/ChatView.tsx
git commit -m "Add the admin page: places, codes table, disable and enable"
```

---

### Task 3: Expanded row — ceiling and redemptions

**Files:**
- Create: `frontend/src/components/AdminPage/CodeDetails.tsx`
- Modify: `frontend/src/components/AdminPage/AdminPage.tsx` (mount `CodeDetails` in the details row, drop the `{void onChange}` placeholder)
- Modify: `frontend/src/components/AdminPage/AdminPage.test.ts` (three cases)

**Interfaces:**
- Consumes: `InviteCodeView`, `Redemption`, `NOT_AVAILABLE`, `SAVE_FAILED`, `byNewest`, `shortUserId`, `problemDetail` from `inviteCodes.ts`; `replaceCode` arrives as the `onChange` prop.
- Produces: `CodeDetails` default export, props `{ code: InviteCodeView; onChange: (view: InviteCodeView) => void }`.

- [ ] **Step 1: Failing tests**

Append to `AdminPage.test.ts`:

```ts
it('expands a row to its redemptions, newest first, burnt places named as such', async () => {
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'GET /api/admin/invite-codes/8/redemptions': () => json(200, [
      { id: 1, userId: null, redeemedAt: '2026-09-01T10:00:00Z' },
      { id: 2, userId: '0f3a9c12-aaaa-bbbb-cccc-000000000000', redeemedAt: '2026-09-02T10:00:00Z' },
    ]),
  })
  await mount()
  await act(async () => { rowOf('launch').click() })
  await flush()

  const items = Array.from(host.querySelectorAll('li'))
  expect(items).toHaveLength(2)
  expect(items[0].textContent).toContain('0f3a9c12')
  expect(items[0].querySelector('[title]')?.getAttribute('title')).toBe('0f3a9c12-aaaa-bbbb-cccc-000000000000')
  expect(items[1].textContent).toContain('burnt')
})

it('says so when nobody has used a code', async () => {
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'GET /api/admin/invite-codes/7/redemptions': () => json(200, []),
  })
  await mount()
  await act(async () => { rowOf('podcast').click() })
  await flush()
  expect(host.textContent).toContain('Nobody has used this code yet.')
})

it('saves a ceiling from the response and shows the 409 detail when refused', async () => {
  let refuse = true
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'GET /api/admin/invite-codes/7/redemptions': () => json(200, []),
    'PUT /api/admin/invite-codes/7/max-uses': () => refuse
      ? json(409, { status: 409, detail: 'The ceiling cannot go below the 7 uses already spent; disable the code instead.' })
      : json(200, { ...podcast, maxUses: 50, remaining: 43 }),
    'DELETE /api/admin/invite-codes/7/max-uses': () => json(200, { ...podcast, maxUses: undefined, remaining: undefined }),
  })
  await mount()
  await act(async () => { rowOf('podcast').click() })
  await flush()

  type('maxUses', '3')
  await act(async () => { button('Save')!.click() })
  await flush()
  expect(bodyOf('PUT /api/admin/invite-codes/7/max-uses')).toEqual({ maxUses: 3 })
  expect(host.textContent).toContain('disable the code instead')
  expect(rowOf('podcast').textContent).toContain('7 / 100')

  refuse = false
  type('maxUses', '50')
  await act(async () => { button('Save')!.click() })
  await flush()
  expect(rowOf('podcast').textContent).toContain('7 / 50')

  await act(async () => { button('No ceiling')!.click() })
  await flush()
  expect(calls()).toContain('DELETE /api/admin/invite-codes/7/max-uses')
  expect(rowOf('podcast').textContent).toContain('7 / ∞')
})
```

Note `rowOf` finds the first `tbody tr` containing the code text; the details row also contains no code text, so the first match stays the code row.

Run: `npx vitest run src/components/AdminPage/AdminPage.test.ts` → the three new cases FAIL.

- [ ] **Step 2: CodeDetails**

Create `frontend/src/components/AdminPage/CodeDetails.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Button, TextInput } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import {
  InviteCodeView, NOT_AVAILABLE, Redemption, SAVE_FAILED, byNewest, problemDetail, shortUserId,
} from './inviteCodes'

interface Props {
  code: InviteCodeView
  onChange: (view: InviteCodeView) => void
}

type Status = { kind: 'note' | 'error'; message: string } | null
type Redemptions = { kind: 'loading' } | { kind: 'failed' } | { kind: 'ready'; rows: Redemption[] }

/**
 * The expanded row: the code's ceiling, and who came in on it.
 *
 * A 409 on the ceiling carries the server's own sentence — it says to disable the code rather
 * than lower the ceiling below what is spent — and that sentence is shown as is, because the
 * rule lives there and this page must not paraphrase it into something the API would not say.
 */
export default function CodeDetails({ code, onChange }: Props) {
  const [ceiling, setCeiling] = useState(code.maxUses === undefined ? '' : String(code.maxUses))
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<Status>(null)
  const [redemptions, setRedemptions] = useState<Redemptions>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false
    apiFetch(`/api/admin/invite-codes/${code.id}/redemptions`)
      .then(async response => {
        if (!response.ok) throw new Error(String(response.status))
        const rows: Redemption[] = await response.json()
        if (!cancelled) setRedemptions({ kind: 'ready', rows: [...rows].sort(byNewest) })
      })
      .catch(() => { if (!cancelled) setRedemptions({ kind: 'failed' }) })
    return () => { cancelled = true }
  }, [code.id])

  async function apply(init: RequestInit) {
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch(`/api/admin/invite-codes/${code.id}/max-uses`, init)
      if (response.ok) {
        const view: InviteCodeView = await response.json()
        setCeiling(view.maxUses === undefined ? '' : String(view.maxUses))
        onChange(view)
        setStatus({ kind: 'note', message: 'Saved.' })
      } else if (response.status === 409 || response.status === 400) {
        setStatus({ kind: 'error', message: (await problemDetail(response)) ?? SAVE_FAILED })
      } else if (response.status === 403) {
        setStatus({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setStatus({ kind: 'error', message: SAVE_FAILED })
      }
    } catch {
      setStatus({ kind: 'error', message: SAVE_FAILED })
    } finally {
      setBusy(false)
    }
  }

  function save(event: React.FormEvent) {
    event.preventDefault()
    if (!/^\d+$/.test(ceiling)) {
      setStatus({ kind: 'error', message: 'A ceiling is a plain number, digits only.' })
      return
    }
    void apply({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxUses: Number(ceiling) }),
    })
  }

  return (
    <div className={classes.details} onClick={event => event.stopPropagation()}>
      <form className={classes.ceiling} onSubmit={save}>
        <h3 className={`${typography.label} ${classes.label}`}>Ceiling</h3>
        <div className={classes.ceilingRow}>
          <TextInput name="maxUses" aria-label="Ceiling" inputMode="numeric" value={ceiling}
            onChange={event => setCeiling(event.currentTarget.value.trim())} placeholder="No ceiling" />
          <Button type="submit" size="compact-sm" loading={busy} disabled={ceiling === ''}>Save</Button>
          {code.maxUses !== undefined && (
            <Button type="button" size="compact-sm" variant="subtle" disabled={busy}
              onClick={() => { void apply({ method: 'DELETE' }) }}>
              No ceiling
            </Button>
          )}
        </div>
        {status && (
          <p className={status.kind === 'error' ? classes.error : classes.muted}
             role={status.kind === 'error' ? 'alert' : 'status'}>
            {status.message}
          </p>
        )}
      </form>

      <div className={classes.redemptions}>
        <h3 className={`${typography.label} ${classes.label}`}>Redemptions</h3>
        {redemptions.kind === 'loading' && <p className={classes.muted}>Loading…</p>}
        {redemptions.kind === 'failed' && <p className={classes.error}>Redemptions could not be loaded.</p>}
        {redemptions.kind === 'ready' && redemptions.rows.length === 0 && (
          <p className={classes.muted}>Nobody has used this code yet.</p>
        )}
        {redemptions.kind === 'ready' && redemptions.rows.length > 0 && (
          <ul className={classes.redemptionList}>
            {redemptions.rows.map(row => (
              <li key={row.id}>
                <span>{new Date(row.redeemedAt).toLocaleString()}</span>
                {row.userId === null
                  ? <em className={classes.muted}>burnt</em>
                  : <code title={row.userId}>{shortUserId(row.userId)}</code>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
```

The wrapping `div`'s `stopPropagation` keeps a click inside the details (typing, Save) from collapsing the row.

- [ ] **Step 3: Mount it and style it**

In `AdminPage.tsx`: `import CodeDetails from './CodeDetails'`; in `CodeRowGroup` replace the placeholder comment and `{void onChange}` with `<CodeDetails code={code} onChange={onChange} />`.

Append to `AdminPage.module.css`:

```css
.details {
  display: flex;
  flex-direction: column;
  gap: var(--space-16);
  cursor: default;
}

.ceiling,
.redemptions {
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
}

.ceilingRow {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-8);
}

.ceilingRow > :first-child {
  width: 8rem;
}

.redemptionList {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.redemptionList li {
  display: flex;
  gap: var(--space-16);
}
```

- [ ] **Step 4: Verify and commit**

Run: `cd frontend && npm test` → all green. `npx tsc -b` → clean.

```bash
git add frontend/src/components/AdminPage
git commit -m "Expand an invite code to its ceiling and redemptions"
```

---

### Task 4: New-code form, docs, build

**Files:**
- Create: `frontend/src/components/AdminPage/NewCodeForm.tsx`
- Modify: `frontend/src/components/AdminPage/AdminPage.tsx` (mount the form, drop `{void appendCode}`), `AdminPage.module.css`
- Modify: `frontend/src/components/AdminPage/AdminPage.test.ts` (two cases)
- Modify: `docs/STATUS.md`, `docs/DEVELOPMENT.md`, this plan's `PROGRESS` line and an `## Outcome` block

**Interfaces:**
- Consumes: `NewCodeFields`, `createBody`, `InviteCodeView`, `NOT_AVAILABLE`, `SAVE_FAILED`, `problemDetail`; `appendCode` arrives as `onCreated`.
- Produces: `NewCodeForm` default export, props `{ onCreated: (view: InviteCodeView) => void }`.

- [ ] **Step 1: Failing tests**

Append to `AdminPage.test.ts`:

```ts
it('posts a new code with a null ceiling, appends the created row and clears the form', async () => {
  const created: InviteCodeView = {
    id: 9, code: 'newsletter', label: 'newsletter', uses: 0, accounts: 0, burnt: 0,
    benefitKind: 'discount_percent', benefitValue: 15, active: true, state: 'OPEN',
    createdAt: '2026-09-14T00:00:00Z',
  }
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'POST /api/admin/invite-codes': () => json(201, created),
  })
  await mount()

  type('code', 'newsletter')
  type('benefitKind', 'discount_percent')
  type('benefitValue', '15')
  await act(async () => { button('Create code')!.click() })
  await flush()

  expect(bodyOf('POST /api/admin/invite-codes')).toEqual({
    code: 'newsletter', label: null, maxUses: null, benefitKind: 'discount_percent',
    benefitValue: 15, validFrom: null, validUntil: null, note: null,
  })
  expect(host.querySelectorAll('tbody tr')).toHaveLength(3)
  expect(rowOf('newsletter').textContent).toContain('0 / ∞')
  expect(host.querySelector<HTMLInputElement>('[name="code"]')!.value).toBe('')
})

it('reports a duplicate code in the spec wording', async () => {
  serve({
    'GET /api/admin/invite-codes': () => json(200, list),
    'POST /api/admin/invite-codes': () => json(409, { status: 409, detail: 'A code with that name already exists.' }),
  })
  await mount()

  type('code', 'podcast')
  await act(async () => { button('Create code')!.click() })
  await flush()

  expect(host.textContent).toContain('A code with that name already exists.')
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
})
```

Also assert, in the first test, that the percentage field is absent before the kind is switched: `expect(host.querySelector('[name="benefitValue"]')).toBeNull()` right after `mount()`.

Run: `npx vitest run src/components/AdminPage/AdminPage.test.ts` → the two new cases FAIL.

- [ ] **Step 2: NewCodeForm**

Create `frontend/src/components/AdminPage/NewCodeForm.tsx`:

```tsx
import { useState } from 'react'
import { Button, NativeSelect, TextInput } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import {
  BenefitKind, InviteCodeView, NOT_AVAILABLE, NewCodeFields, SAVE_FAILED, createBody, problemDetail,
} from './inviteCodes'

interface Props {
  onCreated: (view: InviteCodeView) => void
}

type Status = { kind: 'note' | 'error'; message: string } | null

const EMPTY: NewCodeFields = {
  code: '', label: '', maxUses: '', benefitKind: 'access', benefitValue: '', validFrom: '',
  validUntil: '', note: '',
}

/**
 * Issuing a code. On 201 the returned row is appended to the table above and the form clears;
 * a refusal is one message per cause, the `SettingsPage` pattern — 409 is the spec's sentence,
 * 400 is the server's own detail because it names the field, 403 is the page's not-available
 * sentence, and anything else is ours, not the admin's.
 */
export default function NewCodeForm({ onCreated }: Props) {
  const [fields, setFields] = useState<NewCodeFields>(EMPTY)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<Status>(null)

  function set<K extends keyof NewCodeFields>(key: K, value: NewCodeFields[K]) {
    setFields(current => ({ ...current, [key]: value }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch('/api/admin/invite-codes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(createBody(fields)),
      })
      if (response.status === 201) {
        onCreated(await response.json())
        setFields(EMPTY)
        setStatus({ kind: 'note', message: 'Created.' })
      } else if (response.status === 409) {
        setStatus({ kind: 'error', message: 'A code with that name already exists.' })
      } else if (response.status === 400) {
        setStatus({ kind: 'error', message: (await problemDetail(response)) ?? SAVE_FAILED })
      } else if (response.status === 403) {
        setStatus({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setStatus({ kind: 'error', message: SAVE_FAILED })
      }
    } catch {
      setStatus({ kind: 'error', message: SAVE_FAILED })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className={classes.section} onSubmit={submit}>
      <h2 className={`${typography.label} ${classes.label}`}>New code</h2>
      <div className={classes.formGrid}>
        <TextInput name="code" label="Code" required value={fields.code}
          onChange={event => set('code', event.currentTarget.value)} />
        <TextInput name="label" label="Label" placeholder={fields.code || 'The code'} value={fields.label}
          onChange={event => set('label', event.currentTarget.value)} />
        <TextInput name="maxUses" label="Ceiling" inputMode="numeric" placeholder="No ceiling"
          value={fields.maxUses} onChange={event => set('maxUses', event.currentTarget.value.trim())} />
        <NativeSelect name="benefitKind" label="Benefit" value={fields.benefitKind}
          data={[{ value: 'access', label: 'Access' }, { value: 'discount_percent', label: 'Discount %' }]}
          onChange={event => set('benefitKind', event.currentTarget.value as BenefitKind)} />
        {fields.benefitKind === 'discount_percent' && (
          <TextInput name="benefitValue" label="Discount %" inputMode="numeric" value={fields.benefitValue}
            onChange={event => set('benefitValue', event.currentTarget.value.trim())} />
        )}
        <TextInput name="validFrom" label="Valid from" type="date" value={fields.validFrom}
          onChange={event => set('validFrom', event.currentTarget.value)} />
        <TextInput name="validUntil" label="Valid until" type="date" value={fields.validUntil}
          onChange={event => set('validUntil', event.currentTarget.value)} />
        <TextInput name="note" label="Note" value={fields.note}
          onChange={event => set('note', event.currentTarget.value)} />
      </div>
      <div className={classes.formActions}>
        <Button type="submit" loading={busy} disabled={fields.code.trim() === ''}>Create code</Button>
        {status && (
          <p className={status.kind === 'error' ? classes.error : classes.muted}
             role={status.kind === 'error' ? 'alert' : 'status'}>
            {status.message}
          </p>
        )}
      </div>
    </form>
  )
}
```

- [ ] **Step 3: Mount it and style it**

In `AdminPage.tsx`: `import NewCodeForm from './NewCodeForm'`; replace the Task 4 comment and `{void appendCode}` with `<NewCodeForm onCreated={appendCode} />`.

Append to `AdminPage.module.css`:

```css
.formGrid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-12) var(--space-16);
}

.formActions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-12);
}

@media (max-width: 639px) {
  .formGrid {
    grid-template-columns: minmax(0, 1fr);
  }
}
```

- [ ] **Step 4: Verify**

Run from `frontend/`: `npm test` → all green; `npm run build` → contrast check, `tsc -b` and the Vite build all pass (needs `VITE_SUPABASE_ANON_KEY` in `frontend/.env.local`; if it is absent, run `VITE_SUPABASE_ANON_KEY=x npm run build` — the value is not used by the build itself). Report the exact output lines.

- [ ] **Step 5: Docs**

`docs/DEVELOPMENT.md`, in the invite-code section beside the KAR-54 endpoint table (around the sentence "since KAR-54 they are operated through the admin API rather than SQL"): add one sentence — *Since KAR-55 the app's `/admin` page (menu item **Admin**, shown when `GET /api/profile` says `admin: true`) is the front for that API; the `curl` lines below remain for a session without a browser.* Keep everything else.

`docs/STATUS.md`: append a section after the KAR-54 entry:

```markdown
## Admin page for invite codes (KAR-55, 2026-09-14)

Plan: `docs/superpowers/plans/2026-09-14-kar-55-admin-invite-codes-page.md`.

`/admin` in the frontend, reached from an **Admin** item in the app menu that appears when
`GET /api/profile` answers `admin: true` (`useProfile`, read once per signed-in shell). The item
is a convenience: anyone can type the URL, the API answers 403 and the page says *This page is
not available for this account.* — nothing in the browser decides who is an admin. The page shows
the places line with **Release burnt places** when something is burnt, one row per code with the
server-computed state in the spec's words (Open, Disabled, Used up, Expired, Not yet valid),
Disable/Enable, and on expanding a row its ceiling (save, or **No ceiling**) and its redemptions
newest first; a form below issues a new code. Every mutation re-renders from the `CodeView` the
server returns, never from the click. Under 640px the table is one stacked block per code.

Rulings: form dates are the admin's local days (`validUntil` is the end of that day); the places
line always carries the burnt count; only Disabled and Used-up rows are muted; `SettingsPage` is
untouched.

Verified: `npm test` (<N> tests green — fill in), `npm run build` clean. Manual check with
`npm run dev` against the local backend with `ADMIN_USER_IDS` set: <fill in what was done, or
state that it was not run and why>.
```

Update this plan: `**PROGRESS:** Task 4/4 done.` and add an `## Outcome` block at the end (5–10 lines: what shipped, test counts, anything deferred).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/AdminPage docs/STATUS.md docs/DEVELOPMENT.md docs/superpowers/plans/2026-09-14-kar-55-admin-invite-codes-page.md
git commit -m "Add the new-code form to the admin page and document KAR-55"
```

---

## Self-review against the spec

- Entry and access: Task 1 (route, `routeFromPath`, `Profile.admin`, `useProfile`, Admin item, not-a-gate) + Task 2 (`ChatView` renders the page; 403 → sentence, no table).
- Places line + release + note incl. grace-period case: Task 2 (`placesLine`, `releaseNote`, re-fetch).
- Codes table columns, `7 / ∞`, state words, muted-not-hidden, one-click Disable/Enable from response, `(1 burnt)` only when non-zero: Task 2.
- Row expansion: ceiling save / No ceiling / 409 detail, redemptions newest-first with shortened id + `title`, *burnt*, empty state: Task 3.
- Under 640px stacked blocks: Task 2 CSS (+ Task 4 form grid).
- New code form fields, conditional percentage, POST → 201 append + clear, 409/400/403/401/other wording: Task 4.
- Tests named in the spec: router (T1), AppMenu (T1), AdminPage list/state words/disable-from-response/403/form body/409/release-absent (T2, T4); `npm run build` + `npm test` (T4); manual check (T4 docs, reported honestly).
- Out of scope respected: no edit of label/note/validity, no delete, no other admin function, no admin layout.
