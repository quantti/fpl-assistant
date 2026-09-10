import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../auth/apiFetch'
import { ChatRequest } from '../types'
import {
  ChatEvents,
  GENERATION_FAILED,
  INTERRUPTED,
  REJECTED,
  sendChatTurn,
  UNAVAILABLE,
  UNREACHABLE,
} from './chatTransport'

vi.mock('../auth/apiFetch', () => ({ apiFetch: vi.fn() }))

const apiFetchMock = vi.mocked(apiFetch)

beforeEach(() => {
  apiFetchMock.mockReset()
})

function request(): ChatRequest {
  return { conversationId: null, message: 'hello' }
}

function events(): ChatEvents & { tokens: string[]; conversations: string[]; truncated: number[] } {
  const tokens: string[] = []
  const conversations: string[] = []
  const truncated: number[] = []
  return {
    tokens,
    conversations,
    truncated,
    onToken: t => tokens.push(t),
    onConversation: id => conversations.push(id),
    onTruncated: n => truncated.push(n),
  }
}

const encoder = new TextEncoder()

function encode(s: string): Uint8Array {
  return encoder.encode(s)
}

function frame(event: string, data: string): string {
  return `event: ${event}\ndata: ${data}\n\n`
}

function tokenFrame(text: string): string {
  return `data: ${JSON.stringify(text)}\n\n`
}

function conversationFrame(id: string): string {
  return frame('conversation', JSON.stringify(id))
}

function truncatedFrame(dropped: number): string {
  return frame('truncated', String(dropped))
}

function errorFrame(text: string): string {
  return frame('error', text)
}

function doneFrame(): string {
  return 'event: done\ndata: \n\n'
}

/** A 200 response whose body streams each piece as its own chunk, then closes normally. */
function chunks(...pieces: (string | Uint8Array)[]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const piece of pieces) {
        controller.enqueue(typeof piece === 'string' ? encode(piece) : piece)
      }
      controller.close()
    },
  })
  return new Response(stream, { status: 200 })
}

/** A 200 response whose body streams each frame as its own chunk. */
function sse(...frames: string[]): Response {
  return chunks(...frames)
}

/** A stream that delivers the given pieces one read() at a time, then errors on the next read(). */
function erroringAfter(...pieces: string[]): Response {
  const queue = [...pieces]
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      const piece = queue.shift()
      if (piece !== undefined) {
        controller.enqueue(encode(piece))
        return
      }
      controller.error(new Error('boom'))
    },
  })
  return new Response(stream, { status: 200 })
}

/** A stream whose first read() rejects, with nothing delivered first. */
function erroringImmediately(): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.error(new Error('boom'))
    },
  })
  return new Response(stream, { status: 200 })
}

/** A stream that delivers `done` once and then never enqueues or closes again. */
function neverClosingAfterDone(): Response {
  let delivered = false
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (!delivered) {
        delivered = true
        controller.enqueue(encode(doneFrame()))
      }
      // No further enqueue and no close — a second read() would hang forever.
    },
  })
  return new Response(stream, { status: 200 })
}

it('rejects with UNREACHABLE when the fetch itself fails', async () => {
  apiFetchMock.mockRejectedValue(new TypeError('network down'))

  const outcome = await sendChatTurn(request(), new AbortController().signal, events())

  expect(outcome.kind).toBe('failed')
  expect((outcome as { message: string }).message).toBe(UNREACHABLE)
})

