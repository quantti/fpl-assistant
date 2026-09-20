import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetch } from '../auth/apiFetch';
import { type Conversation, type Message, type PlayerCatalogue } from '../types';

interface UseConversationsReturn {
  conversations: Conversation[];
  /** Null while a new conversation has been started but not yet sent to. */
  currentId: string | null;
  /** The latest selection still loading; currentId stays on the last successful conversation. */
  pendingId: string | null;
  /** True when the list could not be read. An empty sidebar otherwise means "no conversations". */
  failed: boolean;
  select: (id: string) => Promise<Message[] | null>;
  startNew: () => void;
  /** Called when a turn lands, so a conversation created by sending appears in the list. */
  refresh: () => Promise<void>;
  adopt: (id: string) => void;
}

/**
 * The conversation list beside the thread, and which of them is open.
 *
 * <p>The list is the server's answer, refetched rather than patched: the title is written by the
 * server when a conversation is created, and `updated_at` decides the order, so a list this hook
 * edited by hand would drift from the one a reload shows.
 */
export function useConversations(): UseConversationsReturn {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const selectionRef = useRef(0);

  const refresh = useCallback(async () => {
    // A failed read must not look like an empty list. The fetch happens once on mount, so a
    // backend that was down at that moment used to leave the sidebar empty for the rest of the
    // session — indistinguishable from having no conversations at all, and with nothing to
    // retry.
    try {
      const response = await apiFetch('/api/conversations');
      if (!response.ok) {
        setFailed(true);
        return;
      }
      setConversations(await response.json());
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  /** The conversation's messages, or null when unreadable or superseded by a newer selection. */
  const select = useCallback(
    async (id: string): Promise<Message[] | null> => {
      const selection = ++selectionRef.current;
      setPendingId(id);
      try {
        const response = await apiFetch(`/api/conversations/${id}/messages`);
        if (!response.ok) {
          // Refresh a removed conversation out of the list without holding selection loading open.
          if (response.status === 404) void refresh();
          return null;
        }
        type StoredMessage = {
          role: 'user' | 'assistant';
          content: string;
          catalogueId?: string;
          catalogue?: PlayerCatalogue;
        };
        const payload = (await response.json()) as
          | StoredMessage[]
          | { messages: StoredMessage[]; catalogues?: Record<string, PlayerCatalogue> };
        if (selection !== selectionRef.current) return null;
        const stored = Array.isArray(payload) ? payload : payload.messages;
        if (!Array.isArray(stored)) return null;
        const catalogues = Array.isArray(payload) ? undefined : payload.catalogues;
        const messages = stored.map(({ role, content, catalogueId, catalogue }) => {
          if (typeof content !== 'string' || (role !== 'user' && role !== 'assistant')) {
            throw new TypeError('Invalid conversation message');
          }
          const resolved = catalogue ?? (catalogueId ? catalogues?.[catalogueId] : undefined);
          return {
            role,
            content,
            ...(role === 'assistant' && resolved ? { catalogue: resolved } : {}),
          };
        });
        setCurrentId(id);
        return messages;
      } catch {
        return null;
      } finally {
        if (selection === selectionRef.current) setPendingId(null);
      }
    },
    [refresh],
  );

  const startNew = useCallback(() => {
    selectionRef.current++;
    setPendingId(null);
    setCurrentId(null);
  }, []);

  /** The id the server gave a conversation that was created by sending the first message. */
  const adopt = useCallback((id: string) => setCurrentId(id), []);

  return { conversations, currentId, pendingId, failed, select, startNew, refresh, adopt };
}
