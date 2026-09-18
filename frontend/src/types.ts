export interface CataloguePlayer {
  elementId: number;
  webName: string;
  fullName: string;
  teamShort: string;
  teamName: string;
  elementType: number;
  aliases: string[];
}

export interface PlayerCatalogue {
  catalogueId: string;
  season: string;
  players: CataloguePlayer[];
}

export interface Message {
  role: 'user' | 'assistant';
  content: string;
  /** Immutable identity evidence attached only to a completed assistant answer. */
  catalogue?: PlayerCatalogue;
  /**
   * Client-side only: marks a turn the app generated to report a failure,
   * not something the model said. The spec reserves red for errors, and an
   * error is otherwise indistinguishable from an answer in the transcript.
   * Never reaches the backend — since Task 4 the client sends only the new
   * message, and errors are not messages.
   */
  isError?: boolean;
  /**
   * Client-side only: a note from the app about the conversation itself —
   * currently that the oldest turns no longer fit and were left out of what
   * the model was given. Not an error: nothing failed, but the user is
   * entitled to know the answer was written without the beginning.
   */
  isNotice?: boolean;
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
  conversationId: string | null;
  message: string;
}

/** A conversation as the list shows it: enough to recognise and to order by. */
export interface Conversation {
  id: string;
  title: string | null;
  updatedAt: string;
}

/**
 * What `GET /api/usage` answers. Always a share; on the free plan a count as well.
 *
 * Issue #11's "a share, never a count" now holds for the paid plan only — a free tier's limit is
 * the offer, and hiding it makes the product evasive about the one number a prospective user
 * needs. `used` and `limit` are therefore absent rather than null when they are not shown, which
 * is why they are optional here.
 */
export interface Usage {
  plan: 'FREE';
  remainingPercent: number;
  unlimited: boolean;
  nextFreeAt: string | null;
  used?: number;
  limit?: number;
}

/**
 * What `GET /api/profile` answers. `admin` is for showing the Admin link; the API refuses a
 * non-admin regardless of what the page shows, so nothing in the browser decides access on it.
 */
export interface Profile {
  fplTeamId: number | null;
  admin: boolean;
}
