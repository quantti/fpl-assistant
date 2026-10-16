import { useEffect, useState } from 'react';
import { apiFetch } from '../auth/apiFetch';
import type { Comparison } from './comparison';

interface State {
  key: string;
  data: Comparison | null;
  loading: boolean;
  failed: boolean;
}

export function useComparison(membershipKey: string) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<State>({ key: '', data: null, loading: false, failed: false });
  useEffect(() => {
    if (!membershipKey) return;
    const controller = new AbortController();
    let isActive = true;
    const load = async () => {
      setState({ key: membershipKey, data: null, loading: true, failed: false });
      try {
        const response = await apiFetch('/api/shortlist/comparison', { signal: controller.signal });
        if (!response.ok) throw new Error('Comparison unavailable');
        const data = (await response.json()) as Comparison;
        if (isActive) setState({ key: membershipKey, data, loading: false, failed: false });
      } catch {
        if (isActive) setState({ key: membershipKey, data: null, loading: false, failed: true });
      }
    };
    void load();
    return () => {
      isActive = false;
      controller.abort();
    };
  }, [membershipKey, attempt]);
  const current =
    membershipKey && state.key === membershipKey
      ? state
      : { data: null, loading: Boolean(membershipKey), failed: false };
  return { ...current, retry: () => setAttempt((previous) => previous + 1) };
}
