export interface Message {
  role: 'user' | 'assistant'
  content: string
  /**
   * Client-side only: marks a turn the app generated to report a failure,
   * not something the model said. The spec reserves red for errors, and an
   * error is otherwise indistinguishable from an answer in the transcript.
   * Deliberately not part of the wire format — `ChatRequest` is built by
   * projecting to `{ role, content }`, so this never reaches the backend.
   */
  isError?: boolean
}

export interface ChatRequest {
  messages: Message[]
}
