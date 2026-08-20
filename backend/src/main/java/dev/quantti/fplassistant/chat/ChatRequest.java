package dev.quantti.fplassistant.chat;

/**
 * One new user turn, and the conversation it belongs to.
 *
 * <p>Since Task 4 the client no longer sends the history: the server owns it, so a client cannot
 * rewrite what was said, and a reload no longer starts from nothing. A null or blank
 * {@code conversationId} opens a new conversation owned by the caller; the id of the conversation
 * the turn landed in comes back as the stream's first event.
 */
public record ChatRequest(String conversationId, String message) {}
