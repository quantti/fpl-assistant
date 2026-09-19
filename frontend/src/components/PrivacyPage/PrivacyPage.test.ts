// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, expect, it } from 'vitest';
import PrivacyPage from './PrivacyPage';

let root: ReturnType<typeof createRoot> | undefined;

beforeAll(() => {
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', true);
});

afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
});

function renderPage(): HTMLElement {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(PrivacyPage)));
  return host;
}

it('states the controller, purposes, storage and processing boundaries accurately', () => {
  const page = renderPage();
  const text = page.textContent ?? '';

  expect(text).toContain('quantti');
  expect(page.querySelector('a[href="mailto:quantti@users.noreply.github.com"]')).not.toBeNull();
  expect(text).toContain('Supabase Authentication handles your email and password');
  expect(text).toContain('We rely on performance of our contract');
  expect(text).toContain('We rely on our legitimate interests');
  expect(text).not.toContain('proposed legal basis');
  expect(text).toContain('no optional analytics or advertising tracking');
  expect(text).toContain('FPL entry ID');
  expect(text).toContain('cached for minutes');
  expect(text).toContain('Production is documented as using OpenAI for chat');
  expect(text).toContain('search queries are sent to OpenAI to create embeddings');
  expect(text).toContain('Fly.io in Paris');
  expect(text).toContain('process your IP address');
  expect(text).toContain('removed after 14 days');
  expect(text).toContain('ordinary operational logs');
  expect(text).toContain('14-day period above applies only to diagnostic chat traces');
  expect(text).toContain('currently have no automatic expiry configured');
  expect(text).toContain('no self-service account deletion or data export');
  expect(text).toContain('session storage only');
});

it('states rights, limitations and useful destinations without claiming compliance', () => {
  const page = renderPage();
  const text = page.textContent ?? '';

  expect(text).toContain('does not claim that the service has been independently assessed');
  expect(text).toContain('access, correction, erasure, restriction or portability');
  expect(text).toContain('object to processing based on legitimate interests');
  expect(text).toContain('normally respond within one month');
  expect(page.querySelector('a[href="https://tietosuoja.fi/en/home"]')).not.toBeNull();
  expect(text).toContain('does not make solely automated decisions');
  expect(text).toContain('advice is not guaranteed to be correct');
  expect(page.querySelector('a[href="/app"]')).not.toBeNull();
});
