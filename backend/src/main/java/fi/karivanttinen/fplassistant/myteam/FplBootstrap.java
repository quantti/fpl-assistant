package fi.karivanttinen.fplassistant.myteam;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * FPL's {@code bootstrap-static}, held for a few hours and shared by every conversation.
 *
 * <p><b>Why a chat-time fetch when {@code fpl_daily_price} already captures this payload.</b>
 * The daily table stores the parts a price question needs — price, ownership, status, news — and
 * not form, total points or points per game, which a squad card is mostly made of. More
 * importantly this is the one source that is certain to be describing the same season as the
 * {@code element} ids in a manager's picks: FPL renumbers every player every season, so filling
 * a squad from a database that has not synced the new season yet would name <i>different
 * players</i>, silently. One cached call a few times a day is the cheap way to be sure.
 *
 * <p>One entry, app-wide. When a refresh fails the previous snapshot keeps being served — a
 * squad card built from six-hour-old ownership is worth far more than no squad card.
 */
@Component
public class FplBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FplBootstrap.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** The body is a megabyte or two; this bounds a hang, it does not race the download. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() { };

    public record Element(int id, String webName, int teamId, String teamShort, int elementType,
                          int nowCost, String form, int totalPoints, String pointsPerGame,
                          String selectedByPercent, String status, String news,
                          Integer chanceNextRound) {

        /** Null when the player is fully available — the card only mentions the exceptions. */
        public String availability() {
            if (status == null || "a".equals(status)) {
                return null;
            }
            String label = switch (status) {
                case "d" -> "doubtful";
                case "i" -> "injured";
                case "s" -> "suspended";
                case "u" -> "unavailable";
                case "n" -> "not in the squad";
                default -> "flagged";
            };
            String chance = chanceNextRound == null ? "" : " (" + chanceNextRound + "% chance)";
            String reason = news == null || news.isBlank() ? "" : " - " + news;
            return label + chance + reason;
        }
    }

    /** One chip's playable window. There are two sets a season, and they differ per chip. */
    public record ChipWindow(String name, int startEvent, int stopEvent) {
        public boolean covers(int event) {
            return event >= startEvent && event <= stopEvent;
        }
    }

    /**
     * @param currentEvent the gameweek being played
     * @param nextEvent the gameweek the next deadline belongs to — the one a transfer or a chip
     *        activated right now would apply to, which is not the same question as which
     *        gameweek is being played
     */
    public record Snapshot(Map<Integer, Element> elements, List<ChipWindow> chips,
                           Map<Integer, Instant> deadlines, Integer currentEvent,
                           Integer nextEvent) {

        public Optional<Element> element(int id) {
            return Optional.ofNullable(elements.get(id));
        }

        public Optional<Instant> deadline(int event) {
            return Optional.ofNullable(deadlines.get(event));
        }
    }

    private final RestClient restClient;
    private final String bootstrapUrl;
    private final Duration ttl;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();
    private final AtomicReference<Instant> expiresAt = new AtomicReference<>(Instant.EPOCH);

    public FplBootstrap(RestClient.Builder restClientBuilder,
                        @Value("${fpl.daily-price.bootstrap-url}") String bootstrapUrl,
                        @Value("${fpl.myteam.bootstrap-ttl-minutes}") long ttlMinutes) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.bootstrapUrl = bootstrapUrl;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** The current snapshot, refreshing it first if it has aged out and the budget allows. */
    public Optional<Snapshot> snapshot(FplBudget budget) {
        Snapshot current = cached.get();
        if (current != null && Instant.now().isBefore(expiresAt.get())) {
            return Optional.of(current);
        }
        if (!budget.take()) {
            // Out of calls: stale is better than nothing, and nothing is what the caller gets
            // if there is no previous snapshot either.
            return Optional.ofNullable(current);
        }
        try {
            Map<String, Object> body = restClient.get().uri(bootstrapUrl).retrieve().body(JSON);
            if (body == null) {
                return Optional.ofNullable(current);
            }
            Snapshot fresh = parse(body);
            cached.set(fresh);
            expiresAt.set(Instant.now().plus(ttl));
            return Optional.of(fresh);
        } catch (RestClientException | ClassCastException e) {
            log.warn("Could not refresh bootstrap-static ({}); serving the previous snapshot",
                    e.getClass().getSimpleName());
            return Optional.ofNullable(current);
        }
    }

    static Snapshot parse(Map<String, Object> body) {
        Map<Integer, String> teamShort = new HashMap<>();
        if (body.get("teams") instanceof List<?> teams) {
            for (Object item : teams) {
                Map<String, Object> team = asMap(item);
                Integer id = asInt(team.get("id"));
                if (id != null) {
                    teamShort.put(id, asString(team.get("short_name")));
                }
            }
        }

        Map<Integer, Element> elements = new HashMap<>();
        if (body.get("elements") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> element = asMap(item);
                Integer id = asInt(element.get("id"));
                if (id == null) {
                    continue;
                }
                int teamId = orZero(asInt(element.get("team")));
                elements.put(id, new Element(id, asString(element.get("web_name")), teamId,
                        teamShort.getOrDefault(teamId, "?"),
                        orZero(asInt(element.get("element_type"))),
                        orZero(asInt(element.get("now_cost"))),
                        asString(element.get("form")),
                        orZero(asInt(element.get("total_points"))),
                        asString(element.get("points_per_game")),
                        asString(element.get("selected_by_percent")),
                        asString(element.get("status")),
                        asString(element.get("news")),
                        asInt(element.get("chance_of_playing_next_round"))));
            }
        }

        List<ChipWindow> chips = new java.util.ArrayList<>();
        if (body.get("chips") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> chip = asMap(item);
                String name = asString(chip.get("name"));
                Integer start = asInt(chip.get("start_event"));
                Integer stop = asInt(chip.get("stop_event"));
                if (name != null && start != null && stop != null) {
                    chips.add(new ChipWindow(name, start, stop));
                }
            }
        }

        Map<Integer, Instant> deadlines = new HashMap<>();
        Integer currentEvent = null;
        Integer nextEvent = null;
        if (body.get("events") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> event = asMap(item);
                Integer id = asInt(event.get("id"));
                if (id == null) {
                    continue;
                }
                String deadline = asString(event.get("deadline_time"));
                if (deadline != null) {
                    try {
                        deadlines.put(id, OffsetDateTime.parse(deadline).toInstant());
                    } catch (java.time.format.DateTimeParseException e) {
                        log.debug("unparseable deadline on event {}", id);
                    }
                }
                if (Boolean.TRUE.equals(event.get("is_current"))) {
                    currentEvent = id;
                }
                if (Boolean.TRUE.equals(event.get("is_next"))) {
                    nextEvent = id;
                }
            }
        }

        return new Snapshot(Map.copyOf(elements), List.copyOf(chips), Map.copyOf(deadlines),
                currentEvent, nextEvent);
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

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }
}
