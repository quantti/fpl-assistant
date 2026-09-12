package dev.quantti.fplassistant.myteam;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Points scored so far in one gameweek, for every player at once: FPL's
 * {@code /event/{event}/live/}. One call per gameweek, cached app-wide for the same TTL as the
 * squad, because the numbers are the same whoever asks.
 *
 * <p>A chat-time read under the same documented exception as {@link FplEntryClient}: the answer
 * changes every minute of a match, so there is nothing to pre-sync. The body is around a
 * megabyte, hence {@link FplBootstrap}'s timeouts rather than the entry client's.
 *
 * <p>Unlike the bootstrap, nothing stale is served: an expired entry plus a failed refresh means
 * no per-player points, and the card says so. The cached value carries the instant it was read,
 * which is what the card dates the numbers by — the squad's own {@code fetchedAt} is assembly
 * time, even on a cache hit.
 */
@Component
public class FplLivePoints {

    private static final Logger log = LoggerFactory.getLogger(FplLivePoints.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    /** Only the gameweek being played and its neighbours are ever asked for. */
    private static final int MAX_EVENTS = 4;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() { };

    public record ElementPoints(int points, int minutes) {
    }

    /** One gameweek's feed and the instant it was read from FPL. */
    public record Live(int event, Instant fetchedAt, Map<Integer, ElementPoints> elements) {
        public Optional<ElementPoints> of(int elementId) {
            return Optional.ofNullable(elements.get(elementId));
        }
    }

    private final RestClient restClient;
    private final String liveUrl;
    private final TtlCache<Integer, Live> cache;

    public FplLivePoints(RestClient.Builder restClientBuilder,
                         @Value("${fpl.myteam.live-url}") String liveUrl,
                         @Value("${fpl.myteam.cache-ttl-minutes}") long ttlMinutes) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.liveUrl = liveUrl;
        this.cache = new TtlCache<>(Duration.ofMinutes(ttlMinutes), MAX_EVENTS);
    }

    /** The gameweek's feed, from cache when fresh; empty when refused, failed or not yet open. */
    public Optional<Live> forEvent(int event, FplBudget budget) {
        Optional<Live> cached = cache.get(event);
        if (cached.isPresent()) {
            return cached;
        }
        if (!budget.take()) {
            return Optional.empty();
        }
        try {
            ResponseEntity<Map<String, Object>> response = restClient.get()
                    .uri(liveUrl.formatted(event))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, ignored) -> { })
                    .toEntity(JSON);
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                // 404 is the normal answer before the deadline; anything else is worth a line.
                if (response.getStatusCode().value() != 404) {
                    log.warn("FPL answered {} for the live feed of gameweek {}",
                            response.getStatusCode().value(), event);
                }
                return Optional.empty();
            }
            Live live = parse(event, Instant.now(), body);
            cache.put(event, live);
            return Optional.of(live);
        } catch (RestClientException | ClassCastException e) {
            log.warn("Could not read the live feed of gameweek {} ({})", event,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Live parse(int event, Instant fetchedAt, Map<String, Object> body) {
        Map<Integer, ElementPoints> elements = new HashMap<>();
        if (body.get("elements") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> element = asMap(item);
                Integer id = asInt(element.get("id"));
                if (id == null) {
                    continue;
                }
                Map<String, Object> stats = asMap(element.get("stats"));
                elements.put(id, new ElementPoints(orZero(asInt(stats.get("total_points"))),
                        orZero(asInt(stats.get("minutes")))));
            }
        }
        return new Live(event, fetchedAt, Map.copyOf(elements));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
