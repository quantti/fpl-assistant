import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../auth/apiFetch'
import {
  describeGameweekStatus,
  formatChipName,
  formatMoney,
  loadMyTeam,
  ReadyResponse,
  selectPrimarySquad,
} from './myTeam'

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }))

const apiFetchMock = vi.mocked(apiFetch)

beforeEach(() => {
  apiFetchMock.mockReset()
})

describe('loadMyTeam', () => {
  it('loads and validates a ready response through the authenticated fetch helper', async () => {
    apiFetchMock.mockResolvedValue(jsonResponse(ready()))
    const controller = new AbortController()

    await expect(loadMyTeam(controller.signal)).resolves.toEqual(ready())
    expect(apiFetchMock).toHaveBeenCalledWith('/api/my-team', { signal: controller.signal })
  })

  it.each(['no_team_id', 'team_not_found', 'no_squad_yet'] as const)(
    'preserves the %s page state',
    async status => {
      apiFetchMock.mockResolvedValue(jsonResponse({ status }))

      await expect(loadMyTeam(new AbortController().signal)).resolves.toEqual({ status })
    },
  )

  it('keeps a terminal 401 in the sign-in flow', async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 401 }))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'signed_out' })
  })

  it('distinguishes a plain-text shared rate limit response', async () => {
    apiFetchMock.mockResolvedValue(new Response('Rate limit exceeded', { status: 429 }))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'rate_limited' })
  })

  it('distinguishes an FPL outage from other failures', async () => {
    apiFetchMock.mockResolvedValue(jsonResponse({ status: 'unavailable' }, 503))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'unavailable' })
  })

  it('reports another HTTP failure without showing its raw body', async () => {
    apiFetchMock.mockResolvedValue(new Response('internal stack trace', { status: 500 }))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'request_failed' })
  })

  it('reports invalid JSON separately', async () => {
    apiFetchMock.mockResolvedValue(new Response('not json', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })
  })

  it('does not turn an unknown status or malformed ready body into an empty squad', async () => {
    apiFetchMock.mockResolvedValueOnce(jsonResponse({ status: 'mystery' }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })

    apiFetchMock.mockResolvedValueOnce(jsonResponse({ status: 'ready', entry: {} }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })
  })

  it('preserves partial data when both the continuing squad and chips are unknown', async () => {
    const response = ready()
    response.deadline = null
    response.lockedSquad.activeChip = 'freehit'
    response.lockedSquad.players[0] = {
      ...response.lockedSquad.players[0], name: 'Element 302', club: null, marketPrice: null,
    }
    response.chips = { known: false, gameweek: 6, playable: [], openingLater: [], openingGameweek: null }
    response.continuing = { status: 'unknown', reason: 'No previous picks were published' }
    apiFetchMock.mockResolvedValue(jsonResponse(response))

    await expect(loadMyTeam(new AbortController().signal)).resolves.toEqual(response)
  })

  it('rejects unknown continuing states and malformed player details', async () => {
    const response = ready()
    apiFetchMock.mockResolvedValueOnce(jsonResponse({
      ...response, continuing: { status: 'mystery' },
    }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })

    apiFetchMock.mockResolvedValueOnce(jsonResponse({
      ...response,
      lockedSquad: {
        ...response.lockedSquad,
        players: [{ ...response.lockedSquad.players[0], marketPrice: '12.6' }],
      },
    }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })
  })

  it('distinguishes a failed network request', async () => {
    apiFetchMock.mockRejectedValue(new TypeError('fetch failed with private details'))

    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'network_error' })
  })

  it('publishes no state when aborted before the response arrives', async () => {
    let finish!: (response: Response) => void
    apiFetchMock.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const controller = new AbortController()

    const loading = loadMyTeam(controller.signal)
    controller.abort()
    finish(jsonResponse(ready()))

    await expect(loading).resolves.toBeNull()
  })

  it('publishes no state when aborted during delayed JSON decoding', async () => {
    let finishJson!: (body: unknown) => void
    const decoded = new Promise(resolve => { finishJson = resolve })
    apiFetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => decoded,
    } as Response)
    const controller = new AbortController()

    const loading = loadMyTeam(controller.signal)
    await Promise.resolve()
    controller.abort()
    finishJson(ready())

    await expect(loading).resolves.toBeNull()
  })

  it('does not publish a late HTTP failure after abort', async () => {
    let finish!: (response: Response) => void
    apiFetchMock.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const controller = new AbortController()
    const loading = loadMyTeam(controller.signal)
    controller.abort()
    finish(new Response('rate limit', { status: 429 }))

    await expect(loading).resolves.toBeNull()
  })

  it('does not publish a late JSON decoding failure after abort', async () => {
    let rejectJson!: (error: Error) => void
    const decoding = new Promise((_, reject) => { rejectJson = reject })
    apiFetchMock.mockResolvedValue({
      ok: true, status: 200, json: () => decoding,
    } as Response)
    const controller = new AbortController()
    const loading = loadMyTeam(controller.signal)
    await Promise.resolve()
    controller.abort()
    rejectJson(new SyntaxError('Unfinished body'))

    await expect(loading).resolves.toBeNull()
  })

  it('accepts absent gameweek points as null, never as zero', async () => {
    const response = ready()
    response.gameweekPoints = { gameweek: 5, status: 'unknown', entryTotal: null, playersAsOf: null }
    response.lockedSquad.players[0].gameweekPoints = null
    response.lockedSquad.players[0].gameweekMinutes = null
    apiFetchMock.mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }))

    await expect(loadMyTeam(new AbortController().signal)).resolves.toEqual(response)
  })

  it('rejects an unknown gameweek status or non-integer points', async () => {
    const badStatus = ready()
    ;(badStatus.gameweekPoints as { status: string }).status = 'live'
    apiFetchMock.mockResolvedValue(new Response(JSON.stringify(badStatus), { status: 200 }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })

    const badPoints = ready()
    ;(badPoints.lockedSquad.players[0] as { gameweekPoints: unknown }).gameweekPoints = '8'
    apiFetchMock.mockResolvedValue(new Response(JSON.stringify(badPoints), { status: 200 }))
    await expect(loadMyTeam(new AbortController().signal))
      .resolves.toEqual({ status: 'invalid_response' })
  })

  it('words each gameweek status without promising a final score early', () => {
    expect(describeGameweekStatus(5, 'in_progress')).toContain('not finished')
    expect(describeGameweekStatus(5, 'finished_unchecked')).toContain('not confirmed')
    expect(describeGameweekStatus(5, 'final')).toBe('GW 5 is final.')
    expect(describeGameweekStatus(5, 'unknown')).toContain('could not be read')
  })
})

