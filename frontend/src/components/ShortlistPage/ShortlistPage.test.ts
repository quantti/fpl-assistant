// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }));
vi.mock('@mantine/core', () => ({ Loader: () => React.createElement('span', null, 'Loading') }));
import { apiFetch } from '../../auth/apiFetch';
import { ShortlistProvider, useShortlist } from '../../shortlist/ShortlistContext';
import ShortlistPage from './ShortlistPage';

const apiFetchMock = vi.mocked(apiFetch);
let root: ReturnType<typeof createRoot> | undefined;
let host: HTMLDivElement;

const stored = {
  catalogueId: 'catalogue-1',
  season: '2026-27',
  elementId: 17,
  webName: 'Saka',
  fullName: 'Bukayo Saka',
  teamId: 1,
  teamShort: 'ARS',
  teamName: 'Arsenal',
  elementType: 3,
  createdAt: '2026-09-13T10:00:00Z',
};

const comparison = {
  asOf: '2026-09-09T12:00:00Z',
  horizon: 'GW6–10 · 5 editable gameweeks',
  sources: ['Observed totals: season 2026-27'],
  rows: [
    {
      season: '2026-27',
      elementId: 17,
      market: {
        webName: 'Saka',
        fullName: 'Bukayo Saka',
        teamShort: 'ARS',
        position: 3,
        nowCost: 60,
        status: 'd',
        news: 'Knock',
        chanceOfPlayingNextRound: 50,
        ownership: 12,
        penalties: 1,
        directFreeKicks: null,
        corners: 2,
        capturedAt: '2026-09-09T01:00:00Z',
      },
      totals: {
        points: 60,
        minutes: 450,
        goals: 2,
        assists: 3,
        cleanSheets: 2,
        saves: 0,
        bonus: 4,
        xg: 1.2,
        xa: 0.3,
        xgi: 1.5,
        source: 'Stored-data age: record time, not live check.',
      },
      pointsPerMillion: 10,
      reason: null,
      forecast: {
        expectedCleanSheets: 1.5,
        reason: null,
        weeks: [
          {
            gameweek: 6,
            fixtures: [
              { id: 1, opponent: 'CHE', home: true, difficulty: 3, cleanSheetProbability: 0.3 },
              { id: 2, opponent: 'LIV', home: false, difficulty: 5, cleanSheetProbability: 0.2 },
            ],
          },
          { gameweek: 7, fixtures: [] },
          { gameweek: 8, fixtures: [] },
          { gameweek: 9, fixtures: [] },
          { gameweek: 10, fixtures: [] },
        ],
      },
      history: [
        {
          id: 3,
          gameweek: 5,
          opponent: 'FUL',
          home: true,
          kickoff: '2026-09-07T15:00:00Z',
          minutes: 0,
          points: 0,
          goals: 0,
          assists: 0,
          xg: null,
          xa: 0,
          xgi: null,
          defcon: null,
          evidence: 'LEGACY',
        },
      ],
      historyReason: null,
      defcon: { threshold: 12, hits: 2, readings: 4, actionsPer90: 9, observedAppearances: 4 },
      observationSources: ['Stored match coverage: known rows only.'],
    },
  ],
};

function deferredResponse() {
  let finish!: (response: Response) => void;
  // eslint-disable-next-line unicorn/prefer-promise-with-resolvers -- TypeScript lib targets ES2022.
  const promise = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  return { promise, finish };
}

function Shell() {
  const shortlist = useShortlist();
  return React.createElement(
    React.Fragment,
    null,
    React.createElement('span', { 'data-count': true }, `count:${shortlist.count ?? '—'}`),
    React.createElement(ShortlistPage),
  );
}

async function mount(response: Response) {
  apiFetchMock.mockResolvedValueOnce(response);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(React.createElement(ShortlistProvider, null, React.createElement(Shell)));
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeAll(() => {
  (
    globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
});

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(() => Promise.resolve(Response.json(comparison)));
});
afterEach(() => {
  if (root) act(() => root!.unmount());
  root = undefined;
  document.body.replaceChildren();
});

