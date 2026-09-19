package fi.karivanttinen.fplassistant.chat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import fi.karivanttinen.fplassistant.trace.ChatTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

/** Fail-open persistence lifecycle for one accepted chat turn. */
final class ChatTraceSession {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceSession.class);

    private final ChatTraceStore store;
    private final UUID userId;
    private final UUID conversationId;
    private final ChatTraceCollector collector = new ChatTraceCollector();
    private final AtomicReference<CompletableFuture<UUID>> started = new AtomicReference<>();

    ChatTraceSession(ChatTraceStore store, UUID userId, UUID conversationId) {
        this.store = store;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    ChatTraceCollector collector() {
        return collector;
    }

    /** Called when the first effective provider prompt is known. Never performs JDBC inline. */
    void start() {
        ensureStarted();
    }

    void complete(String answer) {
        collector.complete(answer);
        persistTerminal();
    }

    void fail(String partial, Throwable failure) {
        collector.fail(partial, failure);
        persistTerminal();
    }

    private CompletableFuture<UUID> ensureStarted() {
        CompletableFuture<UUID> existing = started.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<UUID> created = new CompletableFuture<>();
        if (!started.compareAndSet(null, created)) {
            return started.get();
        }
        var initial = collector.snapshot();
        try {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    created.complete(store.start(userId, conversationId, initial));
                } catch (RuntimeException e) {
                    log.warn("Could not start chat trace; continuing without telemetry", e);
                    created.complete(null);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not schedule chat trace start; continuing without telemetry", e);
            created.complete(null);
        }
        return created;
    }

    private void persistTerminal() {
        ensureStarted().whenComplete((id, startFailure) -> {
            if (id == null || startFailure != null) {
                return;
            }
            var terminal = collector.snapshot();
            try {
                Schedulers.boundedElastic().schedule(() -> {
                    try {
                        store.complete(id, terminal);
                    } catch (RuntimeException e) {
                        log.warn("Could not complete chat trace {}; chat result is unaffected", id, e);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Could not schedule chat trace completion {}; chat result is unaffected", id, e);
            }
        });
    }
}