describe('page display decisions', () => {
  it('uses the ordinary locked squad as the primary squad', () => {
    const response = ready()

    expect(selectPrimarySquad(response)).toEqual({
      kind: 'available',
      context: 'locked',
      sourceGameweek: 5,
      restoredAtGameweek: null,
      bank: 12,
      value: 1003,
      players: response.lockedSquad.players,
    })
  })

  it('uses the continuing squad and its own funds during a Free Hit', () => {
    const response = ready()
    response.lockedSquad.activeChip = 'freehit'
    response.continuing = {
      status: 'available',
      sourceGameweek: 4,
      bank: 5,
      value: 995,
      players: [{ ...response.lockedSquad.players[0], name: 'Wood', elementId: 44 }],
    }

    expect(selectPrimarySquad(response)).toMatchObject({
      kind: 'available',
      context: 'continuing',
      sourceGameweek: 4,
      restoredAtGameweek: 5,
      bank: 5,
      value: 995,
      players: [{ name: 'Wood' }],
    })
  })

  it('shows unavailable rather than falling back to the Free Hit squad', () => {
    const response = ready()
    response.lockedSquad.activeChip = 'freehit'
    response.continuing = { status: 'unknown', reason: 'No earlier picks were published' }

    const primary = selectPrimarySquad(response)

    expect(primary).toEqual({
      kind: 'unavailable',
      restoredAtGameweek: 5,
      reason: 'No earlier picks were published',
    })
    expect(primary).not.toHaveProperty('bank')
    expect(primary).not.toHaveProperty('players')
  })

  it('formats integer tenths and known chip names only at display time', () => {
    expect(formatMoney(75)).toBe('£7.5m')
    expect(formatMoney(0)).toBe('£0.0m')
    expect(formatMoney(null)).toBe('Unavailable')
    expect(formatChipName('freehit')).toBe('Free Hit')
    expect(formatChipName('free hit')).toBe('Free Hit')
    expect(formatChipName('bench boost')).toBe('Bench Boost')
    expect(formatChipName('triple captain')).toBe('Triple Captain')
    expect(formatChipName('future-chip')).toBe('future-chip')
  })
})

function ready(): ReadyResponse {
  return {
    status: 'ready',
    entry: { id: 123, name: 'Deadline FC' },
    lockedGameweek: 5,
    deadline: '2026-09-05T10:00:00Z',
    loadedAt: '2026-09-08T12:34:56Z',
    lockedSquad: {
      bank: 12,
      value: 1003,
      activeChip: null,
      players: [{
        elementId: 302,
        pickPosition: 1,
        captain: true,
        viceCaptain: false,
        position: 'MID',
        name: 'Salah',
        club: 'LIV',
        marketPrice: 126,
        availability: null,
        gameweekPoints: 8,
        gameweekMinutes: 90,
      }],
    },
    chips: {
      known: true,
      gameweek: 6,
      playable: [],
      openingLater: ['wildcard'],
      openingGameweek: 20,
    },
    continuing: { status: 'not_applicable' },
    gameweekPoints: { gameweek: 5, status: 'in_progress', entryTotal: 61, playersAsOf: '2026-09-12T15:32:00Z' },
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
