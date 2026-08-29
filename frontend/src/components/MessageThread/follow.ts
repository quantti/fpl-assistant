/** The three scroll metrics of the thread element; a plain object so the decision is testable. */
export interface ScrollMetrics {
  scrollHeight: number
  scrollTop: number
  clientHeight: number
}

/**
 * How close to the bottom, in px, still counts as "reading the latest text". Wide enough
 * that the tail of a smooth scroll does not read as the reader having left; narrow enough
 * that one scrolled-up line does.
 */
export const FOLLOW_TOLERANCE = 40

/**
 * Whether the thread should keep following its bottom as content arrives.
 *
 * Streaming appends to the thread many times a second, and scrolling to the end on each
 * append is right only while the reader is there. Once they have scrolled up to re-read,
 * every further append must leave the view where they put it — otherwise the answer cannot
 * be read until it has finished, which is the report behind KAR-46.
 */
export function followsBottom(el: ScrollMetrics): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight < FOLLOW_TOLERANCE
}
