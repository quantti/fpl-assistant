package dev.quantti.fplassistant.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reading back what was said: the caller's conversations, and one conversation's messages.
 *
 * <p>Both filter on the {@code sub} claim, and a conversation belonging to someone else answers
 * {@code 404} — the same answer as one that does not exist, so the endpoint cannot be used to
 * find out which ids are real.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore conversations;

    public ConversationController(ConversationStore conversations) {
        this.conversations = conversations;
    }

    public record ConversationResponse(UUID id, String title, Instant updatedAt) {}

    public record MessageResponse(String role, String content, Instant createdAt) {}

    @GetMapping
    public List<ConversationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return conversations.list(UUID.fromString(jwt.getSubject())).stream()
                .map(c -> new ConversationResponse(c.id(), c.title(), c.updatedAt()))
                .toList();
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        conversations.findOwned(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such conversation"));

        return conversations.messages(id).stream()
                .map(m -> new MessageResponse(m.role(), m.content(), m.createdAt()))
                .toList();
    }
}
