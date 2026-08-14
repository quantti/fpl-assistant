import { useState, useCallback } from 'react'
import { Message } from '../types'

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

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: nextMessages }),
      })

      if (!response.ok || !response.body) {
        throw new Error(`Request failed: ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let sep: number
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          flushEvent(buffer.slice(0, sep))
          buffer = buffer.slice(sep + 2)
        }
      }
      if (buffer.trim()) flushEvent(buffer)

      if (errorText !== null) {
        // Amendment: surface the error instead of committing the partial text.
        setMessages(prev => [...prev, { role: 'assistant', content: errorText! }])
      } else if (!sawDone) {
        // The stream closed without the terminal done event — treat as interrupted.
        setMessages(prev => [...prev, { role: 'assistant', content: INTERRUPTED }])
      } else {
        setMessages(prev => [...prev, { role: 'assistant', content: accumulated }])
      }
    } catch {
      setMessages(prev => [...prev, { role: 'assistant', content: FAILED }])
    } finally {
      setStreamingContent(null)
      setIsStreaming(false)
    }
  }, [messages])

  return { messages, streamingContent, isStreaming, sendMessage }
}
