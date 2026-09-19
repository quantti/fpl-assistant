// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import type { Message, PlayerCatalogue } from '../types';

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

function deferred<T>() {
  let callbacks!: {
    resolve: (value: T) => void;
    reject: (error: Error) => void;
  };
  const promise = new Promise<T>((resolve, reject) => {
    callbacks = { resolve, reject };
  });
  return { promise, ...callbacks };
}

function select(id: string) {
  const response = deferred<Response>();
  apiFetchMock.mockReturnValueOnce(response.promise);
  let result!: Promise<Message[] | null>;
  act(() => {
    result = state.select(id);
  });
  return { ...response, result };
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

it('sets pending immediately and retains the old id until history is normalized', async () => {
  expect(state.currentId).toBeNull();
  expect(state.pendingId).toBeNull();
  act(() => state.adopt('previous'));

  const request = select('next');
  expect(apiFetchMock).toHaveBeenLastCalledWith('/api/conversations/next/messages');
  expect(state.pendingId).toBe('next');
  expect(state.currentId).toBe('previous');

  const body = deferred<unknown>();
  const response = Response.json([]);
  vi.spyOn(response, 'json').mockReturnValueOnce(body.promise);
  await act(async () => {
    request.resolve(response);
    await Promise.resolve();
  });
  expect(state.pendingId).toBe('next');
  expect(state.currentId).toBe('previous');

  const messages: Message[] = [
    { role: 'user', content: 'Question' },
    { role: 'assistant', content: 'Answer' },
  ];
  await act(async () => {
    body.resolve(messages);
    await expect(request.result).resolves.toEqual(messages);
  });
  expect(state.currentId).toBe('next');
  expect(state.pendingId).toBeNull();
});

it.each([
  { name: 'array', payload: [] },
  { name: 'envelope', payload: { messages: [] } },
])('commits empty $name history as a successful selection', async ({ payload }) => {
  act(() => state.adopt('previous'));
  const request = select('empty');

  await act(async () => {
    request.resolve(Response.json(payload));
    await expect(request.result).resolves.toEqual([]);
  });

  expect(state.currentId).toBe('empty');
  expect(state.pendingId).toBeNull();
});

it.each(['older first', 'newer first'])(
  'keeps the newest selection authoritative when responses finish %s',
  async (order) => {
    act(() => state.adopt('previous'));
    const older = select('older');
    const newer = select('newer');
    const messages: Message[] = [{ role: 'assistant', content: 'Newer history' }];
    expect(state.pendingId).toBe('newer');

    const isOlderFirst = order === 'older first';
    const first = isOlderFirst ? older : newer;
    const last = isOlderFirst ? newer : older;
    await act(async () => {
      first.resolve(Response.json(messages));
      await expect(first.result).resolves.toEqual(isOlderFirst ? null : messages);
    });
    expect(state.currentId).toBe(isOlderFirst ? 'previous' : 'newer');
    expect(state.pendingId).toBe(isOlderFirst ? 'newer' : null);

    await act(async () => {
      last.resolve(Response.json(messages));
      await expect(last.result).resolves.toEqual(isOlderFirst ? messages : null);
    });
    expect(state.currentId).toBe('newer');
    expect(state.pendingId).toBeNull();
  },
);

it('uses request ownership even when the same conversation is selected twice', async () => {
  act(() => state.adopt('previous'));
  const older = select('same');
  const newer = select('same');

  await act(async () => {
    older.resolve(Response.json([]));
    await expect(older.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBe('same');

  await act(async () => {
    newer.resolve(Response.json([]));
    await expect(newer.result).resolves.toEqual([]);
  });
  expect(state.currentId).toBe('same');
  expect(state.pendingId).toBeNull();
});

it.each(['HTTP', 'network', 'JSON'])(
  'does not let a stale %s failure clear the newest pending selection',
  async (failure) => {
    act(() => state.adopt('previous'));
    const older = select('older');
    const newer = select('newer');

    await act(async () => {
      if (failure === 'network') older.reject(new Error('Offline'));
      else
        older.resolve(failure === 'HTTP' ? new Response(null, { status: 500 }) : new Response('{'));
      await expect(older.result).resolves.toBeNull();
    });
    expect(state.currentId).toBe('previous');
    expect(state.pendingId).toBe('newer');
    expect(state.failed).toBe(false);

    await act(async () => {
      newer.resolve(Response.json([]));
      await expect(newer.result).resolves.toEqual([]);
    });
    expect(state.currentId).toBe('newer');
    expect(state.pendingId).toBeNull();
  },
);

it('does not revive an older success after the latest selection fails', async () => {
  act(() => state.adopt('previous'));
  const older = select('older');
  const newer = select('newer');

  await act(async () => {
    newer.resolve(new Response(null, { status: 500 }));
    await expect(newer.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBeNull();

  await act(async () => {
    older.resolve(Response.json([{ role: 'assistant', content: 'Old history' }]));
    await expect(older.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBeNull();
});

it('starting a new conversation clears identity and invalidates all outstanding selections', async () => {
  act(() => state.adopt('previous'));
  const older = select('older');
  const newer = select('newer');

  act(() => state.startNew());
  expect(state.currentId).toBeNull();
  expect(state.pendingId).toBeNull();

  for (const request of [newer, older]) {
    await act(async () => {
      request.resolve(Response.json([{ role: 'assistant', content: 'Late history' }]));
      await expect(request.result).resolves.toBeNull();
    });
    expect(state.currentId).toBeNull();
    expect(state.pendingId).toBeNull();
  }
});

it.each([401, 403, 500])(
  'releases pending without changing identity for HTTP %i',
  async (status) => {
    act(() => state.adopt('previous'));
    const request = select('unreadable');

    await act(async () => {
      request.resolve(new Response(null, { status }));
      await expect(request.result).resolves.toBeNull();
    });
    expect(state.currentId).toBe('previous');
    expect(state.pendingId).toBeNull();
    expect(state.failed).toBe(false);
  },
);

it('returns null and releases pending after a network failure', async () => {
  act(() => state.adopt('previous'));
  const request = select('unreadable');

  await act(async () => {
    request.reject(new Error('Offline'));
    await expect(request.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBeNull();
  expect(state.failed).toBe(false);
});

it('returns null and releases pending after invalid JSON', async () => {
  act(() => state.adopt('previous'));
  const request = select('unreadable');

  await act(async () => {
    request.resolve(new Response('{'));
    await expect(request.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBeNull();
  expect(state.failed).toBe(false);
});

it.each([
  { name: 'null', payload: null },
  { name: 'missing messages', payload: {} },
  { name: 'non-array messages', payload: { messages: {} } },
  { name: 'null message', payload: [null] },
  { name: 'missing content', payload: [{ role: 'assistant' }] },
  { name: 'invalid role', payload: [{ role: 'system', content: 'Wrong role' }] },
  {
    name: 'invalid content after a valid message',
    payload: {
      messages: [
        { role: 'user', content: 'Question' },
        { role: 'assistant', content: { text: 'Not a string' } },
      ],
    },
  },
])('rejects malformed history ($name) before committing identity', async ({ payload }) => {
  act(() => state.adopt('previous'));
  const request = select('malformed');

  await act(async () => {
    request.resolve(Response.json(payload));
    await expect(request.result).resolves.toBeNull();
  });
  expect(state.currentId).toBe('previous');
  expect(state.pendingId).toBeNull();
  expect(state.failed).toBe(false);
});

it('keeps a previous list failure independent of selection success or failure', async () => {
  apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 500 }));
  await act(async () => state.refresh());
  expect(state.failed).toBe(true);

  const success = select('readable');
  await act(async () => {
    success.resolve(Response.json([]));
    await expect(success.result).resolves.toEqual([]);
  });
  expect(state.failed).toBe(true);

  const failure = select('unreadable');
  await act(async () => {
    failure.resolve(new Response(null, { status: 500 }));
    await expect(failure.result).resolves.toBeNull();
  });
  expect(state.failed).toBe(true);
  expect(state.currentId).toBe('readable');
  expect(state.pendingId).toBeNull();
});

it('refreshes the list on 404 without holding selection loading open', async () => {
  act(() => state.adopt('previous'));
  const request = select('removed');
  const refresh = deferred<Response>();
  apiFetchMock.mockReturnValueOnce(refresh.promise);
  const settled = vi.fn();
  void request.result.then(settled);

  await act(async () => {
    request.resolve(new Response(null, { status: 404 }));
    await Promise.resolve();
    await Promise.resolve();
  });
  expect(apiFetchMock).toHaveBeenLastCalledWith('/api/conversations');
  expect(settled).toHaveBeenCalledWith(null);
  expect(state.pendingId).toBeNull();
  expect(state.currentId).toBe('previous');
  expect(state.failed).toBe(false);

  const conversations = [
    { id: 'previous', title: 'Still here', updatedAt: '2026-09-20T12:00:00Z' },
  ];
  await act(async () => {
    refresh.resolve(Response.json(conversations));
    await Promise.resolve();
    await Promise.resolve();
  });
  expect(state.conversations).toEqual(conversations);
  expect(state.pendingId).toBeNull();
  expect(state.currentId).toBe('previous');
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
