import { createClient, type Session } from '@supabase/supabase-js';

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
// The project URL keeps a default. It is not a credential — it is already written into
// application.yml and the deploy workflow — and a required placeholder for it bought an outage
// once already (see docs/STATUS.md, 2026-08-19).
// `||` rather than `??`, as in vite.config.ts: an empty VITE_SUPABASE_URL is not a project URL,
// and createClient throws on it at module load — a blank page and a console message.
const supabaseUrl = import.meta.env.VITE_SUPABASE_URL || 'https://example-project.supabase.co';
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
    detectSessionInUrl: (url, params) => {
      // The SDK already parsed params. Replace the token-bearing entry before its hash setter
      // creates another history entry; the same params still reach its verification path.
      // An empty fragment makes that setter a no-op rather than a new fragment navigation.
      if (url.pathname === '/reset-password') {
        globalThis.history.replaceState(globalThis.history.state, '', '/reset-password#');
      }
      // Preserve the SDK's implicit callback detection, including signup on the homepage.
      return Boolean(
        params.access_token || params.error || params.error_description || params.error_code,
      );
    },
  },
});

/** SDK updates must not overwrite a shared session that changed while the request was pending. */
export async function updateRecoveryPassword(session: Session, password: string) {
  const isolated = createClient(supabaseUrl, supabaseAnonKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
      storageKey: `password-reset-${crypto.randomUUID()}`,
    },
  });
  try {
    const { error } = await isolated.auth.setSession({
      access_token: session.access_token,
      refresh_token: session.refresh_token,
    });
    return error ? { data: { user: null }, error } : await isolated.auth.updateUser({ password });
  } finally {
    await isolated.auth.dispose();
  }
}

/** The current access token, or null when signed out. Refreshed by the client as needed. */
export async function accessToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}
