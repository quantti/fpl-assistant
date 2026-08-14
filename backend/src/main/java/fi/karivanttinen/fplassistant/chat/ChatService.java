package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.tools.FplTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    // The client resends the full conversation every turn; this is the only bound on
    // per-request cost and context growth until Phase 5 moves history server-side.
    static final int HISTORY_WINDOW = 20;

    private final ChatClient chatClient;
    private final List<FplTool> tools;

    public ChatService(ChatClient chatClient, List<FplTool> tools) {
        this.chatClient = chatClient;
        this.tools = tools;
    }

    public Flux<String> stream(ChatRequest request) {
        List<ChatRequest.MessageDto> messages = request.messages();
        List<ChatRequest.MessageDto> window =
                messages.subList(Math.max(0, messages.size() - HISTORY_WINDOW), messages.size());
        // The Anthropic API rejects a history that opens on an assistant turn, and an
        // even-sized window over an odd-length alternating conversation always does.
        while (!window.isEmpty() && !"user".equals(window.getFirst().role())) {
            window = window.subList(1, window.size());
        }
        List<Message> history = window.stream()
                .map(m -> (Message) switch (m.role()) {
                    case "user" -> new UserMessage(m.content());
                    default -> new AssistantMessage(m.content());
                })
                .toList();

        return chatClient.prompt()
                .messages(history)
                .tools(tools.toArray())
                .stream()
                .content();
    }
}
