import { ChatRequest } from '../types'
import { apiFetch } from '../auth/apiFetch'

export type ChatOutcome =
  | { kind: 'completed'; content: string }
  | { kind: 'failed'; message: string } // isError message to append
  | { kind: 'gone' }
  | { kind: 'unauthorized' }
  | { kind: 'aborted' }

export interface ChatEvents {
  onConversation(id: string): void
  onTruncated(dropped: number): void // NaN/≤0 allowed; the hook picks the wording
  onToken(accumulated: string): void
}

export const UNREACHABLE =
  'Could not reach the chat service. Check your connection — or, if you are running the app '
  + 'locally, that the backend is up — then try again.'

export function UNAVAILABLE(status: number): string {
  return `The chat service is temporarily unavailable (HTTP ${status}). Try again in a minute.`
}

export function REJECTED(status: number): string {
  return `The chat request was rejected (HTTP ${status}). Reload the page and try again.`
}

export const GENERATION_FAILED =
  'The assistant could not finish this answer — its AI service or a data lookup failed. The '
  + 'answer was not saved. Try again in a moment.'

export const INTERRUPTED =
  'The connection dropped before the answer finished. Wait a moment and check Conversations — '
  + 'the answer may have been saved — before sending again.'

export const GONE =
  'That conversation is no longer available. The messages above stay on screen but are no '
  + 'longer part of it — your next message starts a new conversation, without them.'

/** Retry-After in human terms. Empty when the header is missing — a suspended account has no when. */
export function waitFor(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return ''
  const hours = seconds / 3600
  if (seconds < 90) return `Try again in ${plural(Math.ceil(seconds), 'second')}.`
  if (hours < 1) return `Try again in ${plural(Math.ceil(seconds / 60), 'minute')}.`
  if (hours < 48) return `Try again in ${plural(Math.round(hours), 'hour')}.`
  return `Try again in ${plural(Math.round(hours / 24), 'day')}.`
}

function plural(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'}`
}

/**
 * Runs one `POST /api/chat` turn to completion: the request, the status-code classification and
 * the SSE reader loop. Returns the outcome rather than throwing or touching UI state — the hook
 * decides what to show and when.
 */
export async function sendChatTurn(
  request: ChatRequest,
  signal: AbortSignal,
  events: ChatEvents,
): Promise<ChatOutcome> {
  let response: Response
  try {
    response = await apiFetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal,
    })
  } catch {
    if (signal.aborted) return { kind: 'aborted' }
    return { kind: 'failed', message: UNREACHABLE }
  }

  // apiFetch has already signed out, so the sign-in screen is about to replace this view.
  if (response.status === 401) return { kind: 'unauthorized' }
  // The conversation is gone — deleted elsewhere, or never the caller's.
  if (response.status === 404) return { kind: 'gone' }
  // Two different 429s: the per-IP rate limit and the weekly quota. The backend says which in
  // the body, and Retry-After says when it frees.
  if (response.status === 429) {
    const reason = (await response.text()).trim()
    const retryAfter = Number(response.headers.get('Retry-After'))
    return {
      kind: 'failed',
      message: [reason || 'Too many messages for now.', waitFor(retryAfter)]
        .filter(Boolean).join(' '),
    }
  }
  if (response.status >= 500 && response.status <= 599) {
    return { kind: 'failed', message: UNAVAILABLE(response.status) }
  }
  if (!response.ok) {
    return { kind: 'failed', message: REJECTED(response.status) }
  }
  if (!response.body) {
    return { kind: 'failed', message: INTERRUPTED }
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let accumulated = ''
  let sawError = false
  let outcome: ChatOutcome | null = null

  // SSE events separated by a blank line. Dispatch on the `event:` line — the `done` event
  // carries empty data, so a data-presence check would swallow it. Token events' data is a
  // JSON-encoded string (the backend encodes so leading spaces and newlines survive SSE);
  // error/done payloads are plain text.
  const flushEvent = (block: string): void => {
    const lines = block.split('\n')
    const eventName =
      lines.find(l => l.startsWith('event:'))?.slice('event:'.length).trim() ?? 'token'
    const data = lines
      .filter(l => l.startsWith('data:'))
      .map(l => l.slice('data:'.length).replace(/^ /, ''))
      .join('\n')

    if (eventName === 'conversation') {
      try {
        events.onConversation(JSON.parse(data))
      } catch {
        // Unparseable id — the turn still streams; the next one opens a new conversation.
      }
      return
    }
    if (eventName === 'truncated') {
      events.onTruncated(Number(data))
      return
    }
    if (eventName === 'error') {
      sawError = true
      return
    }
    if (eventName === 'done') {
      // Resolve immediately — do not wait for EOF. A complete error frame wins over a later done.
      outcome = sawError
        ? { kind: 'failed', message: GENERATION_FAILED }
        : { kind: 'completed', content: accumulated }
      return
    }
    if (eventName !== 'token') {
      // Comment-only block or an unknown named event (e.g. a heartbeat) — not text.
      return
    }
    try {
      accumulated += JSON.parse(data)
      events.onToken(accumulated)
    } catch {
      // Malformed token payload — skip it rather than corrupt the message.
    }
  }

  try {
    while (true) {
      let step: ReadableStreamReadResult<Uint8Array>
      try {
        step = await reader.read()
      } catch {
        if (signal.aborted) return { kind: 'aborted' }
        if (sawError) return { kind: 'failed', message: GENERATION_FAILED }
        return { kind: 'failed', message: INTERRUPTED }
      }
      if (step.done) break
      // Normalize CRLF so a proxy that rewrites line endings cannot break event framing.
      buffer += decoder.decode(step.value, { stream: true }).replace(/\r\n/g, '\n')
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        flushEvent(buffer.slice(0, sep))
        buffer = buffer.slice(sep + 2)
        if (outcome) return outcome
      }
    }
    // EOF: a trailing undelimited fragment (including an undelimited "done") is dropped.
    if (signal.aborted) return { kind: 'aborted' }
    if (sawError) return { kind: 'failed', message: GENERATION_FAILED }
    return { kind: 'failed', message: INTERRUPTED }
  } finally {
    try {
      await reader.cancel()
    } catch {
      // The stream may already be closed or errored — nothing to report.
    }
  }
}
