// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';

vi.mock('../../auth/apiFetch', () => ({ apiFetch: vi.fn() }));
vi.mock('../../auth/useProfile', () => ({ useProfile: () => null }));
vi.mock('../../router', () => ({ navigate: vi.fn() }));
vi.mock('../AdminPage/AdminPage', () => ({ default: () => null }));
vi.mock('../AppMenu/AppMenu', () => ({ default: () => null }));
vi.mock('../HeaderNavigation/HeaderNavigation', () => ({ default: () => null }));
vi.mock('../MyTeamPage/MyTeamPage', () => ({ default: () => null }));
vi.mock('../SettingsPage/SettingsPage', () => ({ default: () => null }));
vi.mock('../ShortlistPage/ShortlistPage', () => ({ default: () => null }));
vi.mock('../UsagePage/UsagePage', () => ({ default: () => null }));
import { apiFetch } from '../../auth/apiFetch';
import ChatView from './ChatView';

const apiFetchMock = vi.mocked(apiFetch);
const titles = {
  old: 'Previous conversation',
  a: 'A long conversation title about transfers, captains and the coming gameweeks',
  b: 'Newest conversation',
};
type ConversationId = keyof typeof titles;
const failureCopy = 'That conversation could not be opened. It may have been removed.';
const emptyCopy = 'Ask me anything about Fantasy Premier League';
const done = 'event: done\ndata: \n\n';
let root: ReturnType<typeof createRoot>;
let host: HTMLDivElement;

function row(id: ConversationId): HTMLButtonElement {
  return [...host.querySelectorAll<HTMLButtonElement>(':scope nav li button')].find((button) =>
    button.textContent?.startsWith(titles[id]),
  )!;
}

function newButton(): HTMLButtonElement {
  return [...host.querySelectorAll('button')].find(
    (button) => button.textContent === '+ New conversation',
  )!;
}

function textarea(): HTMLTextAreaElement {
  return host.querySelector('textarea')!;
}

function sendButton(): HTMLButtonElement {
  return host.querySelector('button[aria-label="Send"]')!;
}

