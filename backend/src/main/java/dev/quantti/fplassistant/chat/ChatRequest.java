package dev.quantti.fplassistant.chat;

/**
 * One new user turn, and the conversation it belongs to.
 *
 * <p>Since Task 4 the client no longer sends the history: the server owns it, so a client cannot
 * rewrite what was said, and what was said survives the browser that said it. A null or blank
 * {@code conversationId} opens a new conversation owned by the caller; the id of the conversation
 * the turn landed in comes back as the stream's first event.
 *
 * <p>The client does not yet <em>read</em> any of that back — Task 7 adds the conversation list,
 * and until then a reload still starts a new conversation.
 */
public record ChatRequest(String conversationId, String message) {}
