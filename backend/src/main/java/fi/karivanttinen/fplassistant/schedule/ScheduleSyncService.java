package fi.karivanttinen.fplassistant.schedule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.price.DailyPriceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Captures FPL's gameweeks and fixtures into {@code fpl_gameweek} and {@code fpl_fixture}.
 *
 * <p>The schedule counterpart of {@code DailyPriceSyncService}, and built on the same three
 * rules: the season code comes from the UK date, not from the database; team names come from
 * the payload's own {@code teams} block, not from tapas; and the response is refused when it
 * still describes a finished season. The mapping methods are static and package-private so
 * they are unit-tested from captured payloads without a network.
 */
@Component
public class ScheduleSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSyncService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> ARRAY = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String gameweekTable;
    private final String fixtureTable;
    private final String bootstrapUrl;
    private final String fixturesUrl;

    public ScheduleSyncService(RestClient.Builder restClientBuilder, JdbcTemplate jdbc, TransactionTemplate tx,
                               @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                               @Value("${fpl.schedule.fixture-table-name}") String fixtureTable,
                               @Value("${fpl.daily-price.bootstrap-url}") String bootstrapUrl,
                               @Value("${fpl.schedule.fixtures-url}") String fixturesUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.jdbc = jdbc;
        this.tx = tx;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
        this.bootstrapUrl = bootstrapUrl;
        this.fixturesUrl = fixturesUrl;
    }

    /** @return gameweek rows plus fixture rows written, for logging and exit codes */
    public int sync() {
        Map<String, Object> bootstrap = restClient.get().uri(bootstrapUrl).retrieve().body(OBJECT);
        List<Map<String, Object>> fixtures = restClient.get().uri(fixturesUrl).retrieve().body(ARRAY);
        LocalDate today = LocalDate.now(DailyPriceSyncService.UK);
        if (bootstrap == null || fixtures == null) {
            log.warn("schedule sync: empty response; nothing written");
            return 0;
        }
        if (DailyPriceSyncService.responseBelongsToAFinishedSeason(bootstrap, today)) {
            log.warn("schedule sync: bootstrap-static still carries last season; nothing written");
            return 0;
        }
        String season = DailyPriceSyncService.seasonCode(today);
        List<Object[]> gameweeks = toGameweekRows(bootstrap, season);
        List<Object[]> fixtureRows = toFixtureRows(fixtures, teamsOf(bootstrap), season);
        // Both blocks, not just one: the write ends by deleting whatever this capture did not
        // carry, so an empty fixtures array (FPL has served one during maintenance) would
        // otherwise wipe the season's fixtures rather than leave yesterday's standing.
        if (gameweeks.isEmpty() || fixtureRows.isEmpty()) {
            log.warn("schedule sync: {} events and {} fixtures in the response; nothing written",
                    gameweeks.size(), fixtureRows.size());
            return 0;
        }
        int written = tx.execute(status -> write(season, gameweeks, fixtureRows));
        log.info("schedule sync: {} rows captured for {}", written, season);
        return written;
    }

    /**
     * Upserts every row, then deletes the season's rows this capture did not carry — by id, so the
     * result does not depend on clocks. Called inside {@code tx.execute} from {@link #sync()} so a
     * reader never sees a half-written season; public so a test can drive it without the network.
     * (Do NOT replace the template with {@code @Transactional} on this method: {@code sync()} calls it
     * on {@code this}, and Spring's proxy does not intercept self-invocation, so the annotation would
     * silently do nothing.)
     */
    public int write(String season, List<Object[]> gameweeks, List<Object[]> fixtures) {
        int written = 0;
        if (!gameweeks.isEmpty()) {
            for (int c : jdbc.batchUpdate("""
                    INSERT INTO %s (season, id, name, deadline_time, finished, data_checked, is_current, is_next,
                                    average_entry_score, highest_score, most_captained, most_transferred_in)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (season, id) DO UPDATE SET
                        name = EXCLUDED.name, deadline_time = EXCLUDED.deadline_time,
                        finished = EXCLUDED.finished, data_checked = EXCLUDED.data_checked,
                        is_current = EXCLUDED.is_current, is_next = EXCLUDED.is_next,
                        average_entry_score = EXCLUDED.average_entry_score, highest_score = EXCLUDED.highest_score,
                        most_captained = EXCLUDED.most_captained, most_transferred_in = EXCLUDED.most_transferred_in,
                        captured_at = NOW()
                    """.formatted(gameweekTable), gameweeks)) {
                written += Math.max(c, 0);
            }
            deleteAllExcept(gameweekTable, season, ids(gameweeks));
        }
        if (!fixtures.isEmpty()) {
            for (int c : jdbc.batchUpdate("""
                    INSERT INTO %s (season, id, gameweek, team_h, team_a, team_h_name, team_h_short,
                                    team_a_name, team_a_short, team_h_difficulty, team_a_difficulty,
                                    kickoff_time, started, finished, team_h_score, team_a_score)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (season, id) DO UPDATE SET
                        gameweek = EXCLUDED.gameweek, team_h = EXCLUDED.team_h, team_a = EXCLUDED.team_a,
                        team_h_name = EXCLUDED.team_h_name, team_h_short = EXCLUDED.team_h_short,
                        team_a_name = EXCLUDED.team_a_name, team_a_short = EXCLUDED.team_a_short,
                        team_h_difficulty = EXCLUDED.team_h_difficulty, team_a_difficulty = EXCLUDED.team_a_difficulty,
                        kickoff_time = EXCLUDED.kickoff_time, started = EXCLUDED.started, finished = EXCLUDED.finished,
                        team_h_score = EXCLUDED.team_h_score, team_a_score = EXCLUDED.team_a_score,
                        captured_at = NOW()
                    """.formatted(fixtureTable), fixtures)) {
                written += Math.max(c, 0);
            }
            deleteAllExcept(fixtureTable, season, ids(fixtures));
        }
        return written;
    }

    /** Column 1 of every row is the id; see the row layouts in the mapping methods below. */
    private static Integer[] ids(List<Object[]> rows) {
        return rows.stream().map(r -> (Integer) r[1]).toArray(Integer[]::new);
    }

    private void deleteAllExcept(String table, String season, Integer[] keep) {
        jdbc.update("DELETE FROM %s WHERE season = ? AND NOT (id = ANY(?))".formatted(table), ps -> {
            ps.setString(1, season);
            ps.setArray(2, ps.getConnection().createArrayOf("integer", keep));
        });
    }

    static List<Object[]> toGameweekRows(Map<String, Object> bootstrap, String season) {
        List<Object[]> rows = new ArrayList<>();
        if (!(bootstrap.get("events") instanceof List<?> events)) {
            return rows;
        }
        for (Object o : events) {
            if (!(o instanceof Map<?, ?> e)) {
                continue;
            }
            Integer id = asInt(e.get("id"));
            OffsetDateTime deadline = asTime(e.get("deadline_time"));
            if (id == null || deadline == null) {
                continue;   // deadline_time is NOT NULL: a gameweek without one is not a gameweek
            }
            rows.add(new Object[] {
                    season, id, asString(e.get("name")), deadline,
                    Boolean.TRUE.equals(e.get("finished")),
                    Boolean.TRUE.equals(e.get("data_checked")),
                    Boolean.TRUE.equals(e.get("is_current")),
                    Boolean.TRUE.equals(e.get("is_next")),
                    asInt(e.get("average_entry_score")), asInt(e.get("highest_score")),
                    asInt(e.get("most_captained")), asInt(e.get("most_transferred_in"))
            });
        }
        return rows;
    }

    /** id → {name, short_name} from the bootstrap {@code teams} block. */
    static Map<Integer, String[]> teamsOf(Map<String, Object> bootstrap) {
        Map<Integer, String[]> teams = new HashMap<>();
        if (bootstrap.get("teams") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> t) {
                    Integer id = asInt(t.get("id"));
                    if (id != null) {
                        teams.put(id, new String[] {asString(t.get("name")), asString(t.get("short_name"))});
                    }
                }
            }
        }
        return teams;
    }

    static List<Object[]> toFixtureRows(List<Map<String, Object>> fixtures, Map<Integer, String[]> teams,
                                        String season) {
        List<Object[]> rows = new ArrayList<>(fixtures.size());
        for (Map<String, Object> f : fixtures) {
            Integer id = asInt(f.get("id"));
            Integer home = asInt(f.get("team_h"));
            Integer away = asInt(f.get("team_a"));
            if (id == null || home == null || away == null) {
                continue;
            }
            String[] h = teams.getOrDefault(home, new String[2]);
            String[] a = teams.getOrDefault(away, new String[2]);
            rows.add(new Object[] {
                    season, id, asInt(f.get("event")), home, away,
                    h[0], h[1], a[0], a[1],
                    asInt(f.get("team_h_difficulty")), asInt(f.get("team_a_difficulty")),
                    asTime(f.get("kickoff_time")),
                    Boolean.TRUE.equals(f.get("started")), Boolean.TRUE.equals(f.get("finished")),
                    asInt(f.get("team_h_score")), asInt(f.get("team_a_score"))
            });
        }
        return rows;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime asTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
