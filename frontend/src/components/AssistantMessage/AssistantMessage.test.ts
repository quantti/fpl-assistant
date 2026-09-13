// @vitest-environment jsdom

import React, { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import type { PlayerCatalogue } from '../../types'

const toggle = vi.fn()
vi.mock('../../shortlist/ShortlistContext', () => ({
  useShortlist: () => ({
    players: [], loading: false, error: null, loadFailed: false, count: 0, retry: vi.fn(),
    isSelected: (_season: string, id: number) => id === 17,
    isPending: () => false,
    toggle,
  }),
}))

import AssistantMessage from './AssistantMessage'

const catalogue: PlayerCatalogue = {
  catalogueId: 'catalogue-1',
  season: '2026-27',
  players: [
    {
      elementId: 17, webName: 'Saka', fullName: 'Bukayo Saka', aliases: ['Bukayo Saka', 'Saka'],
      teamShort: 'ARS', teamName: 'Arsenal', elementType: 3,
    },
    {
      elementId: 20, webName: 'Son', fullName: 'Son Heung-min', aliases: ['Son'],
      teamShort: 'TOT', teamName: 'Tottenham', elementType: 3,
    },
  ],
}

beforeAll(() => {
  ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean })
    .IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  document.body.replaceChildren()
  toggle.mockReset()
})

it('renders accessible toggles in GFM tables, repeated prose and inline formatting', () => {
  const host = document.createElement('div')
  document.body.append(host)
  const root = createRoot(host)
  act(() => root.render(React.createElement(AssistantMessage, {
    catalogue,
    content: '| Player | Pick |\n|---|---|\n| Saka | **Son** |\n\nSaka again.',
  })))

  const buttons = [...host.querySelectorAll('button')]
  expect(buttons.map(button => button.textContent)).toEqual(['Saka★', 'Son☆', 'Saka★'])
  expect(buttons[0].getAttribute('aria-pressed')).toBe('true')
  expect(buttons[1].getAttribute('aria-label')).toBe('Add Son Heung-min to shortlist')
  expect(buttons[1].querySelector('strong')).not.toBeNull()

  act(() => buttons[1].click())
  expect(toggle).toHaveBeenCalledWith(catalogue, catalogue.players[1])
  act(() => root.unmount())
})

it('leaves direct and formatted linked names as links without nesting a button', () => {
  const host = document.createElement('div')
  document.body.append(host)
  const root = createRoot(host)
  act(() => root.render(React.createElement(AssistantMessage, {
    catalogue,
    content: '[Saka](https://example.com/player), **[Son](https://example.com/son)** and Saka',
  })))

  const link = host.querySelector('a')
  expect(link?.textContent).toBe('Saka')
  expect(link?.querySelector('button')).toBeNull()
  expect(host.querySelector('strong a')?.textContent).toBe('Son')
  expect(host.querySelector('a button, button a')).toBeNull()
  expect(host.querySelectorAll('a')).toHaveLength(2)
  expect(host.querySelectorAll('button')).toHaveLength(1)
  act(() => root.unmount())
})

it('recognizes player names in inline code and preserves code styling, including in tables', () => {
  const host = document.createElement('div')
  document.body.append(host)
  const root = createRoot(host)
  act(() => root.render(React.createElement(AssistantMessage, {
    catalogue,
    content: '| Pick |\n|---|\n| `Saka` |\n\nTry `Son` too.',
  })))

  expect([...host.querySelectorAll('button')].map(button => button.textContent)).toEqual(['Saka★', 'Son☆'])
  expect([...host.querySelectorAll('button code')].map(code => code.textContent)).toEqual(['Saka', 'Son'])
  expect(host.querySelector('a button, button a')).toBeNull()
  act(() => root.unmount())
})

it('preserves unmatched inline code beside a recognized formatted name', () => {
  const host = document.createElement('div')
  document.body.append(host)
  const root = createRoot(host)
  act(() => root.render(React.createElement(AssistantMessage, {
    catalogue,
    content: '**Saka costs `£10m`**',
  })))

  expect(host.querySelector('button')?.textContent).toBe('Saka★')
  expect(host.querySelector('code')?.textContent).toBe('£10m')
  expect(host.textContent).toContain('Saka★ costs £10m')
  act(() => root.unmount())
})
