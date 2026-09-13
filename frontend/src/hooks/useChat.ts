import { useState, useCallback, useEffect, useRef } from 'react'
import { Message, PlayerCatalogue } from '../types'
import { sendChatTurn, GONE } from './chatTransport'

interface UseChatOptions {
  /** The conversation being continued, or null to open a new one on the next message. */
  conversationId: string | null
  /** The id the server gave the turn — a new conversation's, or null when it is gone. */
  onConversation: (id: string | null) => void
  /** The still-owning turn settled: refresh server-owned list order and conversation titles. */
  onTurnFinished: () => void
}

interface UseChatReturn {
  messages: Message[]
  /**
   * Shows another conversation's transcript, ending this browser's read of any answer still
   * streaming into the old one. The server may continue generating and storing that answer;
   * what must not happen is it landing under a different conversation's messages.
   */
  replaceMessages: (messages: Message[]) => void
  streamingContent: string | null
  isStreaming: boolean
  sendMessage: (content: string) => Promise<void>
}

export function useChat({ conversationId, onConversation, onTurnFinished }: UseChatOptions): UseChatReturn {
  const [messages, setMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  // Signing out unmounts this view. Cancelling the browser read does not promise that server-side
  // generation stopped, but it avoids draining a response that can no longer be shown.
  useEffect(() => () => {
    const controller = abortRef.current
    abortRef.current = null
    controller?.abort()
  }, [])

  const sendMessage = useCallback(async (content: string) => {
    if (abortRef.current) return // single-flight: the hook does not support overlapping sends

    const controller = new AbortController()
    abortRef.current = controller
    const ownsTurn = () => abortRef.current === controller && !controller.signal.aborted

    const userMessage: Message = { role: 'user', content }
    setMessages(prev => [...prev, userMessage])
    setStreamingContent('')
    setIsStreaming(true)

    let turnCatalogue: PlayerCatalogue | undefined
    try {
      const outcome = await sendChatTurn({ conversationId, message: content }, controller.signal, {
        onConversation: id => {
          if (ownsTurn()) onConversation(id)
        },
        onTruncated: dropped => {
          if (!ownsTurn()) return
          setMessages(prev => [...prev, {
            role: 'assistant',
            content: dropped > 0 && Number.isFinite(dropped)
              ? `This conversation has grown too long to send in full. The ${dropped} oldest `
                + 'messages were left out of the answer below — they are still shown above, and '
                + 'still yours, but the assistant did not see them. Start a new conversation if '
                + 'the beginning matters.'
              : 'This conversation has grown too long to send in full. Its oldest messages were '
                + 'left out of the answer below.',
            isNotice: true,
          }])
        },
        onCatalogue: catalogue => {
          if (ownsTurn()) turnCatalogue = catalogue
        },
        onToken: accumulated => {
          if (ownsTurn()) setStreamingContent(accumulated)
        },
      })

      if (!ownsTurn()) return

      switch (outcome.kind) {
        case 'completed':
          setMessages(prev => [...prev, {
            role: 'assistant',
            content: outcome.content,
            ...(turnCatalogue ? { catalogue: turnCatalogue } : {}),
          }])
          break
        case 'failed':
          setMessages(prev => [...prev, { role: 'assistant', content: outcome.message, isError: true }])
          break
        case 'gone':
          // The conversation is gone — deleted elsewhere, or never the caller's. Forget the id
          // so the next message opens a new one instead of failing the same way forever.
          onConversation(null)
          setMessages(prev => [...prev, { role: 'assistant', content: GONE, isError: true }])
          break
        case 'unauthorized':
        case 'aborted':
          // 401: apiFetch has already signed out, so the sign-in screen is about to replace this
          // view — appending an error would only flash a message nobody gets to read. Abort: this
          // component going away is not a failure to report to a user who has left.
          break
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null
        setStreamingContent(null)
        setIsStreaming(false)
        // The list orders by last use and titles a new conversation from its first message, so
        // both are the server's answer to fetch rather than something to guess at here.
        onTurnFinished()
      }
    }
  }, [conversationId, onConversation, onTurnFinished])

  const replaceMessages = useCallback((next: Message[]) => {
    const controller = abortRef.current
    abortRef.current = null
    controller?.abort()
    setStreamingContent(null)
    setIsStreaming(false)
    setMessages(next)
  }, [])

  return { messages, replaceMessages, streamingContent, isStreaming, sendMessage }
}