it('renders loading, load-error and empty states', async () => {
  const { promise, finish } = deferredResponse();
  apiFetchMock.mockReturnValueOnce(promise);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => root!.render(React.createElement(ShortlistProvider, null, React.createElement(Shell))));
  expect(host.textContent).toContain('Loading your shortlist');

  await act(async () => {
    finish(new Response(null, { status: 500 }));
    await Promise.resolve();
  });
  expect(host.textContent).toContain('Your shortlist could not be loaded');
  expect(host.textContent).toContain('count:—');
  expect(
    [...host.querySelectorAll('button')].some((button) => button.textContent === 'Retry'),
  ).toBe(true);

  act(() => root!.unmount());
  root = undefined;
  await mount(Response.json([]));
  expect(host.textContent).toContain('Your shortlist is empty');
});

it('uses shared table columns and opens a detail card from the player name', async () => {
  await mount(Response.json([stored, { ...stored, elementId: 18, fullName: 'Alpha' }]));
  const table = host.querySelector('table[aria-label="Player comparison"]');
  expect(table).not.toBeNull();
  const headers = [...table!.querySelectorAll(':scope > thead th')].map((cell) =>
    cell.textContent?.replace(/ [↑↓↕]$/, ''),
  );
  expect(headers).toEqual([
    'Player',
    'Club',
    'Pos',
    'Availability',
    'Price',
    'Points',
    'Points/£m',
    'xG',
    'xA',
    'xGI',
    'Team xCS',
    'GW6',
    'GW7',
    'GW8',
    'GW9',
    'GW10',
    'Actions',
  ]);
  expect(table!.querySelectorAll(':scope > tbody > tr')).toHaveLength(2);
  expect(host.textContent).not.toContain('ARS · Midfielder · 2026/27 season');
  expect(table!.textContent).toContain('MID');
  const rows = table!.querySelectorAll(':scope > tbody > tr');
  expect(rows[0].querySelector(':scope > :nth-child(12)')?.textContent).toBe('n/a');
  expect(rows[1].querySelector(':scope > :nth-child(12)')?.textContent).toContain('CHE (H)');
  expect(rows[1].querySelector(':scope > :nth-child(12)')?.textContent).toContain('LIV (A)');
  expect(rows[1].querySelector(':scope > :nth-child(13)')?.textContent).toBe('Blank');
  expect(host.textContent).not.toContain('Observed season totals');
  const button = host.querySelector<HTMLButtonElement>(
    'button[aria-expanded="false"][aria-label="Details for Bukayo Saka"]',
  )!;
  act(() => button.click());
  expect(button.getAttribute('aria-expanded')).toBe('true');
  const card = host.querySelector('section[aria-label="Bukayo Saka details"]');
  expect(card?.id).toBe(button.getAttribute('aria-controls'));
  expect(card?.textContent).toContain('Observed season totals');
  expect(host.textContent).toContain('DefCon');
  act(() => button.click());
  expect(host.textContent).not.toContain('Observed season totals');
});

it('shows name, club and position, then removes through shared state', async () => {
  await mount(Response.json([stored]));
  expect(host.textContent).toContain('Bukayo Saka');
  expect(host.textContent).toContain('ARS');
  expect(host.textContent).toContain('MID');
  expect(host.textContent).toContain('count:1');

  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
  const remove = [...host.querySelectorAll('button')].find(
    (button) => button.textContent === 'Remove',
  )!;
  await act(async () => {
    remove.click();
    await Promise.resolve();
  });

  const deletion = apiFetchMock.mock.calls.find((call) => call[1]?.method === 'DELETE');
  expect(deletion).toBeDefined();
  expect(JSON.parse(deletion![1]!.body as string)).toEqual({
    season: '2026-27',
    elementId: 17,
  });
  expect(host.textContent).not.toContain('Bukayo Saka');
  expect(host.textContent).toContain('count:0');
  expect(host.textContent).toContain('Your shortlist is empty');
});

