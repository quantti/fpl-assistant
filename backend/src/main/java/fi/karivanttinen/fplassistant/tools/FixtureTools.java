package fi.karivanttinen.fplassistant.tools;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FixtureTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(FixtureTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final ScheduleSource schedule;
    private final Clock clock;

    public FixtureTools(JdbcTemplate jdbc, SeasonResolver seasonResolver, ScheduleSource schedule,
                        ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.schedule = schedule;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Tool(description = """
            Get upcoming fixtures for a Premier League team by name or short name
            (e.g. "Arsenal", "ARS", "Manchester City", "MCI").
            Returns fixture difficulty rating (FDR 1-5, lower = easier), kickoff time,
            and home/away status. nextN controls how many gameweeks to include (max 10);
            a double gameweek counts as one gameweek with multiple fixtures. Fixtures
            postponed without a rescheduled gameweek are listed separately.
            The reply begins with which source the fixtures come from — FPL's own list
            captured daily when available, otherwise the synced season — and the season.
            """)
    public String getUpcomingFixtures(String teamName, int nextN) {
        try {
            int gwLimit = Math.max(1, Math.min(nextN, 10));
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            Optional<ScheduleSource.Snapshot> captured = schedule.latest();
            if (captured.isPresent()) {
                return fromCapture(captured.get(), teamName, pattern, gwLimit);
            }

            // The Tapas fallback: one instant for every stored-row age in this reply (KAR-9).
            Instant asOf = clock.instant();
            SeasonContext season = seasonResolver.resolve();

            List<Map<String, Object>> teams = jdbc.queryForList("""
                    SELECT id, name, short_name FROM team
                    WHERE season_id = ?
                      AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                    ORDER BY name
                    LIMIT 1
                    """, season.seasonId(), pattern, teamName);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String resolvedName = (String) teams.getFirst().get("name");
            String resolvedShort = (String) teams.getFirst().get("short_name");

            // No gameweek is flagged current between seasons — a bare `>= (SELECT ...)` would
            // compare against NULL and silently drop every row. Fall back to is_next, then GW1.
            // The chosen row comes back with its own write time, for its age line (KAR-9).
            List<Map<String, Object>> start = jdbc.queryForList("""
                    SELECT id, updated_at FROM gameweek
                    WHERE season_id = ? AND id = COALESCE(
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                        (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                    """, season.seasonId(), season.seasonId(), season.seasonId(), season.seasonId());
            Map<String, Object> startRow = start.isEmpty() ? null : start.getFirst();
            Integer startGw = startRow == null ? null : ((Number) startRow.get("id")).intValue();

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT f.gameweek,
                           th.short_name as home_short, ta.short_name as away_short,
                           f.team_h_difficulty, f.team_a_difficulty,
                           f.kickoff_time, f.finished,
                           f.team_h_score, f.team_a_score,
                           f.team_h = ? as is_home, f.updated_at
                    FROM fixture f
                    JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                    JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                    WHERE f.season_id = ?
                      AND (f.team_h = ? OR f.team_a = ?)
                      AND (f.gameweek >= ? OR (f.gameweek IS NULL AND f.finished = false))
                    ORDER BY f.gameweek NULLS LAST, f.kickoff_time
                    """, teamId, season.seasonId(), teamId, teamId, startGw);

            String header = season.currentSeason() ? "Upcoming fixtures" : "Fixtures";
            FixtureWindow window = window(rows, gwLimit);
            return render(season.provenance(), header, resolvedName, resolvedShort, window,
                    storedDataAges(season.code(), startRow, resolvedName,
                            window.scheduled(), window.postponed(), asOf));
        } catch (DataAccessException e) {
            log.error("getUpcomingFixtures(\"{}\", {}) failed", teamName, nextN, e);
            return "Fixture data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * The capture path. The team is resolved from the capture's own denormalised names, the
     * starting gameweek by {@link ScheduleSource#startGameweek} (deadlines against the clock),
     * and the rows carry the same aliases the tapas query produces so {@link #window} and
     * {@link #formatFixture} serve both paths.
     */
    private String fromCapture(ScheduleSource.Snapshot snapshot, String teamName, String pattern, int gwLimit) {
        String provenance = ScheduleSource.provenance(snapshot.season(), snapshot.capturedAt(), Instant.now());
        List<Map<String, Object>> teams = jdbc.queryForList("""
                SELECT team_id, team_name, team_short FROM (
                    SELECT team_h AS team_id, team_h_name AS team_name, team_h_short AS team_short FROM %1$s WHERE season = ?
                    UNION
                    SELECT team_a, team_a_name, team_a_short FROM %1$s WHERE season = ?
                ) t
                WHERE LOWER(team_name) LIKE ? OR LOWER(team_short) = LOWER(?)
                ORDER BY team_name LIMIT 1
                """.formatted(schedule.fixtureTable()),
                snapshot.season(), snapshot.season(), pattern, teamName);
        if (teams.isEmpty()) {
            return provenance + "\nTeam not found: " + teamName;
        }
        int teamId = ((Number) teams.getFirst().get("team_id")).intValue();
        String resolvedName = (String) teams.getFirst().get("team_name");
        String resolvedShort = (String) teams.getFirst().get("team_short");

        Integer startGw = schedule.startGameweek(snapshot.season());
        if (startGw == null) {
            return provenance + "\nNo gameweek data captured for this season.";
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT gameweek, team_h_short AS home_short, team_a_short AS away_short,
                       team_h_difficulty, team_a_difficulty, kickoff_time, finished,
                       team_h_score, team_a_score, team_h = ? AS is_home
                FROM %s
                WHERE season = ? AND (team_h = ? OR team_a = ?)
                  AND (gameweek >= ? OR (gameweek IS NULL AND finished = false))
                ORDER BY gameweek NULLS LAST, kickoff_time
                """.formatted(schedule.fixtureTable()),
                teamId, snapshot.season(), teamId, teamId, startGw);

        FixtureWindow window = window(rows, gwLimit);
        // A finished season's listing is its last round's results, not "upcoming" anything.
        boolean anyUpcoming = window.scheduled().stream()
                .anyMatch(r -> !Boolean.TRUE.equals(r.get("finished")));
        return render(provenance, anyUpcoming ? "Upcoming fixtures" : "Fixtures",
                resolvedName, resolvedShort, window, "");
    }

    /**
     * One reply shape for both paths, so the postponed block and the empty answer cannot drift.
     * {@code ages} is the Tapas path's stored-row age lines, and empty on the capture path.
     */
    private String render(String provenance, String header, String resolvedName,
                          String resolvedShort, FixtureWindow window, String ages) {
        String agesBlock = ages.isEmpty() ? "" : "\n\n" + ages;
        if (window.scheduled().isEmpty() && window.postponed().isEmpty()) {
            return provenance + "\nNo upcoming fixtures found for " + resolvedName + agesBlock;
        }
        StringBuilder out = new StringBuilder(provenance)
                .append("\n\n%s for %s (%s):\n".formatted(header, resolvedName, resolvedShort))
                .append(window.scheduled().stream().map(this::formatFixture)
                        .collect(Collectors.joining("\n")));
        if (!window.postponed().isEmpty()) {
            out.append("\nPostponed, no rescheduled gameweek yet:\n")
                    .append(window.postponed().stream().map(this::formatFixture)
                            .collect(Collectors.joining("\n")));
        }
        return out.append(agesBlock).toString();
    }

    /**
     * The KAR-9 age lines of a Tapas fixture reply, shared with {@link FixtureDifficultyTools}.
     * The gameweek row that chose the starting boundary and the fixture rows actually shown are
     * dated separately, so a fresh fixture cannot hide an old selection flag. With no gameweek
     * row the selection has no age, and an empty listing never borrows the gameweek's.
     */
    static String storedDataAges(String seasonCode, Map<String, Object> startRow, String teamName,
                                 List<Map<String, Object>> scheduled, List<Map<String, Object>> postponed,
                                 Instant asOf) {
        String selection = startRow == null
                ? DataAge.storedDataAge("starting-gameweek selection, season " + seasonCode,
                        "gameweek record", DataAge.Span.of(List.of()), asOf)
                : DataAge.storedDataAge("starting-gameweek selection (gameweek row id=" + startRow.get("id")
                        + "), season " + seasonCode, startRow.get("updated_at"), asOf);
        List<Object> writes = new ArrayList<>();
        scheduled.forEach(r -> writes.add(r.get("updated_at")));
        postponed.forEach(r -> writes.add(r.get("updated_at")));
        String fixtures = DataAge.storedDataAge("fixture records listed for " + teamName + ", season " + seasonCode,
                "fixture record", DataAge.Span.of(writes), asOf);
        return selection + "\n" + fixtures + (startRow == null ? "" : "\n" + DataAge.GAMEWEEK_CAVEAT);
    }

    record FixtureWindow(List<Map<String, Object>> scheduled, List<Map<String, Object>> postponed) {}

    /**
     * Bounds by distinct gameweeks, not fixture rows: a double gameweek is two rows in one
     * round, and "next 5 gameweeks" must always mean five rounds. Postponed fixtures
     * ({@code gameweek IS NULL}) are set aside and never consume the window. Expects rows
     * ordered by gameweek with NULLs last.
     */
    static FixtureWindow window(List<Map<String, Object>> rows, int gwLimit) {
        List<Map<String, Object>> scheduled = new ArrayList<>();
        List<Map<String, Object>> postponed = new ArrayList<>();
        Set<Object> seenGameweeks = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object gw = r.get("gameweek");
            if (gw == null) {
                postponed.add(r);
            } else if (seenGameweeks.contains(gw) || seenGameweeks.size() < gwLimit) {
                seenGameweeks.add(gw);
                scheduled.add(r);
            }
        }
        return new FixtureWindow(scheduled, postponed);
    }

    private String formatFixture(Map<String, Object> r) {
        boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
        String opponent = isHome ? (String) r.get("away_short") : (String) r.get("home_short");
        Object rawFdr = isHome ? r.get("team_h_difficulty") : r.get("team_a_difficulty");
        String fdr = rawFdr == null ? "?" : String.valueOf(((Number) rawFdr).intValue());
        String venue = isHome ? "(H)" : "(A)";
        String score = r.get("team_h_score") != null
                ? " [%s-%s]".formatted(r.get("team_h_score"), r.get("team_a_score"))
                : "";
        String gwLabel = r.get("gameweek") == null ? "Postponed" : "GW" + r.get("gameweek");

        return "%s: vs %s %s | FDR: %s | %s%s".formatted(
                gwLabel, opponent, venue, fdr,
                UkTime.format(r.get("kickoff_time"), "kickoff TBD"), score);
    }
}
