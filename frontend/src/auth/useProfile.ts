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
