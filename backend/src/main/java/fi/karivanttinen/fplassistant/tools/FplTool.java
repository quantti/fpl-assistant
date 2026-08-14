package fi.karivanttinen.fplassistant.tools;

/**
 * Marker for chat tool beans. {@code ChatService} registers every implementation it finds, so
 * adding a tool class is registration-complete by itself — a hand-maintained list fails
 * silently when an entry is forgotten (the model just never sees the tool).
 *
 * <p>An implementation must declare at least one {@code @Tool}-annotated method: Spring AI's
 * {@code MethodToolCallbackProvider} rejects a tool object without one at request time, which
 * would kill the stream for every chat request.
 */
public interface FplTool {
}
