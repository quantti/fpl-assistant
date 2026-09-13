// @vitest-environment jsdom

import React, { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }))
vi.mock('@mantine/core', () => ({ Loader: () => React.createElement('span', null, 'Loading') }))
import { apiFetch } from '../../auth/apiFetch'
import HeaderNavigation from '../HeaderNavigation/HeaderNavigation'
import { ShortlistProvider, useShortlist } from '../../shortlist/ShortlistContext'
import ShortlistPage from './ShortlistPage'

const apiFetchMock = vi.mocked(apiFetch)
let root: ReturnType<typeof createRoot> | undefined
let host: HTMLDivElement

const stored = {
  catalogueId: 'catalogue-1',
  season: '2026-27',
  elementId: 17,
  webName: 'Saka',
  fullName: 'Bukayo Saka',
  teamId: 1,
  teamShort: 'ARS',
  teamName: 'Arsenal',
  elementType: 3,
  createdAt: '2026-09-13T10:00:00Z',
}

function Shell() {
  const shortlist = useShortlist()
  return React.createElement(React.Fragment, null,
    React.createElement(HeaderNavigation, { route: '/shortlist', shortlistCount: shortlist.count }),
    React.createElement(ShortlistPage),
  )
}

async function mount(response: Response) {
  apiFetchMock.mockResolvedValueOnce(response)
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  await act(async () => {
    root!.render(React.createElement(ShortlistProvider, null, React.createElement(Shell)))
    await Promise.resolve()
    await Promise.resolve()
  })
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

it('renders loading, load-error and empty states', async () => {
  let finish!: (response: Response) => void
  apiFetchMock.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  act(() => root!.render(React.createElement(ShortlistProvider, null, React.createElement(Shell))))
  expect(host.textContent).toContain('Loading your shortlist')

  await act(async () => { finish(new Response(null, { status: 500 })); await Promise.resolve() })
  expect(host.textContent).toContain('Your shortlist could not be loaded')
  expect(host.textContent).toContain('Shortlist (—)')
  expect(Array.from(host.querySelectorAll('button')).some(button => button.textContent === 'Retry')).toBe(true)

  act(() => root!.unmount())
  root = undefined
  await mount(Response.json([]))
  expect(host.textContent).toContain('Your shortlist is empty')
})

it('shows name, club and position, then removes through shared state', async () => {
  await mount(Response.json([stored]))
  expect(host.textContent).toContain('Bukayo Saka')
  expect(host.textContent).toContain('Arsenal · Midfielder · 2026/27 season')
  expect(host.textContent).toContain('Shortlist (1)')

  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
  const remove = Array.from(host.querySelectorAll('button'))
    .find(button => button.textContent === 'Remove')!
  await act(async () => { remove.click(); await Promise.resolve() })

  expect(apiFetchMock.mock.calls[1][1]).toMatchObject({ method: 'DELETE' })
  expect(JSON.parse(apiFetchMock.mock.calls[1][1]!.body as string)).toEqual({
    season: '2026-27', elementId: 17,
  })
  expect(host.textContent).not.toContain('Bukayo Saka')
  expect(host.textContent).toContain('Shortlist (0)')
  expect(host.textContent).toContain('Your shortlist is empty')
})
