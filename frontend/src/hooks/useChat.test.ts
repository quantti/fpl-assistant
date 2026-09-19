// @vitest-environment jsdom

import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatRequest, Message, PlayerCatalogue } from '../types';
import { GONE, type ChatEvents, type ChatOutcome } from './chatTransport';

vi.mock('../auth/apiFetch', () => ({ apiFetch: vi.fn() }));

const { sendChatTurnMock } = vi.hoisted(() => ({ sendChatTurnMock: vi.fn() }));

vi.mock('./chatTransport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./chatTransport')>()),
  sendChatTurn: sendChatTurnMock,
}));

import { useChat } from './useChat';

type HookValue = ReturnType<typeof useChat>;
type HookOptions = Parameters<typeof useChat>[0];

type ControlledTurn = {
  request: ChatRequest;
  signal: AbortSignal;
  events: ChatEvents;
  promise: Promise<ChatOutcome>;
  resolve: (outcome: ChatOutcome) => void;
};

const turns: ControlledTurn[] = [];
const mounted: Harness[] = [];
const catalogue: PlayerCatalogue = {
  catalogueId: 'catalogue-a',
  season: '2026-27',
  players: [
    {
      elementId: 17,
      webName: 'Saka',
      fullName: 'Bukayo Saka',
      teamShort: 'ARS',
      teamName: 'Arsenal',
      elementType: 3,
      aliases: ['Bukayo Saka', 'Saka'],
    },
    {
      elementId: 20,
      webName: 'Son',
      fullName: 'Son Heung-min',
      teamShort: 'TOT',
      teamName: 'Tottenham',
      elementType: 3,
      aliases: ['Son'],
    },
  ],
};

class Harness {
  private readonly container = document.createElement('div');
  private readonly root: Root;
  private currentValue: HookValue | undefined;
  private isMounted = true;

  constructor(
    options: HookOptions,
    private readonly strict = false,
  ) {
    this.root = createRoot(this.container);
    document.body.appendChild(this.container);
    act(() => this.render(options));
  }

  private render(options: HookOptions): void {
    const probe = React.createElement(this.Probe, options);
    this.root.render(this.strict ? React.createElement(React.StrictMode, null, probe) : probe);
  }

  private Probe = (options: HookOptions): null => {
    this.currentValue = useChat(options);
    return null;
  };

  current(): HookValue {
    if (!this.currentValue) throw new Error('Hook did not mount');
    return this.currentValue;
  }

  rerender(options: HookOptions): void {
    act(() => this.render(options));
  }

  unmount(): void {
    if (!this.isMounted) return;
    act(() => this.root.unmount());
    this.container.remove();
    this.isMounted = false;
  }
}

function mount(options: HookOptions, strict = false): Harness {
  const harness = new Harness(options, strict);
  mounted.push(harness);
  return harness;
}

function options(overrides: Partial<HookOptions> = {}): HookOptions {
  return {
    conversationId: 'conversation-a',
    onConversation: vi.fn(),
    onTurnFinished: vi.fn(),
    ...overrides,
  };
}

function send(harness: Harness, content: string): Promise<void> {
  let promise!: Promise<void>;
  act(() => {
    promise = harness.current().sendMessage(content);
  });
  return promise;
}

async function settle(
  turn: ControlledTurn,
  promise: Promise<void>,
  outcome: ChatOutcome,
): Promise<void> {
  await act(async () => {
    turn.resolve(outcome);
    await promise;
  });
}

function replace(harness: Harness, messages: Message[]): void {
  act(() => harness.current().replaceMessages(messages));
}

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
const previousActEnvironment = actEnvironment.IS_REACT_ACT_ENVIRONMENT;

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
});

afterAll(() => {
  if (previousActEnvironment === undefined) {
    delete actEnvironment.IS_REACT_ACT_ENVIRONMENT;
  } else {
    actEnvironment.IS_REACT_ACT_ENVIRONMENT = previousActEnvironment;
  }
});

beforeEach(() => {
  turns.length = 0;
  sendChatTurnMock.mockReset();
  sendChatTurnMock.mockImplementation(
    (request: ChatRequest, signal: AbortSignal, events: ChatEvents) => {
      let resolve!: (outcome: ChatOutcome) => void;
      const promise = new Promise<ChatOutcome>((done) => {
        resolve = done;
      });
      turns.push({ request, signal, events, promise, resolve });
      return promise;
    },
  );
});

afterEach(() => {
  for (const harness of mounted.splice(0)) harness.unmount();
  vi.clearAllMocks();
});

