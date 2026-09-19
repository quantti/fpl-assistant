package dev.quantti.fplassistant.trace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Admin-only trace reads. Authorization is inherited from the {@code /api/admin/**} path. */
@RestController
@RequestMapping("/api/admin/chat-traces")
public class ChatTraceAdminController {

    static final int DEFAULT_LIMIT = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatTraceStore traces;

    public ChatTraceAdminController(ChatTraceStore traces) {
        this.traces = traces;
    }

    public record SummaryView(UUID id, UUID userId, UUID conversationId, Instant createdAt,
                              Instant completedAt) {}

    public record DetailView(UUID id, UUID userId, UUID conversationId, Object payload,
                             Instant createdAt, Instant completedAt) {}

    @GetMapping
    public ResponseEntity<List<SummaryView>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Instant before) {
        if (limit < 1 || limit > ChatTraceStore.MAX_LIST_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "limit must be between 1 and " + ChatTraceStore.MAX_LIST_SIZE);
        }
        List<SummaryView> body = traces.list(limit, before).stream()
                .map(t -> new SummaryView(t.id(), t.userId(), t.conversationId(), t.createdAt(),
                        t.completedAt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DetailView> detail(@PathVariable UUID id) {
        ChatTraceStore.Trace trace = traces.find(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Trace not found."));
        DetailView body = new DetailView(trace.id(), trace.userId(), trace.conversationId(),
                JSON.convertValue(trace.payload(), Object.class), trace.createdAt(),
                trace.completedAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
