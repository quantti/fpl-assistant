import { supabase, accessToken } from './supabaseClient'

let sessionEnded = false

/**
 * True once after a `401` ended the session, and false afterwards. The sign-in screen reads it
 * to say why the user is looking at it — without that, a backend that rejects every valid token
 * (a wrong `supabase.audience`, say) is an unbreakable loop with no diagnostic anywhere in the
 * UI: sign in, ask, get bounced, repeat.
 */
export function consumeSessionEnded(): boolean {
  const ended = sessionEnded
  sessionEnded = false
  return ended
}

/**
 * `fetch` for `/api/**`: attaches the access token and ends the session when the server will not
 * take it.
 *
 * The token is read per request rather than captured once, because the client refreshes it in
 * the background — a captured one would be the expired copy an hour into a conversation.
 *
 * A `401` is not on its own proof that the session is over. The backend answers `401` for
 * anything it cannot verify, and says so in as many words: `unverifiableIsUnauthorized` turns an
 * unreachable key server, or a refresh the rate limit refused, into `401` rather than `500`. So
 * one `401` buys a refresh and a single retry, and only a second one signs the user out — during
 * a Supabase blip that is the difference between a hiccup and logging everybody out at the exact
 * moment they cannot sign back in.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const response = await send(path, init)
  if (response.status !== 401) {
    return response
  }

  // Retrying is safe because every caller passes a string body; a stream body could not be sent
  // twice.
  const { data, error } = await supabase.auth.refreshSession()
  if (!error && data.session) {
    const retried = await send(path, init)
    if (retried.status !== 401) {
      return retried
    }
  }

  sessionEnded = true
  // Local scope: this browser. The default is 'global', which revokes every refresh token the
  // user holds — a spurious 401 in one tab would sign them out on their phone as well.
  await supabase.auth.signOut({ scope: 'local' })
  return response
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const token = await accessToken()
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  return fetch(path, { ...init, headers })
}
