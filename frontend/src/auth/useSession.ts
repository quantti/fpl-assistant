import { useEffect, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { supabase } from './supabaseClient'

/**
 * The signed-in session, or null. `loading` is true until the first answer arrives — without it
 * the app would flash the sign-in screen on every reload before the stored session is read.
 */
export function useSession() {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session)
      setLoading(false)
    })

    // Covers sign-in, sign-out, and the silent token refresh — the last of which is why this
    // subscription exists rather than a one-off read.
    const { data: subscription } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next)
    })

    return () => subscription.subscription.unsubscribe()
  }, [])

  return { session, loading }
}
