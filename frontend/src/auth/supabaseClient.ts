import { createClient } from '@supabase/supabase-js';

// The project URL and the publishable key are public by design: they ship inside this bundle,
// and anyone who opens devtools can read them. What keeps the database safe is row level
// security — every table has it enabled with no policies, so this key reaches nothing — plus the
// fact that the backend never trusts the client for identity: it verifies the JWT signature
// against Supabase's published JWKS and reads the user id from the token's `sub` claim.
//
// The key is supplied at build time and has no fallback here: safe to publish is not the same as
// belonging in the source tree, and a key committed once is a key you have to rotate to remove.
// vite.config.ts refuses to build without it, so a missing key fails the deploy rather than
// shipping a bundle that throws on first paint.
//
// Deployment configuration selects the operator's project; Vite refuses a missing URL.
const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

// Auth only. This client must never be used to query the database directly: authorization lives
// in the backend's SQL, which filters on the token's `sub`, and a direct query would route around
// it entirely.
export const supabase = createClient(supabaseUrl, supabaseAnonKey, {
  auth: {
    // Keeps the session across reloads and refreshes the access token before it expires, so a
    // one-hour token does not become a surprise sign-out mid-conversation.
    persistSession: true,
    autoRefreshToken: true,
    // The confirmation link sends the browser back here with the tokens in the URL fragment, and
    // this is what turns them into a session. It defaults to true, and is written out because the
    // email-verification flow depends on it — a later tidy-up that "removes a default" would
    // leave people who confirmed their address staring at the sign-in form.
    detectSessionInUrl: true,
  },
});

/** The current access token, or null when signed out. Refreshed by the client as needed. */
export async function accessToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}