it('compares all three xG metrics, value and five gameweeks with detail evidence', async () => {
  await mount(Response.json([stored]));
  for (const label of [
    'xG',
    'xA',
    'xGI',
    'Points/£m',
    'Team xCS',
    '£6.0m',
    'GW6',
    'GW10',
    'CHE (H)',
    'LIV (A)',
    'Blank',
    'Doubtful',
  ]) {
    expect(host.textContent).toContain(label);
  }
  expect(host.querySelector('details')).not.toBeNull();
  act(() =>
    host.querySelector<HTMLButtonElement>('button[aria-label="Details for Bukayo Saka"]')!.click(),
  );
  expect(host.textContent).toContain('DefCon');
  expect(host.textContent).toContain('Stored-data age');
  expect(host.textContent).toContain('n/a');
  const table = host.querySelector('table[aria-label="Player comparison"]')!;
  const metric = (label: string) => {
    const index = [...table.querySelectorAll(':scope > thead th')].findIndex(
      (cell) => cell.textContent?.replace(/ [↑↓↕]$/, '') === label,
    );
    return table.querySelector(':scope > tbody > tr')!.children[index].textContent;
  };
  expect(metric('Points/£m')).toBe('10.0');
  expect(metric('xG')).toBe('1.20');
  expect(metric('xA')).toBe('0.30');
  expect(metric('xGI')).toBe('1.50');
  expect(
    host.querySelector(':scope section[aria-label="Bukayo Saka details"] tbody tr')?.textContent,
  ).toContain('FUL (H)0000n/a0.00n/a');
});

it('keeps removal usable on comparison failure and retries independently', async () => {
  apiFetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 503 })));
  await mount(Response.json([stored]));
  expect(host.textContent).toContain('Comparison unavailable');
  expect(host.textContent).toContain('count:1');
  apiFetchMock.mockImplementation(() => Promise.resolve(Response.json(comparison)));
  await act(async () => {
    host.querySelector<HTMLButtonElement>('button:not([aria-label])')!.click();
    await Promise.resolve();
  });
  expect(host.textContent).toContain('£6.0m');
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
  await act(async () => {
    host.querySelector<HTMLButtonElement>('button[aria-label="Remove Bukayo Saka"]')!.click();
    await Promise.resolve();
  });
  expect(host.textContent).toContain('count:0');
});

it('puts colour-coded availability in its own cell and sorts from headers both ways', async () => {
  const other = { ...stored, elementId: 18, fullName: 'Alpha', teamShort: 'MCI' };
  apiFetchMock.mockImplementation(() =>
    Promise.resolve(
      Response.json({
        ...comparison,
        rows: [
          comparison.rows[0],
          {
            ...comparison.rows[0],
            elementId: 18,
            market: {
              ...comparison.rows[0].market,
              fullName: 'Alpha',
              status: 'a',
              chanceOfPlayingNextRound: null,
              nowCost: 90,
            },
          },
        ],
      }),
    ),
  );
  await mount(Response.json([stored, other]));
  const table = host.querySelector('table[aria-label="Player comparison"]')!;
  expect(table.querySelector('th[scope="row"]')?.textContent).not.toContain('Available');
  expect(table.querySelector('[data-status="a"]')?.textContent).toBe('Available');
  expect(table.querySelector('[data-status="d"]')?.textContent).toBe('Doubtful · 50%');
  expect(table.querySelector<HTMLElement>('[data-status="d"]')?.dataset.flag).toBe('amber');
  expect(table.querySelectorAll(':scope > thead button')).toHaveLength(16);
  const price = [...table.querySelectorAll(':scope > thead button')].find((button) =>
    button.textContent?.startsWith('Price'),
  ) as HTMLButtonElement;
  expect(price).toBeDefined();
  act(() => price.click());
  expect(price.parentElement?.getAttribute('aria-sort')).toBe('descending');
  expect(table.querySelector(':scope > tbody button[aria-expanded]')?.textContent).toContain(
    'Alpha',
  );
  act(() => price.click());
  expect(price.parentElement?.getAttribute('aria-sort')).toBe('ascending');
  expect(table.querySelector(':scope > tbody button[aria-expanded]')?.textContent).toContain(
    'Bukayo Saka',
  );
});

it('summarises source limits in two sentences with a readable UTC date', async () => {
  await mount(Response.json([stored]));
  const sources = host.querySelector('details');
  expect(sources?.textContent).toContain('9 Sept 2026, 12:00 UTC');
  expect(sources?.textContent).toContain('Statistical refresh times are unverified');
  expect(sources?.textContent).toContain('uncalibrated team estimate');
  expect(sources?.querySelectorAll('p')).toHaveLength(1);
  expect(sources?.textContent).not.toContain('2026-09-09T12:00:00Z');
  expect(sources?.textContent).not.toContain('Observed totals:');
});

it('removes a player while comparison remains unavailable', async () => {
  apiFetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 503 })));
  await mount(Response.json([stored]));
  expect(host.textContent).toContain('Comparison unavailable');
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
  await act(async () => {
    host.querySelector<HTMLButtonElement>('button[aria-label="Remove Bukayo Saka"]')!.click();
    await Promise.resolve();
  });
  expect(host.textContent).toContain('count:0');
  expect(host.textContent).toContain('Your shortlist is empty');
});

