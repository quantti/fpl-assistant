// @vitest-environment jsdom

import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';

let client: SupabaseClient | undefined;
const encode = (value: object) => btoa(JSON.stringify(value));
const token = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'recovery-user', exp: 4_102_444_800 })}.c2lnbmF0dXJl`;

beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  vi.stubEnv('VITE_SUPABASE_URL', 'http://127.0.0.1:1');
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'test-dummy');
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(Response.json({ id: 'recovery-user' }))),
  );
});
afterEach(async () => {
  await client?.auth.dispose();
  client = undefined;
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  localStorage.clear();
});

it('consumes the real SDK recovery callback without leaving its token URL in a previous history entry', async () => {
  globalThis.history.replaceState(
    null,
    '',
    `/reset-password#access_token=${token}&refresh_token=private-refresh&expires_in=3600&token_type=bearer&type=recovery`,
  );
  const entries = globalThis.history.length;
  client = (await import('./supabaseClient')).supabase;
  expect((await client.auth.initialize()).error).toBeNull();
  expect((await client.auth.getSession()).data.session?.user.id).toBe('recovery-user');
  expect(globalThis.location.pathname).toBe('/reset-password');
  expect(globalThis.location.hash).toBe('');
  expect(globalThis.history).toHaveLength(entries);
});

it.each([
  '?error=access_denied&error_code=otp_expired&error_description=private',
  '#error=access_denied&error_description=private',
])('cleans failed callback %s without concealing SDK rejection', async (suffix) => {
  globalThis.history.replaceState(null, '', `/reset-password${suffix}`);
  const entries = globalThis.history.length;
  client = (await import('./supabaseClient')).supabase;
  expect((await client.auth.initialize()).error).not.toBeNull();
  expect(globalThis.location.search + globalThis.location.hash).toBe('');
  expect(globalThis.history).toHaveLength(entries);
});

it('preserves the existing signup callback on the public homepage', async () => {
  globalThis.history.replaceState(
    null,
    '',
    `/#access_token=${token}&refresh_token=private-refresh&expires_in=3600&token_type=bearer&type=signup`,
  );
  client = (await import('./supabaseClient')).supabase;
  expect((await client.auth.initialize()).error).toBeNull();
  expect((await client.auth.getSession()).data.session?.user.id).toBe('recovery-user');
  expect(globalThis.location.pathname).toBe('/');
});
