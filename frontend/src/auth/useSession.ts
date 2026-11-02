import { useEffect, useState } from 'react';
import type { Session } from '@supabase/supabase-js';
import { supabase } from './supabaseClient';

/**
 * The signed-in session, or null. `loading` is true until the first answer arrives — without it
 * the app would flash the sign-in screen on every reload before the stored session is read.
 */
export function useSession() {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [initializationFailed, setInitializationFailed] = useState(() => {
    if (globalThis.location.pathname !== '/reset-password') return false;
    const query = new URLSearchParams(globalThis.location.search);
    const fragment = new URLSearchParams(globalThis.location.hash.slice(1));
    return ['error', 'error_code', 'error_description'].some(
      (key) => query.has(key) || fragment.has(key),
    );
  });

  useEffect(() => {
    async function restore() {
      try {
        const initialized = await supabase.auth.initialize();
        // getSession alone can return an older session after a failed recovery callback.
        if (initialized.error) setInitializationFailed(true);
        const { data, error } = await supabase.auth.getSession();
        if (error) setInitializationFailed(true);
        setSession(data.session);
      } catch {
        // Blocked browser storage must not leave the page loading forever.
        setInitializationFailed(true);
        setSession(null);
      } finally {
        setLoading(false);
      }
    }
    void restore();

    // Covers sign-in, sign-out, and the silent token refresh — the last of which is why this
    // subscription exists rather than a one-off read.
    const { data: subscription } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next);
    });

    return () => subscription.subscription.unsubscribe();
  }, []);

  return { session, loading, initializationFailed };
}
