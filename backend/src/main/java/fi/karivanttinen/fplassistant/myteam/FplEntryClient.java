package fi.karivanttinen.fplassistant.myteam;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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
 * The FPL endpoints that describe one manager: their entry, the squad locked for a gameweek,
 * the chips they have played, and a page of one of their leagues.
 *
 * <p>A <b>documented exception</b> to "external APIs only at sync time", like
 * {@link fi.karivanttinen.fplassistant.user.FplEntryLookup} before it: this data is per user and
 * per gameweek, so there is nothing to pre-sync.
 *
 * <p>Three outcomes, never two: "FPL says there is no such thing", "FPL could not be asked", and
 * a body. Collapsing the first two tells a user their team id is wrong while FPL is down, and
 * sends them hunting for a typo that is not there.
 *
 * <p><b>A 404 from the picks endpoint is not a failure.</b> FPL serves picks only for a gameweek
 * whose deadline has passed — asking for the next one answers 404 all week — so that case comes
 * back as an empty squad rather than an error.
 */
@Component
public class FplEntryClient {

    private static final Logger log = LoggerFactory.getLogger(FplEntryClient.class);

    /** A person is waiting on these, so they are as tight as FplEntryLookup's. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() { };

    public sealed interface Result<T> {
        record Ok<T>(T body) implements Result<T> { }

        /** FPL answered, and says there is no such entry or league. */
        record NotFound<T>() implements Result<T> { }

