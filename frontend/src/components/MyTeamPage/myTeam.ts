import { apiFetch } from '../../auth/apiFetch'

export interface TeamEntry {
  id: number
  name: string
}

export interface SquadPlayer {
  elementId: number
  pickPosition: number
  captain: boolean
  viceCaptain: boolean
  position: string
  name: string
  club: string | null
  marketPrice: number | null
  availability: string | null
  gameweekPoints: number | null
  gameweekMinutes: number | null
}

export interface PageSquad {
  bank: number
  value: number
  activeChip: string | null
  players: SquadPlayer[]
}

export interface ChipAvailability {
  known: boolean
  gameweek: number
  playable: string[]
  openingLater: string[]
  openingGameweek: number | null
}

export type GameweekStatus = 'in_progress' | 'finished_unchecked' | 'final' | 'final_scores_provisional' | 'unknown'

export interface GameweekPoints {
  gameweek: number
  status: GameweekStatus
  entryTotal: number | null
  playersAsOf: string | null
}

export type Continuing =
  | { status: 'not_applicable' }
  | {
      status: 'available'
      sourceGameweek: number
      bank: number
      value: number
      players: SquadPlayer[]
    }
  | { status: 'unknown'; reason: string }

export interface ReadyResponse {
  status: 'ready'
  entry: TeamEntry
  lockedGameweek: number
  deadline: string | null
  loadedAt: string
  lockedSquad: PageSquad
  chips: ChipAvailability
  continuing: Continuing
  gameweekPoints: GameweekPoints
}

export type ServerResponse =
  | ReadyResponse
  | { status: 'no_team_id' }
  | { status: 'team_not_found' }
  | { status: 'no_squad_yet' }
  | { status: 'unavailable' }

export type MyTeamLoadResult = ServerResponse | {
  status: 'signed_out' | 'rate_limited' | 'request_failed' | 'invalid_response' | 'network_error'
}

/**
 * Loads once. A null result means the caller aborted and there is deliberately no state to
 * publish after unmount or navigation.
 */
export async function loadMyTeam(signal: AbortSignal): Promise<MyTeamLoadResult | null> {
  let response: Response
  try {
    response = await apiFetch('/api/my-team', { signal })
  } catch (error) {
    if (signal.aborted || isAbortError(error)) return null
    return { status: 'network_error' }
  }

  if (signal.aborted) return null
  if (response.status === 401) return { status: 'signed_out' }
  if (response.status === 429) return { status: 'rate_limited' }
  if (response.status === 503) return { status: 'unavailable' }
  if (!response.ok) return { status: 'request_failed' }

  let body: unknown
  try {
    body = await response.json()
  } catch (error) {
    if (signal.aborted || isAbortError(error)) return null
    return { status: 'invalid_response' }
  }
  if (signal.aborted) return null

  return isServerResponse(body) ? body : { status: 'invalid_response' }
}

export type PrimarySquad =
  | {
      kind: 'available'
      context: 'locked' | 'continuing'
      sourceGameweek: number
      restoredAtGameweek: number | null
      bank: number
      value: number
      players: SquadPlayer[]
    }
  | { kind: 'unavailable'; restoredAtGameweek: number; reason: string }

/** The Free Hit fifteen is never selected as the primary squad when restoration is unknown. */
export function selectPrimarySquad(response: ReadyResponse): PrimarySquad {
  switch (response.continuing.status) {
    case 'not_applicable':
      return {
        kind: 'available',
        context: 'locked',
        sourceGameweek: response.lockedGameweek,
        restoredAtGameweek: null,
        bank: response.lockedSquad.bank,
        value: response.lockedSquad.value,
        players: response.lockedSquad.players,
      }
    case 'available':
      return {
        kind: 'available',
        context: 'continuing',
        sourceGameweek: response.continuing.sourceGameweek,
        restoredAtGameweek: response.lockedGameweek,
        bank: response.continuing.bank,
        value: response.continuing.value,
        players: response.continuing.players,
      }
    case 'unknown':
      return {
        kind: 'unavailable',
        restoredAtGameweek: response.lockedGameweek,
        reason: response.continuing.reason,
      }
  }
}

/** FPL money is integer tenths in the response; conversion happens only at display time. */
export function formatMoney(tenths: number | null): string {
  return tenths === null ? 'Unavailable' : `£${(tenths / 10).toFixed(1)}m`
}

