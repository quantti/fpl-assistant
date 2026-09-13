import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { apiFetch } from '../auth/apiFetch'
import type { CataloguePlayer, PlayerCatalogue } from '../types'
import classes from './ShortlistContext.module.css'

export interface ShortlistPlayer {
  catalogueId: string
  season: string
  elementId: number
  webName: string
  fullName: string
  teamId: number
  teamShort: string
  teamName: string
  elementType: number
  createdAt: string
}

interface ShortlistState {
  players: ShortlistPlayer[]
  loading: boolean
  error: string | null
  loadFailed: boolean
  count: number | null
  retry(): Promise<void>
  isSelected(season: string, elementId: number): boolean
  isPending(season: string, elementId: number): boolean
  toggle(catalogue: PlayerCatalogue, player: CataloguePlayer): Promise<void>
}

const ShortlistContext = createContext<ShortlistState | null>(null)
const LOAD_ERROR = 'Could not load your shortlist. Try again.'
const UPDATE_ERROR = 'Could not update your shortlist. Try again.'
const keyOf = (season: string, elementId: number) => `${season}:${elementId}`

export function ShortlistProvider({ children }: { children: ReactNode }) {
  const [players, setPlayers] = useState<ShortlistPlayer[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [pending, setPending] = useState<Set<string>>(() => new Set())
  const playersRef = useRef(players)
  const aliveRef = useRef(true)
  const loadRef = useRef<{ token: symbol; controller: AbortController } | null>(null)
  const requestRef = useRef(new Map<string, { token: symbol; controller: AbortController }>())
  playersRef.current = players

  const retry = useCallback(async () => {
    loadRef.current?.controller.abort()
    const request = { token: Symbol('shortlist-load'), controller: new AbortController() }
    loadRef.current = request
    setLoading(true)
    try {
      const response = await apiFetch('/api/shortlist', { signal: request.controller.signal })
      if (!response.ok) throw new Error('shortlist load failed')
      const loaded = await response.json() as ShortlistPlayer[]
      if (!aliveRef.current || loadRef.current?.token !== request.token) return
      setPlayers(loaded)
      setError(null)
      setLoadFailed(false)
    } catch {
      if (aliveRef.current && loadRef.current?.token === request.token) {
        setError(LOAD_ERROR)
        setLoadFailed(true)
      }
    } finally {
      if (aliveRef.current && loadRef.current?.token === request.token) {
        loadRef.current = null
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    aliveRef.current = true
    void retry()
    return () => {
      aliveRef.current = false
      loadRef.current?.controller.abort()
      loadRef.current = null
      for (const request of requestRef.current.values()) request.controller.abort()
      requestRef.current.clear()
    }
  }, [retry])

  const toggle = useCallback(async (catalogue: PlayerCatalogue, player: CataloguePlayer) => {
    const key = keyOf(catalogue.season, player.elementId)
    if (loading || loadFailed || requestRef.current.has(key)) return

    const selected = playersRef.current.some(item =>
      item.season === catalogue.season && item.elementId === player.elementId)
    const request = { token: Symbol(key), controller: new AbortController() }
    requestRef.current.set(key, request)
    setPending(previous => new Set(previous).add(key))
    setError(null)

    try {
      const response = await apiFetch('/api/shortlist', {
        method: selected ? 'DELETE' : 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(selected
          ? { season: catalogue.season, elementId: player.elementId }
          : {
              catalogueId: catalogue.catalogueId,
              season: catalogue.season,
              elementId: player.elementId,
            }),
        signal: request.controller.signal,
      })
      if (!response.ok) throw new Error('shortlist update failed')
      if (!aliveRef.current || requestRef.current.get(key)?.token !== request.token) return

      if (selected) {
        setPlayers(previous => previous.filter(item =>
          item.season !== catalogue.season || item.elementId !== player.elementId))
      } else {
        const stored = await response.json() as ShortlistPlayer
        if (!aliveRef.current || requestRef.current.get(key)?.token !== request.token) return
        setPlayers(previous => [
          ...previous.filter(item => item.season !== stored.season || item.elementId !== stored.elementId),
          stored,
        ])
      }
    } catch {
      if (aliveRef.current && requestRef.current.get(key)?.token === request.token) setError(UPDATE_ERROR)
    } finally {
      if (aliveRef.current && requestRef.current.get(key)?.token === request.token) {
        requestRef.current.delete(key)
        setPending(previous => {
          const next = new Set(previous)
          next.delete(key)
          return next
        })
      }
    }
  }, [loading, loadFailed])

  const value = useMemo<ShortlistState>(() => ({
    players,
    loading,
    error,
    loadFailed,
    count: loading || loadFailed ? null : players.length,
    retry,
    isSelected: (season, elementId) => players.some(player =>
      player.season === season && player.elementId === elementId),
    isPending: (season, elementId) => pending.has(keyOf(season, elementId)),
    toggle,
  }), [players, loading, error, loadFailed, pending, retry, toggle])

  return (
    <ShortlistContext.Provider value={value}>
      {children}
      {error && <div className={classes.error} role="alert">{error}</div>}
    </ShortlistContext.Provider>
  )
}

export function useShortlist(): ShortlistState {
  const state = useContext(ShortlistContext)
  if (!state) throw new Error('useShortlist must be used inside ShortlistProvider')
  return state
}
