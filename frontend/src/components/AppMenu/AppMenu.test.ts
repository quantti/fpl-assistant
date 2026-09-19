// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';

vi.mock('../../auth/supabaseClient', () => ({ supabase: { auth: { signOut: vi.fn() } } }));
vi.mock('../../router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../router')>();
  return { ...actual, navigate: vi.fn() };
});
// Mantine's Menu needs a MantineProvider and a portal; neither is what this test is about. The
// stand-ins render every item as a plain button so the dropdown's contents can be read directly.
vi.mock('@mantine/core', () => {
  const Menu = ({ children }: { children: React.ReactNode }) =>
    React.createElement('div', null, children);
  Menu.Target = ({ children }: { children: React.ReactNode }) =>
    React.createElement('div', null, children);
  Menu.Dropdown = ({ children }: { children: React.ReactNode }) =>
    React.createElement('div', null, children);
  Menu.Item = ({ children, onClick }: { children: React.ReactNode; onClick?: () => void }) =>
    React.createElement('button', { type: 'button', onClick }, children);
  Menu.Divider = () => React.createElement('hr');
  return {
    Menu,
    Burger: () => React.createElement('button', { type: 'button' }, 'Menu'),
    useMantineColorScheme: () => ({ setColorScheme: vi.fn() }),
    useComputedColorScheme: () => 'light',
  };
});
import { navigate } from '../../router';
import AppMenu from './AppMenu';

let root: ReturnType<typeof createRoot> | undefined;

beforeAll(() => {
  (
    globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
  vi.mocked(navigate).mockReset();
});

function mount(admin: boolean, route: '/app' | '/settings' = '/app'): HTMLDivElement {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(AppMenu, { route, admin })));
  return host;
}

function labels(host: HTMLElement): string[] {
  return [...host.querySelectorAll('button')].map((button) => button.textContent ?? '');
}

it('offers Admin only to an admin, and it opens /admin', () => {
  const host = mount(true);
  expect(labels(host)).toContain('Admin');

  const admin = [...host.querySelectorAll('button')].find((b) => b.textContent === 'Admin')!;
  act(() => admin.click());
  expect(vi.mocked(navigate).mock.calls).toEqual([['/admin']]);
});

it('has no Admin item for everyone else', () => {
  const host = mount(false);
  expect(labels(host)).not.toContain('Admin');
});

it('returns application pages to chat at /app', () => {
  const host = mount(false, '/settings');
  const back = [...host.querySelectorAll('button')].find(
    (button) => button.textContent === 'Back to chat',
  )!;

  act(() => back.click());

  expect(vi.mocked(navigate).mock.calls).toEqual([['/app']]);
});
