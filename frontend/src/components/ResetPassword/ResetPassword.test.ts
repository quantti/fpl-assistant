// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import type { Session } from '@supabase/supabase-js';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import ResetPassword, { type ResetPasswordProps } from './ResetPassword';

const auth = vi.hoisted(() => ({
  updateUser: vi.fn(),
  signOut: vi.fn(),
  resetPasswordForEmail: vi.fn(),
}));
vi.mock('../../auth/supabaseClient', () => ({ supabase: { auth } }));
let root: ReturnType<typeof createRoot>;
let host: HTMLDivElement;
const session = { user: { id: 'user-a' } } as Session;
let props: ResetPasswordProps;
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
  vi.resetAllMocks();
  auth.updateUser.mockResolvedValue({ data: { user: session.user }, error: null });
  auth.signOut.mockResolvedValue({ error: null });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  props = { session, initializationFailed: false };
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
});
function render() {
  act(() =>
    root.render(
      React.createElement(MantineProvider, null, React.createElement(ResetPassword, props)),
    ),
  );
}
function button(label: string) {
  const found = [...host.querySelectorAll('button')].find((b) => b.textContent === label);
  expect(found, label).toBeDefined();
  return found!;
}
function passwords(first = ' new secret password ', second = first) {
  const inputs = [...host.querySelectorAll('input[autocomplete="new-password"]')];
  expect(inputs).toHaveLength(2);
  act(() => {
    for (const [index, input] of inputs.entries()) {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(
        input,
        index === 0 ? first : second,
      );
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
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

it('updates the exact password, clears fields and signs out locally before offering login', async () => {
  render();
  passwords();
  await submit();
  expect(auth.updateUser).toHaveBeenCalledWith({ password: ' new secret password ' });
  expect(auth.signOut).toHaveBeenCalledWith({ scope: 'local' });
  expect(host.textContent).toContain('Your password has been updated');
  expect(host.querySelector('input')).toBeNull();
  expect(host.querySelector('a[href="/login"]')).not.toBeNull();
  props = { ...props, session: null };
  render();
  expect(host.textContent).toContain('Your password has been updated');
});
it('does not submit mismatching passwords', async () => {
  render();
  passwords('one password', 'different password');
  await submit();
  expect(auth.updateUser).not.toHaveBeenCalled();
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('do not match');
});
it.each([
  { session: null, initializationFailed: false },
  { session, initializationFailed: true },
])('offers a new link instead of changing a password with invalid recovery state', (state) => {
  props = state;
  render();
  expect(host.querySelector('input')).toBeNull();
  expect(host.textContent).toContain('Request a new reset link');
  act(() => button('Request a new reset link').click());
  expect(host.querySelector('input[type="email"]')).not.toBeNull();
  expect(auth.updateUser).not.toHaveBeenCalled();
});
it.each(['weak_password', 'same_password'])(
  'explains rejected password %s safely',
  async (code) => {
    auth.updateUser.mockResolvedValue({ error: { code, status: 422, message: 'private detail' } });
    render();
    passwords();
    await submit();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('different');
    expect(host.textContent).not.toContain('private detail');
    expect(auth.signOut).not.toHaveBeenCalled();
    expect(button('Update password').disabled).toBe(false);
  },
);
it.each([401, 403])(
  'offers a new recovery link when update loses authentication (%s)',
  async (status) => {
    auth.updateUser.mockResolvedValue({ error: { status, message: 'private detail' } });
    render();
    passwords();
    await submit();
    expect(host.textContent).toContain('Request a new reset link');
    expect(host.querySelector('input')).toBeNull();
  },
);
it('shows a retry after a thrown update failure', async () => {
  auth.updateUser.mockRejectedValue(new Error('private detail'));
  render();
  passwords();
  await submit();
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('try again');
  expect(button('Update password').disabled).toBe(false);
  expect(host.textContent).not.toContain('private detail');
});
it.each(['returned', 'thrown'])(
  'does not report password failure when local sign-out fails (%s)',
  async (kind) => {
    if (kind === 'returned')
      auth.signOut.mockResolvedValue({ error: { message: 'private detail' } });
    else auth.signOut.mockRejectedValue(new Error('private detail'));
    render();
    passwords();
    await submit();
    expect(host.textContent).toContain('Your password has been updated');
    expect(host.textContent).toContain('could not sign out');
    expect(host.querySelector('input')).toBeNull();
    auth.signOut.mockResolvedValue({ error: null });
    await act(async () => {
      button('Try signing out again').click();
      await Promise.resolve();
    });
    expect(auth.updateUser).toHaveBeenCalledTimes(1);
    expect(host.querySelector('a[href="/login"]')).not.toBeNull();
  },
);
it('discards a previous account’s sign-out retry when another account arrives', async () => {
  auth.signOut.mockResolvedValue({ error: { message: 'offline' } });
  render();
  passwords();
  await submit();
  expect(button('Try signing out again')).toBeDefined();
  props = { ...props, session: { user: { id: 'user-b' } } as Session };
  render();
  expect(host.textContent).not.toContain('Try signing out again');
  expect(host.textContent).not.toContain('Your password has been updated');
  expect(host.querySelectorAll('input[autocomplete="new-password"]')).toHaveLength(2);
  expect(auth.signOut).toHaveBeenCalledTimes(1);
});

it('clears password fields when the active account changes', () => {
  render();
  passwords();
  props = { ...props, session: { user: { id: 'user-b' } } as Session };
  render();
  for (const input of host.querySelectorAll('input')) expect(input.value).toBe('');
});
it('does not sign out a replacement account when an old update completes late', async () => {
  let finish!: (value: unknown) => void;
  auth.updateUser.mockReturnValue(
    // ES2022 has no Promise.withResolvers.
    // eslint-disable-next-line unicorn/prefer-promise-with-resolvers
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  render();
  passwords();
  await submit();
  props = { ...props, session: { user: { id: 'user-b' } } as Session };
  render();
  await act(async () => {
    finish({ error: null });
    await Promise.resolve();
  });
  expect(auth.signOut).not.toHaveBeenCalled();
  expect(host.textContent).not.toContain('Your password has been updated');
});

it('does not submit twice while a request is pending', async () => {
  let finish!: (value: unknown) => void;
  auth.updateUser.mockReturnValue(
    // ES2022 has no Promise.withResolvers.
    // eslint-disable-next-line unicorn/prefer-promise-with-resolvers
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  render();
  passwords();
  act(() => {
    const form = host.querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  expect(auth.updateUser).toHaveBeenCalledTimes(1);
  await act(async () => {
    finish({ error: null });
    await Promise.resolve();
  });
});
