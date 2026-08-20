package dev.quantti.fplassistant.chat;

import dev.quantti.fplassistant.tools.FplTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    // The bound on per-request cost and context growth. Since Task 4 the history is the
    // server's, so this is also how much of a conversation is read back per turn.
    public static final int HISTORY_WINDOW = 20;

    private final ChatClient chatClient;
    private final List<FplTool> tools;

    public ChatService(ChatClient chatClient, List<FplTool> tools) {
        this.chatClient = chatClient;
        this.tools = tools;
    }

    /**
     * Streams the answer to a conversation whose turns are given oldest-first. The caller has
     * already bounded them to {@link #HISTORY_WINDOW}; trimming here is about the shape of the
     * window rather than its size.
     */
    public Flux<String> stream(List<ChatTurn> turns) {
        List<ChatTurn> window = turns.subList(Math.max(0, turns.size() - HISTORY_WINDOW), turns.size());
        // The Anthropic API rejects a history that opens on an assistant turn, and an
        // even-sized window over an odd-length alternating conversation always does.
        while (!window.isEmpty() && !ChatTurn.USER.equals(window.getFirst().role())) {
            window = window.subList(1, window.size());
        }
        List<Message> history = merged(window).stream()
                .map(m -> (Message) switch (m.role()) {
                    case ChatTurn.USER -> new UserMessage(m.content());
                    default -> new AssistantMessage(m.content());
                })
                .toList();

        return chatClient.prompt()
                .messages(history)
                .tools(tools.toArray())
                .stream()
                .content();
    }

    /**
     * Consecutive turns of the same role, joined. The Anthropic API rejects a history with two
     * user turns in a row, and stored history produces exactly that whenever an answer failed:
     * the user's message is persisted before the model is called, the failed answer is not, and
     * the next question lands straight after it. The frontend used to do this merge on the
     * history it held; since Task 4 it holds none.
     */
    static List<ChatTurn> merged(List<ChatTurn> turns) {
        List<ChatTurn> out = new ArrayList<>();
        for (ChatTurn turn : turns) {
            if (!out.isEmpty() && out.getLast().role().equals(turn.role())) {
                ChatTurn previous = out.removeLast();
                out.add(new ChatTurn(turn.role(), previous.content() + "\n\n" + turn.content()));
            } else {
                out.add(turn);
            }
        }
        return out;
    }
}
