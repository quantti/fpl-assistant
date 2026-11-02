// @vitest-environment jsdom

import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Session, SupabaseClient } from '@supabase/supabase-js';
import type { updateRecoveryPassword } from './supabaseClient';

let client: SupabaseClient;
let updatePassword: typeof updateRecoveryPassword;
let finishUpdate: () => void;
let putStarted: Promise<void>;
let signalPut: () => void;
const calls: string[] = [];

const encode = (value: object) => btoa(JSON.stringify(value));
function session(id: string): Session {
  const token = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: id, exp: 4_102_444_800 })}.c2lnbmF0dXJl`;
  return {
    access_token: token,
    refresh_token: `refresh-${id}`,
    token_type: 'bearer',
    expires_in: 3600,
    user: { id, aud: 'authenticated', app_metadata: {}, user_metadata: {}, created_at: '' },
  };
}

beforeEach(async () => {
  vi.resetModules();
  localStorage.clear();
  globalThis.history.replaceState(null, '', '/reset-password');
  vi.stubEnv('VITE_SUPABASE_URL', 'http://127.0.0.1:1');
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'test-dummy');
  calls.length = 0;
  // ES2022 has no Promise.withResolvers.
  // eslint-disable-next-line unicorn/prefer-promise-with-resolvers
  putStarted = new Promise((resolve) => {
    signalPut = resolve;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init: RequestInit) => {
      calls.push(`${init.method} ${new URL(url).pathname}`);
      const authorization = new Headers(init.headers).get('authorization');
      const id =
        authorization === `Bearer ${session('account-b').access_token}` ? 'account-b' : 'account-a';
      if (init.method === 'PUT') {
        signalPut();
        return new Promise<Response>((resolve) => {
          finishUpdate = () => resolve(Response.json({ user: session(id).user }));
        });
      }
      return Promise.resolve(Response.json(session(id).user));
    }),
  );
  const module = await import('./supabaseClient');
  client = module.supabase;
  updatePassword = module.updateRecoveryPassword;
  await client.auth.setSession(session('account-a'));
});
afterEach(async () => {
  await client.auth.dispose();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  localStorage.clear();
});

it('does not overwrite a replacement session when the SDK finishes the old password update', async () => {
  const updating = updatePassword(session('account-a'), 'new-password');
  await putStarted;
  await client.auth.setSession(session('account-b'));
  const storedBefore = localStorage.getItem('sb-127-auth-token');
  finishUpdate();
  expect((await updating).error).toBeNull();
  expect((await client.auth.getSession()).data.session?.user.id).toBe('account-b');
  expect(localStorage.getItem('sb-127-auth-token')).toBe(storedBefore);
  expect(calls.some((call) => call.includes('/logout'))).toBe(false);
});

it('uses the original form identity even if the shared client already switched accounts', async () => {
  await client.auth.setSession(session('account-b'));
  const updating = updatePassword(session('account-a'), 'new-password');
  await putStarted;
  finishUpdate();
  const result = await updating;
  expect(result.data?.user?.id).toBe('account-a');
  expect((await client.auth.getSession()).data.session?.user.id).toBe('account-b');
});
