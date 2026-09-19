// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }));
vi.mock('@mantine/core', () => ({ Loader: () => React.createElement('span', null, 'Loading') }));
import { apiFetch } from '../../auth/apiFetch';
import type { Usage } from '../../types';
import UsagePage from './UsagePage';

const apiFetchMock = vi.mocked(apiFetch);
let root: ReturnType<typeof createRoot> | undefined;
let host: HTMLDivElement;

async function mount(usage: Partial<Usage>) {
  apiFetchMock.mockResolvedValueOnce(
    Response.json({
      plan: 'FREE',
      remainingPercent: 40,
      unlimited: false,
      nextFreeAt: null,
      used: 12,
      limit: 20,
      ...usage,
    }),
  );
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(React.createElement(UsagePage));
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeAll(() => {
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', true);
});

beforeEach(() => apiFetchMock.mockReset());
afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
});

it('names the deadline a gameweek window ends at, and never calls it this gameweek', async () => {
  await mount({
    window: { kind: 'GAMEWEEK', gameweek: 13, season: '2026/27' },
    resetsAt: '2026-09-26T10:30:00Z',
  });

  expect(host.textContent).toContain('of your messages for gameweek 13 remaining');
  expect(host.textContent).toContain('12 of 20 messages used');
  expect(host.textContent).toContain('Resets at the gameweek 13 deadline');
  // The round being played during this window is GW12, so "this gameweek" would read two ways.
  expect(host.textContent).not.toContain('this gameweek');
  expect(host.textContent).not.toContain("this week's");
});

it('says off-season when there is no round to name', async () => {
  await mount({
    remainingPercent: 88,
    used: 12,
    limit: 100,
    window: { kind: 'OFF_SEASON', season: '2025/26' },
  });

  expect(host.textContent).toContain('of your off-season messages remaining');
  expect(host.textContent).toContain('12 of 100 messages used');
  // No capture ends this window yet, so no reset is claimed.
  expect(host.textContent).toContain('when the new season');
  expect(host.textContent).not.toContain('gameweek');
});

it('falls back to the rolling week when the schedule is unreadable', async () => {
  await mount({ window: { kind: 'FALLBACK' } });

  expect(host.textContent).toContain("of this week's messages remaining");
  expect(host.textContent).toContain('12 of 20 messages used in the last seven days');
  expect(host.textContent).not.toContain('Resets at');
});

it('tells a blocked caller when they may send again', async () => {
  await mount({
    remainingPercent: 0,
    used: 20,
    limit: 20,
    nextFreeAt: '2026-09-26T10:30:00Z',
    window: { kind: 'GAMEWEEK', gameweek: 13, season: '2026/27' },
    resetsAt: '2026-09-26T10:30:00Z',
  });

  expect(host.textContent).toContain('You can send again on');
});

it('states an unlimited account has no limit at all, not no weekly limit', async () => {
  await mount({ unlimited: true, used: undefined, limit: undefined, window: { kind: 'FALLBACK' } });

  expect(host.textContent).toContain('This account has no message limit.');
});

it('shows a loader until the answer arrives', () => {
  // A request that never settles is the loading state, with no resolver to hold on to.
  apiFetchMock.mockReturnValueOnce(new Promise(() => {}));
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(UsagePage)));

  expect(host.textContent).toContain('Loading');
});

it('says so when usage cannot be loaded', async () => {
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 500 }));
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(React.createElement(UsagePage));
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(host.textContent).toContain('Usage could not be loaded.');
});