export function describeGameweekStatus(gameweek: number, status: GameweekStatus): string {
  switch (status) {
    case 'in_progress':
      return `FPL has not marked GW ${gameweek} finished yet: points are provisional, bonus can still change, and automatic substitutions apply only once FPL marks the gameweek final.`
    case 'finished_unchecked':
      return `GW ${gameweek} has finished but FPL has not confirmed it: bonus and automatic substitutions may still change.`
    case 'final':
      return `GW ${gameweek} is final.`
    case 'final_scores_provisional':
      return `FPL marks GW ${gameweek} final, but one or more displayed score snapshots were read before that confirmation. Treat those scores as provisional; final bonus or automatic substitutions may be absent.`
    case 'unknown':
      return `Whether GW ${gameweek} is finished could not be read from FPL.`
  }
}

export function formatChipName(chip: string): string {
  const names: Record<string, string> = {
    wildcard: 'Wildcard',
    freehit: 'Free Hit',
    'free hit': 'Free Hit',
    bboost: 'Bench Boost',
    'bench boost': 'Bench Boost',
    '3xc': 'Triple Captain',
    'triple captain': 'Triple Captain',
    manager: 'Assistant Manager',
    'assistant manager': 'Assistant Manager',
  }
  return names[chip] ?? chip
}

function isServerResponse(value: unknown): value is ServerResponse {
  if (!isRecord(value) || typeof value.status !== 'string') return false
  if (value.status === 'ready') return isReady(value)
  return value.status === 'no_team_id'
    || value.status === 'team_not_found'
    || value.status === 'no_squad_yet'
    || value.status === 'unavailable'
}

function isReady(value: unknown): value is ReadyResponse {
  if (!isRecord(value)) return false
  return isRecord(value.entry)
    && isInteger(value.entry.id)
    && typeof value.entry.name === 'string'
    && isInteger(value.lockedGameweek)
    && (value.deadline === null || typeof value.deadline === 'string')
    && typeof value.loadedAt === 'string'
    && isSquad(value.lockedSquad)
    && isChips(value.chips)
    && isContinuing(value.continuing)
    && isGameweekPoints(value.gameweekPoints)
}

function isSquad(value: unknown): value is PageSquad {
  return isRecord(value)
    && isInteger(value.bank)
    && isInteger(value.value)
    && (value.activeChip === null || typeof value.activeChip === 'string')
    && Array.isArray(value.players)
    && value.players.every(isPlayer)
}

function isPlayer(value: unknown): value is SquadPlayer {
  return isRecord(value)
    && isInteger(value.elementId)
    && isInteger(value.pickPosition)
    && typeof value.captain === 'boolean'
    && typeof value.viceCaptain === 'boolean'
    && typeof value.position === 'string'
    && typeof value.name === 'string'
    && (value.club === null || typeof value.club === 'string')
    && (value.marketPrice === null || isInteger(value.marketPrice))
    && (value.availability === null || typeof value.availability === 'string')
    && (value.gameweekPoints === null || isInteger(value.gameweekPoints))
    && (value.gameweekMinutes === null || isInteger(value.gameweekMinutes))
}

function isChips(value: unknown): value is ChipAvailability {
  return isRecord(value)
    && typeof value.known === 'boolean'
    && isInteger(value.gameweek)
    && isStringArray(value.playable)
    && isStringArray(value.openingLater)
    && (value.openingGameweek === null || isInteger(value.openingGameweek))
}

function isGameweekPoints(value: unknown): value is GameweekPoints {
  return isRecord(value)
    && isInteger(value.gameweek)
    && (value.status === 'in_progress' || value.status === 'finished_unchecked'
      || value.status === 'final' || value.status === 'final_scores_provisional'
      || value.status === 'unknown')
    && (value.entryTotal === null || isInteger(value.entryTotal))
    && (value.playersAsOf === null || typeof value.playersAsOf === 'string')
}

function isContinuing(value: unknown): value is Continuing {
  if (!isRecord(value)) return false
  if (value.status === 'not_applicable') return true
  if (value.status === 'unknown') return typeof value.reason === 'string'
  return value.status === 'available'
    && isInteger(value.sourceGameweek)
    && isInteger(value.bank)
    && isInteger(value.value)
    && Array.isArray(value.players)
    && value.players.every(isPlayer)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}
