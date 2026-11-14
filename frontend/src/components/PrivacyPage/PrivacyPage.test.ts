// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import PrivacyPage from './PrivacyPage';

let root: ReturnType<typeof createRoot> | undefined;

beforeAll(() => {
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', true);
});

beforeEach(() => {
  vi.stubEnv('VITE_PRIVACY_CONTROLLER_NAME', 'Example Operator');
  vi.stubEnv('VITE_PRIVACY_CONTACT_EMAIL', 'privacy@example.com');
});

afterEach(() => {
  vi.unstubAllEnvs();
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

  expect(text).toContain('Example Operator');
  expect(page.querySelector('a[href="mailto:privacy@example.com"]')).not.toBeNull();
  expect(text).not.toContain('admin@fpl-sidekick.com');
  expect(text).toContain('Supabase Authentication handles your email and password');
  expect(text).toContain('We rely on performance of our contract');
  expect(text).toContain('We rely on our legitimate interests');
  expect(text).not.toContain('proposed legal basis');
  expect(text).toContain('no optional analytics or advertising tracking');
  expect(text).toContain('FPL entry ID');
  expect(text).toContain('cached for minutes');
  expect(text).toContain('sent to Google Gemini through the Gemini Developer API');
  expect(text).toContain('This is an API-key integration, not Vertex AI');
  expect(text).toContain('Gemini project has Cloud Billing linked');
  expect(text).toContain('Developer Generate Content logging is disabled');
  expect(text).toContain('currently has no logging datasets');
  expect(text).toContain('for 55 days for policy enforcement');
  expect(text).toContain('do not make a retrospective promise about earlier feedback sharing');
  expect(text).toContain('OpenAI is used only to create embeddings');
  expect(text).toContain('does not generate the chat answer');
  expect(text).toContain('not used to train or improve its models unless the customer opts in');
  expect(text).toContain('all three sharing categories enabled');
  expect(text).toContain('API call logging is disabled');
  expect(text).toContain('Global residency');
  expect(text).toContain('retained for up to 30 days');
  expect(page.querySelector('a[href="https://ai.google.dev/gemini-api/terms"]')).not.toBeNull();
  expect(
    page.querySelector('a[href="https://ai.google.dev/gemini-api/docs/usage-policies"]'),
  ).not.toBeNull();
  expect(
    page.querySelector('a[href="https://platform.openai.com/docs/guides/your-data"]'),
  ).not.toBeNull();
  expect(text).toContain('Free plan');
  expect(text).toContain('Ireland (eu-west-1)');
  expect(text).toContain('runs on Fly.io in Paris');
  expect(page.querySelector('a[href="https://supabase.com/legal/dpa"]')).not.toBeNull();
  expect(page.querySelector('a[href="https://resend.com/legal/dpa"]')).not.toBeNull();
  expect(page.querySelector('a[href="https://fly.io/documents"]')).not.toBeNull();
  expect(text).toContain('not a blanket guarantee about every provider account');
  expect(text).toContain('public data from the official Fantasy Premier League API');
  expect(text).toContain('Resend documents 30-day retention');
  expect(text).toContain('Open and click tracking are disabled');
  expect(text).toContain('process your IP address');
  expect(text).toContain('removed after 14 days');
  expect(text).toContain('approved maximum for ordinary application logs is 14 days');
  expect(text).toContain('no external error-monitoring or log-export service');
  expect(text).toContain('keep them for the account’s lifetime');
  expect(text).toContain('Account-erasure tooling is not implemented yet');
  expect(text).toContain('approved but unimplemented policy is 90 days');
  expect(text).toContain('no automatic deletion of inactive accounts during testing');
  expect(text).toContain('no self-service account deletion, data export');
  expect(text).toContain('currently operate no separate database backup');
  expect(text).toContain('approved target, not an active feature');
  expect(text).toContain('session storage only');
});

it('states rights, limitations and useful destinations without claiming compliance', () => {
  const page = renderPage();
  const text = page.textContent ?? '';

  expect(text).toContain('does not claim that the service has been independently assessed');
  expect(text).toContain('access, correction, erasure, restriction or portability');
  expect(text).toContain('object to processing based on legitimate interests');
  expect(text).toContain('normally respond within one month');
  expect(text).toContain('minimum information needed to verify your identity');
  expect(text).toContain('tooling is still being built');
  expect(text).toContain('non-reusable consumed-place record');
  expect(page.querySelector('a[href="https://tietosuoja.fi/en/home"]')).not.toBeNull();
  expect(text).toContain('does not make solely automated decisions');
  expect(text).toContain('advice is not guaranteed to be correct');
  expect(page.querySelector('a[href="/app"]')).not.toBeNull();
});
