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

beforeEach(() => { apiFetchMock.mockReset() })
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
  expect(host.querySelector('[name="benefitValue"]')).toBeNull()

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

it('refuses a non-numeric ceiling on the new-code form and posts nothing', async () => {
  serve({ 'GET /api/admin/invite-codes': () => json(200, list) })
  await mount()

  type('code', 'newsletter')
  type('maxUses', '1o')
  await act(async () => { button('Create code')!.click() })
  await flush()

  expect(host.textContent).toContain('A ceiling is a plain number, digits only.')
  expect(calls()).not.toContain('POST /api/admin/invite-codes')
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
