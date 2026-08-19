import { createClient } from '@supabase/supabase-js'

// The project URL and the publishable key are public by design: they ship inside this bundle,
// and anyone who opens devtools can read them. What keeps the database safe is row level
// security — every table has it enabled with no policies, so this key reaches nothing — plus the
// fact that the backend never trusts the client for identity: it verifies the JWT signature
// against Supabase's published JWKS and reads the user id from the token's `sub` claim.
//
// Baked as defaults rather than required env vars so the Docker build needs no build arguments.
// Override either with VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY when pointing at another
// project.
const supabaseUrl = import.meta.env.VITE_SUPABASE_URL ?? 'https://itmykooxrbrdbgqwsesb.supabase.co'
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY ?? 'sb_publishable_amQGCnukyvPrtpWW5XEwlg_o55I_C0Q'

// Auth only. This client must never be used to query the database directly: authorization lives
// in the backend's SQL, which filters on the token's `sub`, and a direct query would route around
// it entirely.
export const supabase = createClient(supabaseUrl, supabaseAnonKey, {
  auth: {
    // Keeps the session across reloads and refreshes the access token before it expires, so a
    // one-hour token does not become a surprise sign-out mid-conversation.
    persistSession: true,
    autoRefreshToken: true,
  },
})

/** The current access token, or null when signed out. Refreshed by the client as needed. */
export async function accessToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession()
  return data.session?.access_token ?? null
}