describe('turn ownership', () => {
  it('keeps B active when replaced turn A settles late', async () => {
    const onTurnFinished = vi.fn();
    const onConversationA = vi.fn();
    const harness = mount(options({ onConversation: onConversationA, onTurnFinished }));

    const promiseA = send(harness, 'A');
    const turnA = turns[0];
    const replacement: Message[] = [{ role: 'assistant', content: 'Conversation B history' }];
    replace(harness, replacement);
    expect(turnA.signal.aborted).toBe(true);

    const onConversationB = vi.fn();
    harness.rerender(
      options({
        conversationId: 'conversation-b',
        onConversation: onConversationB,
        onTurnFinished,
      }),
    );
    const promiseB = send(harness, 'B');
    const turnB = turns[1];
    act(() => turnB.events.onToken('B partial'));

    await settle(turnA, promiseA, { kind: 'completed', content: 'late A' });

    expect(harness.current().messages).toEqual([...replacement, { role: 'user', content: 'B' }]);
    expect(harness.current().streamingContent).toBe('B partial');
    expect(harness.current().isStreaming).toBe(true);
    expect(onTurnFinished).not.toHaveBeenCalled();

    send(harness, 'refused while B owns the turn');
    expect(turns).toHaveLength(2);

    await settle(turnB, promiseB, { kind: 'completed', content: 'B answer' });
    expect(harness.current().messages).toEqual([
      ...replacement,
      { role: 'user', content: 'B' },
      { role: 'assistant', content: 'B answer' },
    ]);
    expect(harness.current().streamingContent).toBeNull();
    expect(harness.current().isStreaming).toBe(false);
    expect(onTurnFinished).toHaveBeenCalledTimes(1);
  });

  it('rejects a synchronous duplicate send before React rerenders', () => {
    const harness = mount(options());
    const capturedSend = harness.current().sendMessage;

    act(() => {
      void capturedSend('first');
      void capturedSend('duplicate');
    });

    expect(turns).toHaveLength(1);
    expect(harness.current().messages).toEqual([{ role: 'user', content: 'first' }]);
  });

  it('keeps a replacement authoritative when replace and send share a batch', () => {
    const harness = mount(options());
    const oldMessages: Message[] = [{ role: 'assistant', content: 'old transcript' }];
    replace(harness, oldMessages);
    const captured = harness.current();
    const replacement: Message[] = [{ role: 'assistant', content: 'replacement transcript' }];

    act(() => {
      captured.replaceMessages(replacement);
      void captured.sendMessage('new question');
    });

    expect(harness.current().messages).toEqual([
      ...replacement,
      { role: 'user', content: 'new question' },
    ]);
    expect(turns).toHaveLength(1);
  });

  it.each<ChatOutcome>([
    { kind: 'completed', content: 'late answer' },
    { kind: 'failed', message: 'late failure' },
    { kind: 'gone' },
  ])('ignores late A metadata and a $kind outcome after B starts', async (outcome) => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }));
    const promiseA = send(harness, 'A');
    const turnA = turns[0];

    const replacement: Message[] = [{ role: 'assistant', content: 'B history' }];
    replace(harness, replacement);
    harness.rerender(
      options({
        conversationId: 'conversation-b',
        onConversation,
        onTurnFinished,
      }),
    );
    send(harness, 'B');
    const turnB = turns[1];
    act(() => turnB.events.onToken('B partial'));

    act(() => {
      turnA.events.onToken('late A token');
      turnA.events.onConversation('late-conversation-a');
      turnA.events.onTruncated(7);
      turnA.events.onCatalogue(catalogue);
    });
    await settle(turnA, promiseA, outcome);

    expect(onConversation).not.toHaveBeenCalled();
    expect(onTurnFinished).not.toHaveBeenCalled();
    expect(harness.current().messages).toEqual([...replacement, { role: 'user', content: 'B' }]);
    expect(harness.current().streamingContent).toBe('B partial');
    expect(harness.current().isStreaming).toBe(true);
  });

  it('detaches a replaced turn even when no new turn starts', async () => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }));
    const pending = send(harness, 'A');
    const turn = turns[0];
    const replacement: Message[] = [{ role: 'assistant', content: 'replacement' }];

    replace(harness, replacement);
    expect(turn.signal.aborted).toBe(true);
    act(() => {
      turn.events.onConversation('late-conversation-a');
      turn.events.onToken('late token');
      turn.events.onTruncated(2);
    });
    await settle(turn, pending, { kind: 'completed', content: 'late answer' });

    expect(onConversation).not.toHaveBeenCalled();
    expect(onTurnFinished).not.toHaveBeenCalled();
    expect(harness.current().messages).toEqual(replacement);
    expect(harness.current().streamingContent).toBeNull();
    expect(harness.current().isStreaming).toBe(false);
  });

  it('detaches a pending turn on unmount', async () => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }));
    const pending = send(harness, 'A');
    const turn = turns[0];

    harness.unmount();
    expect(turn.signal.aborted).toBe(true);
    act(() => {
      turn.events.onConversation('late-conversation-a');
      turn.events.onToken('late token');
      turn.events.onTruncated(2);
    });
    await settle(turn, pending, { kind: 'failed', message: 'late failure' });

    expect(onConversation).not.toHaveBeenCalled();
    expect(onTurnFinished).not.toHaveBeenCalled();
  });
});

