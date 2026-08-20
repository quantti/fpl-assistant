import { useCallback, useEffect, useState } from 'react'
import { apiFetch } from '../auth/apiFetch'
import { Conversation, Message } from '../types'

interface UseConversationsReturn {
  conversations: Conversation[]
  /** Null while a new conversation has been started but not yet sent to. */
  currentId: string | null
  loadingMessages: boolean
  select: (id: string) => Promise<Message[] | null>
  startNew: () => void
  /** Called when a turn lands, so a conversation created by sending appears in the list. */
  refresh: () => Promise<void>
  adopt: (id: string) => void
}

/**
 * The conversation list beside the thread, and which of them is open.
 *
 * <p>The list is the server's answer, refetched rather than patched: the title is written by the
 * server when a conversation is created, and `updated_at` decides the order, so a list this hook
 * edited by hand would drift from the one a reload shows.
 */
export function useConversations(): UseConversationsReturn {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  const [loadingMessages, setLoadingMessages] = useState(false)

  const refresh = useCallback(async () => {
    const response = await apiFetch('/api/conversations')
    if (!response.ok) return
    setConversations(await response.json())
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** The conversation's messages, or null when it could not be read — the caller keeps what it has. */
  const select = useCallback(async (id: string): Promise<Message[] | null> => {
    setLoadingMessages(true)
    try {
      const response = await apiFetch(`/api/conversations/${id}/messages`)
      if (!response.ok) {
        // A conversation that is gone should not stay in the list pretending otherwise.
        if (response.status === 404) await refresh()
        return null
      }
      const stored: Array<{ role: 'user' | 'assistant'; content: string }> = await response.json()
      setCurrentId(id)
      return stored.map(({ role, content }) => ({ role, content }))
    } finally {
      setLoadingMessages(false)
    }
  }, [refresh])

  const startNew = useCallback(() => setCurrentId(null), [])

  /** The id the server gave a conversation that was created by sending the first message. */
  const adopt = useCallback((id: string) => setCurrentId(id), [])

  return { conversations, currentId, loadingMessages, select, startNew, refresh, adopt }
}
