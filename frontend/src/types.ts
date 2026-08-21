export interface Message {
  role: 'user' | 'assistant'
  content: string
  /**
   * Client-side only: marks a turn the app generated to report a failure,
   * not something the model said. The spec reserves red for errors, and an
   * error is otherwise indistinguishable from an answer in the transcript.
   * Never reaches the backend — since Task 4 the client sends only the new
   * message, and errors are not messages.
   */
  isError?: boolean
  /**
   * Client-side only: a note from the app about the conversation itself —
   * currently that the oldest turns no longer fit and were left out of what
   * the model was given. Not an error: nothing failed, but the user is
   * entitled to know the answer was written without the beginning.
   */
  isNotice?: boolean
}

/**
 * The wire contract for `POST /api/chat` since Task 4: one new user turn, and
 * the conversation it belongs to. The history is the server's — it reads it
 * back from the database — so the client cannot rewrite what was said, and
 * what was said outlives this browser. Reading it back is Task 7: nothing
 * here calls /api/conversations yet, so a reload still starts fresh.
 *
 * A null `conversationId` opens a new conversation; the id comes back as the
 * stream's first event.
 */
export interface ChatRequest {
  conversationId: string | null
  message: string
}

/** A conversation as the list shows it: enough to recognise and to order by. */
export interface Conversation {
  id: string
  title: string | null
  updatedAt: string
}

/** What `GET /api/usage` answers. Deliberately a share, never a count — see issue #11. */
export interface Usage {
  remainingPercent: number
  unlimited: boolean
  nextFreeAt: string | null
}