describe('active turn outcomes', () => {
  it('binds catalogue metadata only after provisional streaming text completes', async () => {
    const harness = mount(options());
    const pending = send(harness, 'question');
    const turn = turns[0];

    act(() => {
      turn.events.onCatalogue(catalogue);
      turn.events.onToken('Son');
    });
    expect(harness.current().streamingContent).toBe('Son');
    expect(harness.current().messages).toEqual([{ role: 'user', content: 'question' }]);

    await settle(turn, pending, { kind: 'completed', content: 'Sonny is not Son.' });

    expect(harness.current().messages[harness.current().messages.length - 1]).toEqual({
      role: 'assistant',
      content: 'Sonny is not Son.',
      catalogue,
    });
  });

  it('accepts active metadata and truncation callbacks, including empty success', async () => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }));
    const pending = send(harness, 'question');
    const turn = turns[0];

    act(() => {
      turn.events.onConversation('new-conversation');
      turn.events.onTruncated(3);
      turn.events.onToken('partial');
    });
    expect(onConversation).toHaveBeenCalledWith('new-conversation');
    expect(harness.current().streamingContent).toBe('partial');
    expect(harness.current().messages[1]).toMatchObject({ role: 'assistant', isNotice: true });
    expect(harness.current().messages[1].content).toContain('The 3 oldest messages');

    await settle(turn, pending, { kind: 'completed', content: '' });

    expect(harness.current().messages[harness.current().messages.length - 1]).toEqual({
      role: 'assistant',
      content: '',
    });
    expect(harness.current().streamingContent).toBeNull();
    expect(harness.current().isStreaming).toBe(false);
    expect(onTurnFinished).toHaveBeenCalledTimes(1);

    send(harness, 'next question');
    expect(turns).toHaveLength(2);
  });

  it('preserves the transport failure message exactly', async () => {
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onTurnFinished }));
    const pending = send(harness, 'question');
    const message = 'Exact transport error — retry later.';

    await settle(turns[0], pending, { kind: 'failed', message });

    expect(harness.current().messages).toEqual([
      { role: 'user', content: 'question' },
      { role: 'assistant', content: message, isError: true },
    ]);
    expect(onTurnFinished).toHaveBeenCalledTimes(1);
  });

  it.each<ChatOutcome>([{ kind: 'unauthorized' }, { kind: 'aborted' }])(
    'keeps an active $kind outcome silent and releases ownership',
    async (outcome) => {
      const onTurnFinished = vi.fn();
      const harness = mount(options({ onTurnFinished }));
      const pending = send(harness, 'question');

      await settle(turns[0], pending, outcome);

      expect(harness.current().messages).toEqual([{ role: 'user', content: 'question' }]);
      expect(harness.current().isStreaming).toBe(false);
      expect(onTurnFinished).toHaveBeenCalledTimes(1);
      send(harness, 'next question');
      expect(turns).toHaveLength(2);
    },
  );

  it('clears gone conversation identity and displays the existing error copy', async () => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }));
    const pending = send(harness, 'question');

    await settle(turns[0], pending, { kind: 'gone' });

    expect(onConversation).toHaveBeenCalledWith(null);
    expect(harness.current().messages[harness.current().messages.length - 1]).toEqual({
      role: 'assistant',
      content: GONE,
      isError: true,
    });
    expect(onTurnFinished).toHaveBeenCalledTimes(1);
  });

  it('survives StrictMode effect cleanup and detaches on explicit unmount', async () => {
    const onConversation = vi.fn();
    const onTurnFinished = vi.fn();
    const harness = mount(options({ onConversation, onTurnFinished }), true);
    const pending = send(harness, 'question');
    const turn = turns[0];

    expect(turn.signal.aborted).toBe(false);
    harness.unmount();
    expect(turn.signal.aborted).toBe(true);
    act(() => turn.events.onConversation('late-conversation'));
    await settle(turn, pending, { kind: 'completed', content: 'late answer' });

    expect(onConversation).not.toHaveBeenCalled();
    expect(onTurnFinished).not.toHaveBeenCalled();
  });
});
