// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import SignIn from './SignIn';

const auth = vi.hoisted(() => ({ resetPasswordForEmail: vi.fn(), signInWithPassword: vi.fn() }));
vi.mock('../../auth/supabaseClient', () => ({ supabase: { auth } }));
vi.mock('../../auth/apiFetch', () => ({ consumeSessionEnded: () => false }));
let root: ReturnType<typeof createRoot>;
let host: HTMLDivElement;

beforeAll(() => {
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', {
    value: true,
    configurable: true,
  });
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
beforeEach(() => {
  vi.clearAllMocks();
  auth.resetPasswordForEmail.mockResolvedValue({ data: {}, error: null });
  globalThis.history.replaceState(null, '', '/login');
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root.render(React.createElement(MantineProvider, null, React.createElement(SignIn))));
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
});
function button(text: string) {
  const result = [...host.querySelectorAll('button')].find((b) => b.textContent === text);
  expect(result, `button ${text}`).toBeDefined();
  return result!;
}
function enterEmail() {
  act(() => button('Forgot password?').click());
  const input = host.querySelector('input[type="email"]')!;
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(
      input,
      ' test@example.com ',
    );
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function submit() {
  await act(async () => {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await Promise.resolve();
  });
}

it('requests recovery with a trimmed email and fixed same-origin redirect, then returns to login', async () => {
  enterEmail();
  await submit();
  expect(auth.resetPasswordForEmail).toHaveBeenCalledWith('test@example.com', {
    redirectTo: `${globalThis.location.origin}/reset-password`,
  });
  expect(host.textContent).toContain('If an account exists');
  act(() => button('Back to sign in').click());
  expect(host.querySelector('input[autocomplete="current-password"]')).not.toBeNull();
});

it.each(['user_not_found', 'email_not_confirmed', 'user_banned'])(
  'does not reveal %s',
  async (code) => {
    auth.resetPasswordForEmail.mockResolvedValue({
      error: { code, status: 400, message: 'private account detail' },
    });
    enterEmail();
    await submit();
    expect(host.textContent).toContain('If an account exists');
    expect(host.textContent).not.toContain('private account detail');
    expect(host.querySelector('[role="alert"]')).toBeNull();
  },
);

it.each([429, 500, 0])('reports a safe retry for infrastructure status %s', async (status) => {
  auth.resetPasswordForEmail.mockResolvedValue({
    error: { status, message: 'private provider detail' },
  });
  enterEmail();
  await submit();
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('try again');
  expect(host.textContent).not.toContain('private provider detail');
  expect(button('Send reset link').disabled).toBe(false);
});

it('recovers from a thrown error', async () => {
  auth.resetPasswordForEmail.mockRejectedValue(new Error('private storage detail'));
  enterEmail();
  await submit();
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('try again');
  expect(button('Send reset link').disabled).toBe(false);
});

it('admits only one in-flight request even with repeated submit events', async () => {
  let resolve!: (value: unknown) => void;
  // ES2022 is the app's target; Promise.withResolvers requires ES2024.
  auth.resetPasswordForEmail.mockReturnValue(
    // eslint-disable-next-line unicorn/prefer-promise-with-resolvers
    new Promise((finish) => {
      resolve = finish;
    }),
  );
  enterEmail();
  act(() => {
    const form = host.querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  expect(auth.resetPasswordForEmail).toHaveBeenCalledTimes(1);
  await act(async () => {
    resolve({ error: null });
    await Promise.resolve();
  });
});

it('keeps account creation available without a recovery entry point', () => {
  act(() => button('Create an account').click());
  expect(host.querySelector('input[autocomplete="new-password"]')).not.toBeNull();
  expect(host.textContent).not.toContain('Forgot password?');
  expect(auth.resetPasswordForEmail).not.toHaveBeenCalled();
});
