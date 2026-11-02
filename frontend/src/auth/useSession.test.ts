// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from './useSession';

const auth = vi.hoisted(() => ({
  initialize: vi.fn(),
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
}));
vi.mock('./supabaseClient', () => ({ supabase: { auth } }));
let root: ReturnType<typeof createRoot>;
let host: HTMLDivElement;
let notify: (event: string, session: unknown) => void;
const session = { user: { id: 'test-user' } };
function Probe() {
  return React.createElement('output', null, JSON.stringify(useSession()));
}
beforeEach(() => {
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', {
    value: true,
    configurable: true,
  });
  auth.initialize.mockResolvedValue({ error: null });
  auth.getSession.mockResolvedValue({ data: { session }, error: null });
  auth.onAuthStateChange.mockImplementation((callback: typeof notify) => {
    notify = callback;
    return { data: { subscription: { unsubscribe: vi.fn() } } };
  });
  globalThis.history.replaceState(
    null,
    '',
    '/reset-password#error_code=otp_expired&error_description=private',
  );
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.clearAllMocks();
});
async function mount() {
  await act(async () => {
    root.render(React.createElement(Probe));
    await Promise.resolve();
  });
}

it('rejects failed callback initialization despite an existing session and clears callback URL', async () => {
  auth.initialize.mockResolvedValue({ error: new Error('invalid callback') });
  await mount();
  expect(host.textContent).toContain('"initializationFailed":true');
  expect(host.textContent).toContain('"loading":false');
  expect(globalThis.location.hash).toBe('');
  // A later auth notification cannot turn a failed callback into a successful recovery.
  act(() => notify('INITIAL_SESSION', session));
  expect(host.textContent).toContain('"initializationFailed":true');
});

it('keeps recovery tokens until SDK initialization completes, then clears the URL', async () => {
  globalThis.history.replaceState(
    null,
    '',
    '/reset-password#access_token=test-token&type=recovery',
  );
  let finish!: (value: unknown) => void;
  auth.initialize.mockReturnValue(
    // ES2022 has no Promise.withResolvers.
    // eslint-disable-next-line unicorn/prefer-promise-with-resolvers
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  await mount();
  expect(globalThis.location.hash).toContain('access_token');
  expect(host.textContent).toContain('"loading":true');
  await act(async () => {
    finish({ error: null });
    await Promise.resolve();
  });
  expect(globalThis.location.hash).toBe('');
  expect(host.textContent).toContain('"initializationFailed":false');
  expect(host.textContent).toContain('test-user');
  act(() => notify('PASSWORD_RECOVERY', session));
  expect(host.textContent).toContain('test-user');
});

it.each(['?error=denied', '#error_code=otp_expired', '#error_description=private'])(
  'blocks callback error %s even if SDK reports an old valid session',
  async (suffix) => {
    globalThis.history.replaceState(null, '', `/reset-password${suffix}`);
    await mount();
    expect(host.textContent).toContain('"initializationFailed":true');
    expect(globalThis.location.search + globalThis.location.hash).toBe('');
  },
);

it('restores a session on a clean recovery reload', async () => {
  globalThis.history.replaceState(null, '', '/reset-password');
  await mount();
  expect(host.textContent).toContain('test-user');
  expect(host.textContent).toContain('"initializationFailed":false');
});

it('finishes loading on storage failures', async () => {
  auth.initialize.mockRejectedValue(new Error('storage unavailable'));
  await mount();
  expect(host.textContent).toContain('"loading":false');
  expect(host.textContent).toContain('"initializationFailed":true');
});
