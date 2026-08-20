package fi.karivanttinen.fplassistant.chat;

/**
 * One turn of a conversation as this application handles it: the role that produced it and its
 * text. Named for the turn rather than the message because {@code Message} is Spring AI's own
 * type, which {@link ChatService} converts to.
 *
 * <p>Since Task 4 the client no longer sends these — the server reads them back from
 * {@code fpl_message}. The record survives as the shape both the store and the model speak.
 */
public record ChatTurn(String role, String content) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
