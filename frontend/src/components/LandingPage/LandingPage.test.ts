// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, expect, it } from 'vitest';
import LandingPage from './LandingPage';

let root: ReturnType<typeof createRoot> | undefined;
let host: HTMLDivElement;

beforeAll(() => {
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', true);
});

afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
});

function renderLandingPage(isSignedIn = false, isSessionLoading = false): HTMLElement {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() =>
    root!.render(
      React.createElement(LandingPage, {
        signedIn: isSignedIn,
        sessionLoading: isSessionLoading,
      }),
    ),
  );
  return host;
}

function linksByText(element: HTMLElement): Map<string, string | null> {
  return new Map(
    [...element.querySelectorAll('a')].map((link) => [
      link.textContent?.trim() ?? '',
      link.getAttribute('href'),
    ]),
  );
}

it('presents the product honestly with registration and login paths', () => {
  const page = renderLandingPage();
  const links = linksByText(page);

  expect(page.querySelector('h1')?.textContent).toBe('Your FPL decisions, backed by data.');
  expect(page.textContent).toContain('Now free');
  expect(page.textContent).toContain('Free during testing · Invite code required');
  expect(page.textContent).toContain('Compare players in conversation');
  expect(page.textContent).toContain('Plan your next move');
  expect(page.textContent).toContain('Bring your team into it');
  expect(page.textContent).toContain('Keep a shortlist');
  expect(page.textContent).toContain('Not affiliated with or endorsed by the Premier League.');
  expect(links.get('Register free')).toBe('/register');
  expect(links.get('Register')).toBe('/register');
  expect(links.get('Log in')).toBe('/login');
  expect(links.get('Privacy')).toBe('/privacy');
});

it('offers the app instead of sign-up actions to a signed-in visitor', () => {
  const page = renderLandingPage(true);
  const links = linksByText(page);

  expect(links.get('Open FPL Sidekick')).toBe('/app');
  expect(links.get('Open the app')).toBe('/app');
  expect(links.has('Register')).toBe(false);
  expect(page.textContent).not.toContain('Invite code required');
});

it('exposes session loading without hiding the public homepage', () => {
  const page = renderLandingPage(false, true);

  expect(page.firstElementChild?.getAttribute('aria-busy')).toBe('true');
  expect(page.querySelector('h1')).not.toBeNull();
});