it('sorts missing metrics last and filters positions without changing membership', async () => {
  const second = { ...stored, elementId: 18, fullName: 'Alpha', elementType: 2 };
  apiFetchMock.mockImplementation(() =>
    Promise.resolve(
      Response.json({
        ...comparison,
        rows: [
          comparison.rows[0],
          {
            ...comparison.rows[0],
            elementId: 18,
            market: null,
            totals: null,
            pointsPerMillion: null,
          },
        ],
      }),
    ),
  );
  await mount(Response.json([stored, second]));
  expect(host.querySelector('button[aria-expanded]')?.textContent).toContain('Alpha');
  const selects = host.querySelectorAll('select');
  const valueHeader = [...host.querySelectorAll(':scope thead button')].find((button) =>
    button.textContent?.startsWith('Points/£m'),
  ) as HTMLButtonElement;
  act(() => valueHeader.click());
  expect(host.querySelector('button[aria-expanded]')?.textContent).toContain('Bukayo Saka');
  act(() => valueHeader.click());
  expect(host.querySelector('button[aria-expanded]')?.textContent).toContain('Bukayo Saka');
  act(() => {
    selects[0].value = '2';
    selects[0].dispatchEvent(new Event('change', { bubbles: true }));
  });
  expect(
    host.querySelectorAll(':scope table[aria-label="Player comparison"] > tbody > tr'),
  ).toHaveLength(1);
  expect(host.querySelector('button[aria-expanded]')?.textContent).toContain('Alpha');
  expect(host.textContent).toContain('count:2');
});

it('aborts comparison and ignores late completion after removal', async () => {
  const { promise, finish } = deferredResponse();
  apiFetchMock.mockImplementation(() => promise);
  await mount(Response.json([stored]));
  const signal = apiFetchMock.mock.calls.find(
    (call) => call[0] === '/api/shortlist/comparison',
  )![1]!.signal!;
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
  await act(async () => {
    host.querySelector<HTMLButtonElement>('button[aria-label="Remove Bukayo Saka"]')!.click();
    await Promise.resolve();
  });
  expect(signal.aborted).toBe(true);
  await act(async () => {
    finish(Response.json(comparison));
    await Promise.resolve();
  });
  expect(host.textContent).toContain('Your shortlist is empty');
  expect(host.textContent).not.toContain('£6.0m');
});

it('ignores an old account response after the account-keyed provider remounts', async () => {
  const { promise, finish } = deferredResponse();
  apiFetchMock.mockImplementation(() => promise);
  await mount(Response.json([stored]));
  const signal = apiFetchMock.mock.calls.find(
    (call) => call[0] === '/api/shortlist/comparison',
  )![1]!.signal!;
  apiFetchMock.mockResolvedValueOnce(Response.json([]));
  await act(async () => {
    root!.render(
      React.createElement(ShortlistProvider, {
        key: 'another-account',
        children: React.createElement(Shell),
      }),
    );
    await Promise.resolve();
  });
  expect(signal.aborted).toBe(true);
  await act(async () => {
    finish(Response.json(comparison));
    await Promise.resolve();
  });
  expect(host.textContent).toContain('count:0');
  expect(host.textContent).not.toContain('Bukayo Saka');
});

it('uses the current captured name rather than the frozen catalogue name', async () => {
  apiFetchMock.mockImplementation(() =>
    Promise.resolve(
      Response.json({
        ...comparison,
        rows: [
          {
            ...comparison.rows[0],
            market: { ...comparison.rows[0].market, fullName: 'Updated Name' },
          },
        ],
      }),
    ),
  );
  await mount(Response.json([stored]));
  expect(host.querySelector('button[aria-expanded]')?.textContent).toContain('Updated Name');
  expect(host.querySelector('button[aria-label="Remove Updated Name"]')).not.toBeNull();
});

it('never attaches another season with the same element id', async () => {
  await mount(Response.json([{ ...stored, season: '2025-26' }]));
  expect(host.textContent).not.toContain('£6.0m');
  act(() =>
    host.querySelector<HTMLButtonElement>('button[aria-label="Details for Bukayo Saka"]')!.click(),
  );
  expect(host.textContent).toContain('2025/26 season');
});