        /** FPL could not be reached or answered unusably. Says nothing about the id. */
        record Unavailable<T>() implements Result<T> { }
    }

    public record ClassicLeague(int id, String name, String type, Integer rank, Integer lastRank,
                                Integer totalEntries) {
        /** FPL's own flag: "x" is a league someone created, "s" one it puts everybody in. */
        public boolean userCreated() {
            return "x".equals(type);
        }
    }

    public record EntrySummary(int id, String teamName, String managerName, Integer overallRank,
                               Integer totalPoints, Integer currentEvent,
                               List<ClassicLeague> leagues) {
    }

    public record Pick(int element, int position, int multiplier, boolean captain,
                       boolean viceCaptain, int elementType) {
        public boolean starting() {
            return position <= 11;
        }
    }

    /** Bank and value are in 0.1m units, as everywhere else in this project. */
    public record LockedSquad(String activeChip, int bank, int value, Integer eventPoints,
                              List<Pick> picks) {
    }

    public record ChipPlayed(String name, int event) {
    }

    public record StandingsRow(int entryId, String entryName, int rank, Integer lastRank,
                               int totalPoints, int eventPoints) {
    }

    public record StandingsPage(String leagueName, int page, boolean hasNext,
                                List<StandingsRow> rows) {
    }

    private final RestClient restClient;
    private final String entryUrl;
    private final String picksUrl;
    private final String historyUrl;
    private final String standingsUrl;

    public FplEntryClient(RestClient.Builder restClientBuilder,
                          @Value("${fpl.myteam.entry-url}") String entryUrl,
                          @Value("${fpl.myteam.picks-url}") String picksUrl,
                          @Value("${fpl.myteam.history-url}") String historyUrl,
                          @Value("${fpl.myteam.standings-url}") String standingsUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.entryUrl = entryUrl;
        this.picksUrl = picksUrl;
        this.historyUrl = historyUrl;
        this.standingsUrl = standingsUrl;
    }

    public Result<EntrySummary> entry(int entryId) {
        return get(entryUrl.formatted(entryId), body -> new EntrySummary(
                orZero(asInt(body.get("id"))),
                asString(body.get("name")),
                managerName(body),
                asInt(body.get("summary_overall_rank")),
                asInt(body.get("summary_overall_points")),
                asInt(body.get("current_event")),
                classicLeagues(body)));
    }

    /**
     * The squad locked for that gameweek, or empty when FPL has none to show — a gameweek whose
     * deadline has not passed, which is most of the week.
     */
    public Result<Optional<LockedSquad>> picks(int entryId, int event) {
        Result<LockedSquad> result = get(picksUrl.formatted(entryId, event), body -> {
            Map<String, Object> history = asMap(body.get("entry_history"));
            List<Pick> picks = new ArrayList<>();
            if (body.get("picks") instanceof List<?> raw) {
                for (Object item : raw) {
                    Map<String, Object> pick = asMap(item);
                    Integer element = asInt(pick.get("element"));
                    if (element != null) {
                        picks.add(new Pick(element, orZero(asInt(pick.get("position"))),
                                orZero(asInt(pick.get("multiplier"))),
                                Boolean.TRUE.equals(pick.get("is_captain")),
                                Boolean.TRUE.equals(pick.get("is_vice_captain")),
                                orZero(asInt(pick.get("element_type")))));
                    }
                }
            }
            return new LockedSquad(asString(body.get("active_chip")),
                    orZero(asInt(history.get("bank"))), orZero(asInt(history.get("value"))),
                    asInt(history.get("points")), List.copyOf(picks));
        });
        return switch (result) {
            case Result.Ok<LockedSquad>(LockedSquad squad) -> new Result.Ok<>(Optional.of(squad));
            case Result.NotFound<LockedSquad> ignored -> new Result.Ok<>(Optional.empty());
            case Result.Unavailable<LockedSquad> ignored -> new Result.Unavailable<>();
        };
    }

    public Result<List<ChipPlayed>> chipsPlayed(int entryId) {
        return get(historyUrl.formatted(entryId), body -> {
            List<ChipPlayed> chips = new ArrayList<>();
            if (body.get("chips") instanceof List<?> raw) {
                for (Object item : raw) {
                    Map<String, Object> chip = asMap(item);
                    String name = asString(chip.get("name"));
                    Integer event = asInt(chip.get("event"));
                    if (name != null && event != null) {
                        chips.add(new ChipPlayed(name, event));
                    }
                }
            }
            return List.copyOf(chips);
        });
    }

    /** One page of a classic league's standings — fifty rows, as FPL serves them. */
    public Result<StandingsPage> standings(int leagueId, int page) {
        return get(standingsUrl.formatted(leagueId, page), body -> {
            Map<String, Object> league = asMap(body.get("league"));
            Map<String, Object> standings = asMap(body.get("standings"));
            List<StandingsRow> rows = new ArrayList<>();
            if (standings.get("results") instanceof List<?> raw) {
                for (Object item : raw) {
                    Map<String, Object> row = asMap(item);
                    Integer entryId = asInt(row.get("entry"));
                    if (entryId != null) {
                        // player_name is deliberately not read: it is a third party's legal name,
                        // and everything here ends up in a prompt sent to the model provider.
                        rows.add(new StandingsRow(entryId, asString(row.get("entry_name")),
                                orZero(asInt(row.get("rank"))), asInt(row.get("last_rank")),
                                orZero(asInt(row.get("total"))),
                                orZero(asInt(row.get("event_total")))));
                    }
                }
            }
            return new StandingsPage(asString(league.get("name")),
                    orZero(asInt(standings.get("page"))),
                    Boolean.TRUE.equals(standings.get("has_next")), List.copyOf(rows));
        });
    }

    private <T> Result<T> get(String url, java.util.function.Function<Map<String, Object>, T> read) {
        try {
            ResponseEntity<Map<String, Object>> response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, ignored) -> { })
                    .toEntity(JSON);
            if (response.getStatusCode().value() == 404) {
                return new Result.NotFound<>();
            }
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                log.warn("FPL answered {} for a my-team request; reporting it as unavailable",
                        response.getStatusCode().value());
                return new Result.Unavailable<>();
            }
            return new Result.Ok<>(read.apply(body));
        } catch (RestClientException | ClassCastException e) {
            // Never the URI: it embeds the entry id, which links an account to a real person's
            // public FPL profile.
            log.warn("Could not read from FPL ({}); reporting it as unavailable",
                    e.getClass().getSimpleName());
            return new Result.Unavailable<>();
        }
    }

    private static String managerName(Map<String, Object> body) {
        String first = asString(body.get("player_first_name"));
        String last = asString(body.get("player_last_name"));
        if (first == null && last == null) {
            return null;
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    private static List<ClassicLeague> classicLeagues(Map<String, Object> body) {
        Map<String, Object> leagues = asMap(body.get("leagues"));
        List<ClassicLeague> classic = new ArrayList<>();
        if (leagues.get("classic") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> league = asMap(item);
                Integer id = asInt(league.get("id"));
                if (id != null) {
                    classic.add(new ClassicLeague(id, asString(league.get("name")),
                            asString(league.get("league_type")), asInt(league.get("entry_rank")),
                            asInt(league.get("entry_last_rank")), asInt(league.get("rank_count"))));
                }
            }
        }
        return List.copyOf(classic);
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
