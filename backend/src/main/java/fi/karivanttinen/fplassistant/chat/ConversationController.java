package fi.karivanttinen.fplassistant.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import fi.karivanttinen.fplassistant.config.TokenSubject;
import fi.karivanttinen.fplassistant.shortlist.PlayerCatalogueMetadata;
import fi.karivanttinen.fplassistant.shortlist.PlayerCatalogueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationStore conversations;
    private final PlayerCatalogueStore catalogues;

    public ConversationController(ConversationStore conversations, PlayerCatalogueStore catalogues) {
        this.conversations = conversations;
        this.catalogues = catalogues;
    }

    public record ConversationResponse(UUID id, String title, Instant updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageResponse(String role, String content, Instant createdAt, UUID catalogueId) {}

    public record HistoryResponse(List<MessageResponse> messages,
                                  Map<UUID, PlayerCatalogueMetadata> catalogues) {}

    @GetMapping
    public List<ConversationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return conversations.list(TokenSubject.userId(jwt)).stream()
                .map(c -> new ConversationResponse(c.id(), c.title(), c.updatedAt()))
                .toList();
    }

    @GetMapping("/{id}/messages")
    public HistoryResponse messages(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = TokenSubject.userId(jwt);
        conversations.findOwned(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such conversation"));

        List<ConversationStore.StoredMessage> stored = conversations.messages(id);
        Map<UUID, PlayerCatalogueMetadata> metadata = new LinkedHashMap<>();
        stored.stream().map(ConversationStore.StoredMessage::catalogueId)
                .filter(Objects::nonNull).distinct()
                .forEach(catalogueId -> catalogue(catalogueId, metadata));
        List<MessageResponse> messages = stored.stream()
                .map(message -> new MessageResponse(message.role(), message.content(),
                        message.createdAt(), metadata.containsKey(message.catalogueId())
                                ? message.catalogueId() : null))
                .toList();
        return new HistoryResponse(messages, metadata);
    }

    private void catalogue(UUID id, Map<UUID, PlayerCatalogueMetadata> metadata) {
        try {
            catalogues.find(id).map(PlayerCatalogueMetadata::from)
                    .ifPresent(value -> metadata.put(id, value));
        } catch (RuntimeException e) {
            log.error("could not read player catalogue {} for conversation history", id, e);
        }
    }
}
