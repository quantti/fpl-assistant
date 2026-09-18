// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import type { PlayerCatalogue } from '../types';

vi.mock('../auth/apiFetch', () => ({ apiFetch: vi.fn() }));
import { apiFetch } from '../auth/apiFetch';
import { useConversations } from './useConversations';

const apiFetchMock = vi.mocked(apiFetch);
let state: ReturnType<typeof useConversations>;
let root: ReturnType<typeof createRoot>;

function Probe() {
  state = useConversations();
  return null;
}

const catalogue: PlayerCatalogue = {
  catalogueId: 'catalogue-1',
  season: '2026-27',
  players: [],
};

beforeAll(() => {
  (
    globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
});
beforeEach(async () => {
  apiFetchMock.mockReset();
  apiFetchMock.mockResolvedValueOnce(Response.json([]));
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root.render(React.createElement(Probe));
    await Promise.resolve();
    await Promise.resolve();
  });
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
});

it('resolves deduplicated catalogue history only for assistant messages', async () => {
  apiFetchMock.mockResolvedValueOnce(
    Response.json({
      messages: [
        { role: 'user', content: 'Saka?', catalogueId: 'catalogue-1' },
        { role: 'assistant', content: 'Saka.', catalogueId: 'catalogue-1' },
        { role: 'assistant', content: 'Same evidence.', catalogueId: 'catalogue-1' },
        { role: 'assistant', content: 'Legacy answer.' },
      ],
      catalogues: { 'catalogue-1': catalogue },
    }),
  );

  let messages;
  await act(async () => {
    messages = await state.select('conversation-1');
  });

  expect(messages).toEqual([
    { role: 'user', content: 'Saka?' },
    { role: 'assistant', content: 'Saka.', catalogue },
    { role: 'assistant', content: 'Same evidence.', catalogue },
    { role: 'assistant', content: 'Legacy answer.' },
  ]);
});

it('still accepts legacy array history with optional inline catalogue metadata', async () => {
  apiFetchMock.mockResolvedValueOnce(
    Response.json([
      { role: 'assistant', content: 'Saka.', catalogue },
      { role: 'assistant', content: 'Plain.' },
    ]),
  );

  let messages;
  await act(async () => {
    messages = await state.select('conversation-legacy');
  });

  expect(messages).toEqual([
    { role: 'assistant', content: 'Saka.', catalogue },
    { role: 'assistant', content: 'Plain.' },
  ]);
});
