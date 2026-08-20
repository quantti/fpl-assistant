import { supabase, accessToken } from './supabaseClient'

/**
 * `fetch` for `/api/**`: attaches the access token and treats a `401` as the end of the session.
 *
 * The token is read per request rather than captured once, because the client refreshes it in
 * the background — a captured one would be the expired copy an hour into a conversation.
 * `getSession()` refreshes it when it is close to expiring, so this is also where the silent
 * refresh happens.
 *
 * A `401` means the token is gone or no longer accepted, and the stored session is then a lie:
 * signing out clears it, which flips the session subscription and returns the user to the
 * sign-in screen instead of leaving them looking at a chat that answers nothing.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken()
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, { ...init, headers })
  if (response.status === 401) {
    await supabase.auth.signOut()
  }
  return response
}