describe('status classification', () => {
  describe('maps 500, 502 and 503 to UNAVAILABLE with the status', () => {
    it.each([500, 502, 503])('%d', async status => {
      apiFetchMock.mockResolvedValue(new Response(null, { status }))

      const outcome = await sendChatTurn(request(), new AbortController().signal, events())

      expect(outcome).toEqual({ kind: 'failed', message: UNAVAILABLE(status) })
      expect((outcome as { message: string }).message).toContain(`HTTP ${status}`)
    })
  })

  describe('maps 400 and 418 to REJECTED with the status', () => {
    it.each([400, 418])('%d', async status => {
      apiFetchMock.mockResolvedValue(new Response(null, { status }))

      const outcome = await sendChatTurn(request(), new AbortController().signal, events())

      expect(outcome).toEqual({ kind: 'failed', message: REJECTED(status) })
      expect((outcome as { message: string }).message).toContain(`HTTP ${status}`)
    })
  })

  it('returns unauthorized on 401 without reading a body', async () => {
    const response = new Response('should not be read', { status: 401 })
    const textSpy = vi.spyOn(response, 'text')
    apiFetchMock.mockResolvedValue(response)

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'unauthorized' })
    expect(textSpy).not.toHaveBeenCalled()
  })

  it('returns gone on 404', async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 404 }))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'gone' })
  })

  it('formats 429 from the body and Retry-After', async () => {
    apiFetchMock.mockResolvedValueOnce(new Response('Weekly limit reached.', {
      status: 429,
      headers: { 'Retry-After': '90' },
    }))
    await expect(sendChatTurn(request(), new AbortController().signal, events()))
      .resolves.toEqual({ kind: 'failed', message: 'Weekly limit reached. Try again in 2 minutes.' })

    apiFetchMock.mockResolvedValueOnce(new Response('', { status: 429 }))
    await expect(sendChatTurn(request(), new AbortController().signal, events()))
      .resolves.toEqual({ kind: 'failed', message: 'Too many messages for now.' })
  })

  it('falls back to the generic 429 reason when the body read fails', async () => {
    const response = new Response('irrelevant', { status: 429, headers: { 'Retry-After': '90' } })
    vi.spyOn(response, 'text').mockRejectedValue(new Error('body read failed'))
    apiFetchMock.mockResolvedValue(response)

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({
      kind: 'failed',
      message: 'Too many messages for now. Try again in 2 minutes.',
    })
  })

  it('returns aborted when the 429 body read fails after an abort', async () => {
    const controller = new AbortController()
    const response = new Response('irrelevant', { status: 429 })
    vi.spyOn(response, 'text').mockImplementation(async () => {
      controller.abort()
      throw new Error('body read failed')
    })
    apiFetchMock.mockResolvedValue(response)

    const outcome = await sendChatTurn(request(), controller.signal, events())

    expect(outcome).toEqual({ kind: 'aborted' })
  })

  it('treats a 2xx without a body as INTERRUPTED', async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 200 }))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'failed', message: INTERRUPTED })
  })
})

