import { useState, useCallback, useEffect, useRef } from 'react'
import { ChatRequest, Message } from '../types'
import { apiFetch } from '../auth/apiFetch'

interface UseChatReturn {
  messages: Message[]
  streamingContent: string | null
  isStreaming: boolean
  sendMessage: (content: string) => Promise<void>
}

/** Retry-After in human terms. Empty when the header is missing — a suspended account has no when. */
function waitFor(seconds: number): string {
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

const GONE = 'That conversation is no longer available. The messages above stay on screen but are no longer part of it — your next message starts a new conversation, without them.'

const INTERRUPTED = 'Sorry — the connection was interrupted. Please try again.'
const FAILED = 'Sorry, something went wrong. Please try again.'

export function useChat(): UseChatReturn {
  const [messages, setMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)
  // The server owns the history; this is the only part of it the client holds. Null until the
  // first answer names the conversation the turn landed in.
  const [conversationId, setConversationId] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  // Signing out unmounts this view, but a stream nobody aborts keeps draining: the backend goes
  // on generating — and paying for — a completion that will never be read.
  useEffect(() => () => abortRef.current?.abort(), [])

  const sendMessage = useCallback(async (content: string) => {
    if (isStreaming) return // single-flight: the hook does not support overlapping sends
    const userMessage: Message = { role: 'user', content }
    const nextMessages = [...messages, userMessage]
    setMessages(nextMessages)
    setStreamingContent('')
    setIsStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller

    let accumulated = ''
    let sawDone = false
    let errorText: string | null = null

    // SSE events separated by a blank line. Dispatch on the `event:` line — the `done`
    // event carries empty data, so a data-presence check would swallow it. Token events'
    // data is a JSON-encoded string (the backend encodes so leading spaces and newlines
    // survive SSE); error/done payloads are plain text.
    const flushEvent = (block: string) => {
      const lines = block.split('\n')
      const eventName =
        lines.find(l => l.startsWith('event:'))?.slice('event:'.length).trim() ?? 'token'
      const data = lines
        .filter(l => l.startsWith('data:'))
        .map(l => l.slice('data:'.length).replace(/^ /, ''))
        .join('\n')

      if (eventName === 'conversation') {
        // First event of every stream: which conversation this turn landed in. A new one was
        // opened when the request carried no id, and the next turn has to name it.
        try {
          setConversationId(JSON.parse(data))
        } catch {
          // Unparseable id — the turn still streams; the next one opens a new conversation.
        }
        return
      }
      if (eventName === 'error') {
        errorText = data || FAILED
        return
      }
      if (eventName === 'done') {
        sawDone = true
        return
      }
      try {
        accumulated += JSON.parse(data)
        setStreamingContent(accumulated)
      } catch {
        // Malformed token payload — skip it rather than corrupt the message.
      }
    }

    try {
      const response = await apiFetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, message: content } satisfies ChatRequest),
        signal: controller.signal,
      })

      // apiFetch has already signed out, so the sign-in screen is about to replace this view.
      // Appending an error would only flash a message nobody gets to read.
      if (response.status === 401) {
        return
      }
      // The conversation is gone — deleted elsewhere, or never the caller's. Forget the id so
      // the next message opens a new one instead of failing the same way forever.
      if (response.status === 404) {
        setConversationId(null)
        setMessages(prev => [...prev, { role: 'assistant', content: GONE, isError: true }])
        return
      }
      // Two different 429s now: the per-IP rate limit and the weekly quota. The backend says
      // which in the body, and Retry-After says when it frees — repeating a generic "too
      // quickly" would tell a user who has spent their week to wait a minute.
      if (response.status === 429) {
        const reason = (await response.text()).trim()
        const retryAfter = Number(response.headers.get('Retry-After'))
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: [reason || 'Too many messages for now.', waitFor(retryAfter)]
            .filter(Boolean).join(' '),
          isError: true,
        }])
        return
      }
      if (!response.ok || !response.body) {
        throw new Error(`Request failed: ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        // Normalize CRLF so a proxy that rewrites line endings cannot break event framing.
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let sep: number
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          flushEvent(buffer.slice(0, sep))
          buffer = buffer.slice(sep + 2)
        }
      }
      if (buffer.trim()) flushEvent(buffer)

      if (errorText !== null) {
        // Amendment: surface the error instead of committing the partial text.
        setMessages(prev => [...prev, { role: 'assistant', content: errorText!, isError: true }])
      } else if (!sawDone) {
        // The stream closed without the terminal done event — treat as interrupted.
        setMessages(prev => [...prev, { role: 'assistant', content: INTERRUPTED, isError: true }])
      } else {
        setMessages(prev => [...prev, { role: 'assistant', content: accumulated }])
      }
    } catch {
      // An abort is this component going away, not a failure to report to a user who has left.
      if (controller.signal.aborted) return
      setMessages(prev => [...prev, { role: 'assistant', content: FAILED, isError: true }])
    } finally {
      setStreamingContent(null)
      setIsStreaming(false)
    }
  }, [messages, isStreaming, conversationId])

  return { messages, streamingContent, isStreaming, sendMessage }
}
