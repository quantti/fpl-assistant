import { useState, useCallback } from 'react'
import { ChatRequest, Message } from '../types'

interface UseChatReturn {
  messages: Message[]
  streamingContent: string | null
  isStreaming: boolean
  sendMessage: (content: string) => Promise<void>
}

const INTERRUPTED = 'Sorry — the connection was interrupted. Please try again.'
const FAILED = 'Sorry, something went wrong. Please try again.'

export function useChat(): UseChatReturn {
  const [messages, setMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)

  const sendMessage = useCallback(async (content: string) => {
    if (isStreaming) return // single-flight: the hook does not support overlapping sends
    const userMessage: Message = { role: 'user', content }
    const nextMessages = [...messages, userMessage]
    setMessages(nextMessages)
    setStreamingContent('')
    setIsStreaming(true)

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

    // Error turns are transcript-only: they are the app reporting a failure, not the model
    // saying anything, and sending them back would have the model treat "Sorry, something
    // went wrong" as its own prior answer. Filtered here rather than never appended because
    // the user still needs to see them in the UI. Consecutive user turns that the filtering
    // can produce (question, error, retry) are merged: the Anthropic API rejects a history
    // with two user turns in a row.
    const wireMessages: ChatRequest['messages'] = nextMessages
      .filter(m => !m.isError)
      .reduce<ChatRequest['messages']>((acc, { role, content }) => {
        const previous = acc[acc.length - 1]
        if (previous && previous.role === 'user' && role === 'user') {
          previous.content = `${previous.content}\n\n${content}`
          return acc
        }
        acc.push({ role, content })
        return acc
      }, [])

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: wireMessages } satisfies ChatRequest),
      })

      if (response.status === 429) {
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: 'You are sending messages too quickly — wait a minute and try again.',
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
      setMessages(prev => [...prev, { role: 'assistant', content: FAILED, isError: true }])
    } finally {
      setStreamingContent(null)
      setIsStreaming(false)
    }
  }, [messages, isStreaming])

  return { messages, streamingContent, isStreaming, sendMessage }
}