describe('the SSE reader loop', () => {
  it('completes on done with the accumulated tokens', async () => {
    apiFetchMock.mockResolvedValue(sse(
      conversationFrame('conv-1'),
      tokenFrame(' hel'),
      tokenFrame('lo\n'),
      doneFrame(),
    ))
    const ev = events()

    const outcome = await sendChatTurn(request(), new AbortController().signal, ev)

    expect(outcome).toEqual({ kind: 'completed', content: ' hello\n' })
    expect(ev.conversations).toEqual(['conv-1'])
    expect(ev.tokens).toEqual([' hel', ' hello\n'])
  })

  it('completes with empty content on done after zero tokens', async () => {
    apiFetchMock.mockResolvedValue(sse(doneFrame()))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'completed', content: '' })
  })

  it('reports truncated as metadata, not text', async () => {
    apiFetchMock.mockResolvedValue(sse(truncatedFrame(3), doneFrame()))
    const ev = events()

    const outcome = await sendChatTurn(request(), new AbortController().signal, ev)

    expect(ev.truncated).toEqual([3])
    expect(outcome).toEqual({ kind: 'completed', content: '' })
  })

  it('normalises a CRLF split across chunks so a trailing done still completes', async () => {
    const frameText = 'event: done\r\ndata: \r\n\r\n'
    const bytes = encode(frameText)
    // Split inside the closing \r\n\r\n, leaving "...\n\r" in one chunk and "\n" in the next —
    // per-chunk normalisation would leave a stray \r\n\n that never forms a \n\n separator.
    const splitAt = frameText.length - 1
    apiFetchMock.mockResolvedValue(chunks(bytes.slice(0, splitAt), bytes.slice(splitAt)))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'completed', content: '' })
  })

  it('reassembles frames split across chunks and inside multibyte characters', async () => {
    const tokenBytes = encode(tokenFrame('€'))
    const doneBytes = encode(doneFrame())
    const eurStart = encode('data: "').length
    // Split inside the euro sign's 3-byte UTF-8 encoding, and split "\n\n" across chunks too.
    const chunk1 = tokenBytes.slice(0, eurStart + 1)
    const chunk2 = tokenBytes.slice(eurStart + 1, tokenBytes.length - 1)
    const chunk3 = tokenBytes.slice(tokenBytes.length - 1)
    apiFetchMock.mockResolvedValue(chunks(chunk1, chunk2, chunk3, doneBytes))
    const ev = events()

    const outcome = await sendChatTurn(request(), new AbortController().signal, ev)

    expect(outcome).toEqual({ kind: 'completed', content: '€' })
    expect(ev.tokens).toEqual(['€'])
  })

  it('ignores comments and unknown named events', async () => {
    apiFetchMock.mockResolvedValue(sse(
      ': ping\n\n',
      'event: heartbeat\ndata: keep-alive\n\n',
      tokenFrame('hi'),
      doneFrame(),
    ))
    const ev = events()

    const outcome = await sendChatTurn(request(), new AbortController().signal, ev)

    expect(outcome).toEqual({ kind: 'completed', content: 'hi' })
    expect(ev.tokens).toEqual(['hi'])
  })

  it('returns GENERATION_FAILED on a complete error frame, even when done follows', async () => {
    apiFetchMock.mockResolvedValue(sse(
      tokenFrame('partial'),
      errorFrame('boom'),
      doneFrame(),
    ))
    const ev = events()

    const outcome = await sendChatTurn(request(), new AbortController().signal, ev)

    expect(outcome).toEqual({ kind: 'failed', message: GENERATION_FAILED })
    expect(ev.tokens).toEqual(['partial'])
  })

  it('keeps GENERATION_FAILED when the read fails after the error frame', async () => {
    apiFetchMock.mockResolvedValue(erroringAfter(errorFrame('boom')))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'failed', message: GENERATION_FAILED })
  })

  it('returns INTERRUPTED on EOF without done', async () => {
    apiFetchMock.mockResolvedValue(sse(tokenFrame('hi')))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'failed', message: INTERRUPTED })
  })

  it('returns INTERRUPTED when the read rejects', async () => {
    apiFetchMock.mockResolvedValue(erroringImmediately())

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'failed', message: INTERRUPTED })
  })

  it('does not accept an undelimited trailing done', async () => {
    apiFetchMock.mockResolvedValue(sse('event: done\ndata:'))

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'failed', message: INTERRUPTED })
  })

  it('returns completed without waiting for EOF', async () => {
    apiFetchMock.mockResolvedValue(neverClosingAfterDone())

    const outcome = await sendChatTurn(request(), new AbortController().signal, events())

    expect(outcome).toEqual({ kind: 'completed', content: '' })
  })
})

describe('abort handling', () => {
  it('returns aborted when the signal fires before the response', async () => {
    const controller = new AbortController()
    let rejectFetch!: (err: unknown) => void
    apiFetchMock.mockReturnValue(new Promise((_, reject) => { rejectFetch = reject }))

    const outcomePromise = sendChatTurn(request(), controller.signal, events())
    controller.abort()
    rejectFetch(new DOMException('Aborted', 'AbortError'))

    await expect(outcomePromise).resolves.toEqual({ kind: 'aborted' })
  })

  it('returns aborted when the signal fires during the read', async () => {
    const controller = new AbortController()
    let delivered = false
    let rejectRead: ((err: unknown) => void) | undefined
    const stream = new ReadableStream<Uint8Array>({
      pull(streamController) {
        if (!delivered) {
          delivered = true
          streamController.enqueue(encode(tokenFrame('partial')))
          return
        }
        return new Promise((_, reject) => { rejectRead = reject })
      },
    })
    apiFetchMock.mockResolvedValue(new Response(stream, { status: 200 }))
    const ev = events()

    const outcomePromise = sendChatTurn(request(), controller.signal, ev)
    while (!rejectRead) await Promise.resolve()
    controller.abort()
    rejectRead(new DOMException('Aborted', 'AbortError'))

    await expect(outcomePromise).resolves.toEqual({ kind: 'aborted' })
    // The first read delivered a token, proving the abort outcome discards accumulated text
    // rather than there being nothing to discard.
    expect(ev.tokens).toEqual(['partial'])
  })
})
