// @vitest-environment jsdom

import React, { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import type { CataloguePlayer, PlayerCatalogue } from '../types'

vi.mock('../auth/apiFetch', () => ({ apiFetch: vi.fn() }))
import { apiFetch } from '../auth/apiFetch'
import { ShortlistProvider, useShortlist } from './ShortlistContext'

const apiFetchMock = vi.mocked(apiFetch)
const LOAD_ERROR_COPY = 'Could not load your shortlist. Try again.'
type State = ReturnType<typeof useShortlist>
let state: State
let root: ReturnType<typeof createRoot> | undefined

const catalogue: PlayerCatalogue = {
  catalogueId: 'catalogue-1', season: '2026-27', players: [],
}
const saka: CataloguePlayer = {
  elementId: 17, webName: 'Saka', fullName: 'Bukayo Saka', aliases: ['Saka'],
  teamShort: 'ARS', teamName: 'Arsenal', elementType: 3,
}
const stored = {
  ...saka, catalogueId: catalogue.catalogueId, season: catalogue.season,
  teamId: 1, createdAt: '2026-09-13T10:00:00Z',
}

function Probe() {
  state = useShortlist()
  return null
}

async function mount(load: Response = Response.json([])) {
  apiFetchMock.mockResolvedValueOnce(load)
  const host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  await act(async () => {
    root!.render(React.createElement(ShortlistProvider, null, React.createElement(Probe)))
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

it('loads membership once and derives shared count and identity state', async () => {
  await mount(Response.json([stored]))

  expect(apiFetchMock).toHaveBeenCalledWith('/api/shortlist', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(state.loading).toBe(false)
  expect(state.count).toBe(1)
  expect(state.isSelected('2026-27', 17)).toBe(true)
})

it('updates pessimistically with the backend PUT and DELETE JSON contracts', async () => {
  await mount()
  let finishPut!: (response: Response) => void
  apiFetchMock.mockReturnValueOnce(new Promise(resolve => { finishPut = resolve }))

  let add!: Promise<void>
  act(() => { add = state.toggle(catalogue, saka) })
  expect(state.isSelected('2026-27', 17)).toBe(false)
  expect(state.isPending('2026-27', 17)).toBe(true)
  expect(JSON.parse(apiFetchMock.mock.calls[1][1]!.body as string)).toEqual({
    catalogueId: 'catalogue-1', season: '2026-27', elementId: 17,
  })

  await act(async () => {
    finishPut(Response.json(stored))
    await add
  })
  expect(state.isSelected('2026-27', 17)).toBe(true)

  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
  await act(async () => { await state.toggle(catalogue, saka) })
  expect(apiFetchMock.mock.calls[2][1]).toMatchObject({ method: 'DELETE' })
  expect(JSON.parse(apiFetchMock.mock.calls[2][1]!.body as string)).toEqual({
    season: '2026-27', elementId: 17,
  })
  expect(state.isSelected('2026-27', 17)).toBe(false)
})

it('keeps membership unknown and blocks mutations until a failed initial load retries successfully', async () => {
  await mount(new Response(null, { status: 500 }))

  expect(state.loadFailed).toBe(true)
  expect(state.error).toBe(LOAD_ERROR_COPY)
  expect(state.count).toBeNull()
  await act(async () => { await state.toggle(catalogue, saka) })
  expect(apiFetchMock).toHaveBeenCalledTimes(1)

  let finish!: (response: Response) => void
  apiFetchMock.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  let retry!: Promise<void>
  act(() => { retry = state.retry() })
  expect(state.loading).toBe(true)
  expect(state.error).toBe(LOAD_ERROR_COPY)
  expect(state.count).toBeNull()
  await act(async () => { await state.toggle(catalogue, saka) })
  expect(apiFetchMock).toHaveBeenCalledTimes(2)

  await act(async () => {
    finish(Response.json([stored]))
    await retry
  })
  expect(state.loadFailed).toBe(false)
  expect(state.error).toBeNull()
  expect(state.count).toBe(1)
})

it('lets only the newest membership retry settle state', async () => {
  await mount(new Response(null, { status: 500 }))
  let finishOld!: (response: Response) => void
  let finishNew!: (response: Response) => void
  apiFetchMock
    .mockReturnValueOnce(new Promise(resolve => { finishOld = resolve }))
    .mockReturnValueOnce(new Promise(resolve => { finishNew = resolve }))

  let oldRetry!: Promise<void>
  let newRetry!: Promise<void>
  act(() => { oldRetry = state.retry() })
  const oldSignal = apiFetchMock.mock.calls[1][1]!.signal
  act(() => { newRetry = state.retry() })
  expect(oldSignal?.aborted).toBe(true)

  await act(async () => {
    finishNew(Response.json([stored]))
    await newRetry
  })
  await act(async () => {
    finishOld(new Response(null, { status: 500 }))
    await oldRetry
  })
  expect(state.players).toEqual([stored])
  expect(state.loadFailed).toBe(false)
  expect(state.error).toBeNull()
})

it('keeps state unchanged on errors and ignores a mutation that settles after account unmount', async () => {
  await mount()
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 500 }))
  await act(async () => { await state.toggle(catalogue, saka) })
  expect(state.players).toEqual([])
  expect(state.error).toContain('Could not update')

  let finish!: (response: Response) => void
  apiFetchMock.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  let pending!: Promise<void>
  act(() => { pending = state.toggle(catalogue, saka) })
  const mutationSignal = apiFetchMock.mock.calls[2][1]!.signal
  act(() => root!.unmount())
  root = undefined
  expect(mutationSignal?.aborted).toBe(true)
  await act(async () => {
    finish(Response.json(stored))
    await pending
  })
  expect(state.players).toEqual([])
})