function typeMessage(content: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(
    textarea(),
    content,
  );
  act(() => {
    textarea().dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function flush(action: () => void) {
  await act(async () => {
    action();
    await Promise.resolve();
  });
}

function select(id: ConversationId) {
  let callbacks!: {
    resolve: (response: Response) => void;
    reject: (error: Error) => void;
  };
  apiFetchMock.mockReturnValueOnce(
    new Promise<Response>((resolve, reject) => {
      callbacks = { resolve, reject };
    }),
  );
  act(() => row(id).click());
  expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/conversations/${id}/messages`);
  return callbacks;
}

function history(content: string): Response {
  return Response.json([{ role: 'assistant', content }]);
}

async function loadPrevious() {
  const request = select('old');
  await flush(() => request.resolve(history('Previous saved answer')));
  expect(host.querySelector('[data-thread]')?.textContent).toContain('Previous saved answer');
}

function chatCalls() {
  return apiFetchMock.mock.calls.filter(([path]) => path === '/api/chat');
}

async function sendTo(conversationId: string | null) {
  typeMessage('Follow up');
  await flush(() => sendButton().click());
  expect(JSON.parse(chatCalls().at(-1)![1]!.body as string)).toEqual({
    conversationId,
    message: 'Follow up',
  });
}

function stream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value;
    },
  });
  return {
    response: new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }),
    push: (events: string) => controller.enqueue(new TextEncoder().encode(events)),
  };
}

beforeAll(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.stubGlobal('matchMedia', (media: string) => ({
    media,
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  Object.defineProperty(document, 'fonts', { configurable: true, value: new EventTarget() });
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: vi.fn(),
  });
});

afterAll(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(document, 'fonts');
  Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});

beforeEach(async () => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation((path, init) => {
    if (path === '/api/conversations') {
      return Promise.resolve(
        Response.json(
          Object.entries(titles).map(([id, title]) => ({
            id,
            title,
            updatedAt: '2026-09-20T10:00:00Z',
          })),
        ),
      );
    }
    if (path === '/api/chat' && init?.method === 'POST') {
      return Promise.resolve(new Response(done));
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await flush(() =>
    root.render(
      React.createElement(
        MantineProvider,
        { forceColorScheme: 'light' },
        React.createElement(ChatView, { route: '/app' }),
      ),
    ),
  );
});

afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
});

it('immediately hides the old thread, marks the target and disables the real composer until success', async () => {
  await loadPrevious();
  typeMessage('Unsent question');
  expect(sendButton().disabled).toBe(false);

  const request = select('a');

  expect(host.querySelector('[role="status"]')?.textContent).toBe('Loading conversation…');
  expect(host.querySelector('[data-thread]')).toBeNull();
  expect(host.textContent).not.toContain('Previous saved answer');
  expect(host.textContent).not.toContain(emptyCopy);
  expect(textarea().disabled).toBe(true);
  expect(sendButton().disabled).toBe(true);
  expect(row('old').getAttribute('aria-current')).toBe('true');
  expect(row('a').getAttribute('aria-current')).toBeNull();
  expect(row('a').getAttribute('aria-busy')).toBe('true');
  expect(row('a').textContent).toContain(titles.a);
  expect(row('a').textContent).toContain('Loading…');
  expect(row('b').disabled).toBe(false);
  expect(newButton().disabled).toBe(false);
  act(() => {
    sendButton().click();
    textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  });
  expect(chatCalls()).toHaveLength(0);

  await flush(() => request.resolve(history('Selected saved answer')));

  expect(host.querySelector('[role="status"]')).toBeNull();
  expect(host.querySelector('[aria-busy="true"]')).toBeNull();
  expect(row('a').textContent).not.toContain('Loading…');
  expect(row('old').getAttribute('aria-current')).toBeNull();
  expect(row('a').getAttribute('aria-current')).toBe('true');
  expect(host.querySelector('[data-thread]')?.textContent).toContain('Selected saved answer');
  expect(host.textContent).not.toContain('Previous saved answer');
  expect(textarea().disabled).toBe(false);
  expect(sendButton().disabled).toBe(false);
});

it('accepts empty history as success rather than a failed selection', async () => {
  await loadPrevious();
  const request = select('a');
  expect(host.querySelector('[role="status"]')?.textContent).toBe('Loading conversation…');

  await flush(() => request.resolve(Response.json([])));

  expect(host.querySelector('[role="status"]')).toBeNull();
  expect(host.querySelector('[aria-busy="true"]')).toBeNull();
  expect(host.textContent).toContain(emptyCopy);
  expect(host.textContent).not.toContain(failureCopy);
  expect(row('a').getAttribute('aria-current')).toBe('true');
  expect(textarea().disabled).toBe(false);
  await sendTo('a');
});

it.each(['HTTP', 'network'])(
  'releases loading after a %s failure and keeps the existing recovery',
  async (failure) => {
    await loadPrevious();
    typeMessage('Unsent question');
    const request = select('a');
    expect(textarea().disabled).toBe(true);

    await flush(() => {
      if (failure === 'HTTP') request.resolve(new Response(null, { status: 500 }));
      else request.reject(new Error('Offline'));
    });

    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(host.querySelector('[aria-busy="true"]')).toBeNull();
    expect(host.querySelector('[data-thread]')?.textContent).toBe(`Error${failureCopy}`);
    expect(row('old').getAttribute('aria-current')).toBe('true');
    expect(row('a').getAttribute('aria-current')).toBeNull();
    expect(textarea().disabled).toBe(false);
    expect(sendButton().disabled).toBe(false);
    await sendTo('old');
  },
);

it.each(['older first', 'newer first'])(
  'keeps the second click authoritative, including the next POST, when responses finish %s',
  async (order) => {
    await loadPrevious();
    const older = select('a');
    const newer = select('b');
    expect(row('b').getAttribute('aria-busy')).toBe('true');
    expect(row('a').getAttribute('aria-busy')).not.toBe('true');

    if (order === 'older first') {
      await flush(() => older.resolve(history('Stale answer')));
      expect(host.querySelector('[role="status"]')?.textContent).toBe('Loading conversation…');
      expect(host.textContent).not.toContain(failureCopy);
      expect(host.textContent).not.toContain('Stale answer');
      expect(row('old').getAttribute('aria-current')).toBe('true');
      expect(textarea().disabled).toBe(true);
      await flush(() => newer.resolve(history('Newest saved answer')));
    } else {
      await flush(() => newer.resolve(history('Newest saved answer')));
      await flush(() => older.resolve(history('Stale answer')));
    }

    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(host.querySelector('[aria-busy="true"]')).toBeNull();
    expect(row('b').getAttribute('aria-current')).toBe('true');
    expect(host.querySelector('[data-thread]')?.textContent).toContain('Newest saved answer');
    expect(host.textContent).not.toContain('Stale answer');
    expect(host.textContent).not.toContain(failureCopy);
    expect(textarea().disabled).toBe(false);
    await sendTo('b');
  },
);

it('New conversation clears pending selection and late history cannot restore it', async () => {
  await loadPrevious();
  const request = select('a');
  expect(host.querySelector('[role="status"]')?.textContent).toBe('Loading conversation…');

  act(() => newButton().click());

  expect(host.querySelector('[role="status"]')).toBeNull();
  expect(host.querySelector('[aria-busy="true"]')).toBeNull();
  expect(host.querySelector('[aria-current="true"]')).toBeNull();
  expect(host.textContent).toContain(emptyCopy);
  expect(textarea().disabled).toBe(false);

  await flush(() => request.resolve(history('Late saved answer')));

  expect(host.querySelector('[data-thread]')).toBeNull();
  expect(host.querySelector('[aria-current="true"]')).toBeNull();
  expect(host.textContent).toContain(emptyCopy);
  expect(host.textContent).not.toContain('Late saved answer');
  expect(host.textContent).not.toContain(failureCopy);
  await sendTo(null);
});

it('detaches a live outgoing SSE turn at click so late metadata, tokens and completion cannot reclaim the view', async () => {
  await loadPrevious();
  const outgoing = stream();
  apiFetchMock.mockResolvedValueOnce(outgoing.response);
  await sendTo('old');
  const outgoingSignal = chatCalls()[0][1]!.signal!;
  await flush(() => outgoing.push('event: token\ndata: "Outgoing partial"\n\n'));
  expect(host.textContent).toContain('Outgoing partial');
  expect(textarea().disabled).toBe(true);

  const request = select('a');
  expect(outgoingSignal.aborted).toBe(true);
  expect(host.querySelector('[role="status"]')?.textContent).toBe('Loading conversation…');
  expect(host.textContent).not.toContain('Outgoing partial');

  // The mock HTTP stream deliberately ignores abort, like data already buffered by the reader.
  await flush(() =>
    outgoing.push('event: conversation\ndata: "b"\n\nevent: token\ndata: "Late token"\n\n'),
  );
  expect(row('old').getAttribute('aria-current')).toBe('true');
  expect(row('b').getAttribute('aria-current')).toBeNull();
  expect(row('a').getAttribute('aria-busy')).toBe('true');
  expect(textarea().disabled).toBe(true);

  await flush(() => request.resolve(history('Selected saved answer')));
  const active = stream();
  apiFetchMock.mockResolvedValueOnce(active.response);
  await sendTo('a');
  await flush(() => active.push('event: token\ndata: "Active partial"\n\n'));
  const callsBeforeCompletion = apiFetchMock.mock.calls.length;

  await flush(() =>
    outgoing.push(
      'event: conversation\ndata: "b"\n\nevent: token\ndata: "Late completion"\n\n' + done,
    ),
  );

  expect(apiFetchMock.mock.calls).toHaveLength(callsBeforeCompletion);
  expect(row('a').getAttribute('aria-current')).toBe('true');
  expect(host.textContent).toContain('Selected saved answer');
  expect(host.textContent).toContain('Active partial');
  expect(host.textContent).not.toContain('Outgoing partial');
  expect(host.textContent).not.toContain('Late token');
  expect(host.textContent).not.toContain('Late completion');
  expect(textarea().disabled).toBe(true);
  await flush(() => active.push(done));
  expect(textarea().disabled).toBe(false);
});
