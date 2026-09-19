// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '@supabase/supabase-js';
import type { Route } from './router';
import { useSession } from './auth/useSession';

let route: Route = '/';
let appMounts = 0;
let appUnmounts = 0;
const navigate = vi.hoisted(() => vi.fn());

vi.mock('./auth/useSession', () => ({ useSession: vi.fn() }));
vi.mock('./hooks/useKeyboardAwareViewport', () => ({ useKeyboardAwareViewport: vi.fn() }));
vi.mock('./router', () => ({ navigate, useRoute: () => route }));
vi.mock('@mantine/core', () => ({ Loader: () => React.createElement('span', null, 'loading') }));
vi.mock('./components/ChatView/ChatView', () => ({
  default: ({ route: current }: Readonly<{ route: Route }>) => {
    React.useEffect(() => {
      appMounts++;
      return () => {
        appUnmounts++;
      };
    }, []);
    return React.createElement('span', { 'data-view': 'app' }, current);
  },
}));
vi.mock('./components/LandingPage/LandingPage', () => ({
  default: ({ signedIn, sessionLoading }: { signedIn: boolean; sessionLoading: boolean }) =>
    React.createElement('span', {
      'data-view': 'landing',
      'data-signed-in': String(signedIn),
      'data-session-loading': String(sessionLoading),
    }),
}));
vi.mock('./components/PrivacyPage/PrivacyPage', () => ({
  default: () => React.createElement('span', { 'data-view': 'privacy' }),
}));
vi.mock('./components/StorageNotice/StorageNotice', () => ({
  default: () => React.createElement('span', { 'data-view': 'storage-notice' }),
}));
vi.mock('./components/SignIn/SignIn', () => ({
  default: ({ initialMode }: { initialMode: string }) =>
    React.createElement('span', { 'data-view': 'auth', 'data-mode': initialMode }),
}));
vi.mock('./shortlist/ShortlistContext', () => ({
  ShortlistProvider: ({ children }: { children: React.ReactNode }) => children,
}));
import App from './App';

const sessionMock = vi.mocked(useSession);
let root: ReturnType<typeof createRoot> | undefined;

beforeAll(() => {
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', {
    configurable: true,
    value: true,
  });
});

beforeEach(() => {
  route = '/';
  navigate.mockReset();
  appMounts = 0;
  appUnmounts = 0;
  sessionMock.mockReturnValue({ session: null, loading: false });
});

afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
});

function renderApp(): HTMLElement {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(App)));
  return host;
}

describe('public and protected routing', () => {
  it('shows the public homepage immediately while the session is loading', () => {
    sessionMock.mockReturnValue({ session: null, loading: true });

    const landing = renderApp().querySelector('[data-view="landing"]');

    expect((landing as HTMLElement | null)?.dataset.sessionLoading).toBe('true');
  });

  it('keeps the homepage visible to a signed-in visitor', () => {
    sessionMock.mockReturnValue({ session: session('user-a'), loading: false });

    const landing = renderApp().querySelector('[data-view="landing"]');

    expect((landing as HTMLElement | null)?.dataset.signedIn).toBe('true');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows privacy without waiting for authentication', () => {
    route = '/privacy';
    sessionMock.mockReturnValue({ session: null, loading: true });

    const host = renderApp();

    expect(host.querySelector('[data-view="privacy"]')).not.toBeNull();
    expect(host.querySelector('[data-view="auth"]')).toBeNull();
  });

  it.each([
    ['/login', 'signIn'],
    ['/register', 'signUp'],
  ] as const)('selects the expected form at %s', (path, mode) => {
    route = path;

    const auth = renderApp().querySelector('[data-view="auth"]');

    expect((auth as HTMLElement | null)?.dataset.mode).toBe(mode);
  });

  it.each([
    { path: '/register' as const, auth: null },
    { path: '/app' as const, auth: session('user-a') },
  ])('shows the storage notice at $path', ({ path, auth }) => {
    route = path;
    sessionMock.mockReturnValue({ session: auth, loading: false });

    expect(renderApp().querySelector('[data-view="storage-notice"]')).not.toBeNull();
  });

  it('places the notice after the current page in one layout frame', () => {
    const host = renderApp();
    const page = host.querySelector('[data-view="landing"]')!.parentElement!;
    const notice = host.querySelector('[data-view="storage-notice"]')!;

    expect(page.parentElement).toBe(notice.parentElement);
    expect(page.nextElementSibling).toBe(notice);
  });

  it('opens a protected deep link after authentication without replacing it', () => {
    route = '/settings';
    const host = renderApp();
    expect(host.querySelector('[data-view="auth"]')).not.toBeNull();

    sessionMock.mockReturnValue({ session: session('user-a'), loading: false });
    act(() => root!.render(React.createElement(App)));

    expect(host.querySelector('[data-view="app"]')?.textContent).toBe('/settings');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('sends a completed login to the canonical chat path', () => {
    route = '/login';
    sessionMock.mockReturnValue({ session: session('user-a'), loading: false });

    renderApp();

    expect(navigate).toHaveBeenCalledWith('/app');
  });

  it('replaces account-owned application state when the authenticated user changes', () => {
    route = '/app';
    sessionMock.mockReturnValue({ session: session('user-a'), loading: false });
    renderApp();

    sessionMock.mockReturnValue({ session: session('user-b'), loading: false });
    act(() => root!.render(React.createElement(App)));

    expect(appMounts).toBe(2);
    expect(appUnmounts).toBe(1);
  });
});

function session(id: string): Session {
  return {
    access_token: 'fixture-token',
    refresh_token: 'fixture-refresh',
    expires_in: 3600,
    token_type: 'bearer',
    user: { id, aud: 'authenticated', app_metadata: {}, user_metadata: {}, created_at: '' },
  };
}
