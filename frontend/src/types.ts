export interface Message {
  role: 'user' | 'assistant'
  content: string
  /**
   * Client-side only: marks a turn the app generated to report a failure,
   * not something the model said. The spec reserves red for errors, and an
   * error is otherwise indistinguishable from an answer in the transcript.
   * Deliberately not part of the wire format — the request body is built by
   * projecting to `{ role, content }`, so this never reaches the backend.
   */
  isError?: boolean
}

/**
 * The wire contract for `POST /api/chat`. `messages` is deliberately a
 * projection of `Message` — a client-only field like `isError` is not part
 * of the request, and typing it as `Message[]` here would let a future call
 * site send it through.
 */
export interface ChatRequest {
  messages: Array<Pick<Message, 'role' | 'content'>>
}
