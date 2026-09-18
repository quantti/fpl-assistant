// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';

vi.mock('../../auth/supabaseClient', () => ({ supabase: { auth: { signOut: vi.fn() } } }));
vi.mock('../../router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../router')>();
  return { ...actual, navigate: vi.fn() };
});
import { navigate } from '../../router';
import { APP_MENU_LINKS } from '../AppMenu/AppMenu';
import HeaderNavigation from './HeaderNavigation';

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

it('keeps My team and Shortlist in dedicated navigation', () => {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(HeaderNavigation, { route: '/shortlist' })));

  const buttons = Array.from(host.querySelectorAll('button'));
  expect(buttons.map((button) => button.textContent)).toEqual(['My team', 'Shortlist']);
  expect(buttons[1].getAttribute('aria-current')).toBe('page');

  act(() => buttons[0].click());
  act(() => buttons[1].click());
  expect(vi.mocked(navigate).mock.calls).toEqual([['/my-team'], ['/shortlist']]);
});

it('does not duplicate My team or Shortlist in the overflow menu', () => {
  expect(APP_MENU_LINKS.map((item) => item.label)).toEqual(['Usage', 'Settings']);
});
