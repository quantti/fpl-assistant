package dev.quantti.fplassistant.myteam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.quantti.fplassistant.tools.DataAge;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What Supabase knows about a squad that FPL's own payload does not: the fixture run ahead and
 * the expected goals and assists behind.
 *
 * <p><b>The season gate is a correctness rule, not a nicety.</b> An {@code element} id is
 * season-scoped and FPL renumbers every player every season, so joining this season's pick ids
 * against a season the database has not replaced yet returns <i>different players'</i> numbers,
 * with nothing to show that it did. The enrichment therefore runs only while the resolved season
 * is the one the database flags current; otherwise the card is served from FPL's own payload and
 * says the deeper numbers are not available for the new season yet. The reason the resolver
 * falls back at all is documented on {@link SeasonResolver} — that fallback is right for a stats
 * question and wrong for someone's live squad.
 */
@Component
public class SquadEnrichment {

    /** How many upcoming fixtures the difficulty average covers. */
    static final int FIXTURE_RUN = 5;

    /**
     * @param playerWrite the recorded write time of the one player row behind xG/xA (KAR-9)
     * @param fdrWrites the write times of exactly the fixtures admitted to {@code nextFiveFdr};
     *        null when there is no FDR
     */
    public record Depth(Double nextFiveFdr, BigDecimal expectedGoals, BigDecimal expectedAssists,
                        DataAge.Span playerWrite, DataAge.Span fdrWrites) {
    }

    /**
     * The gameweek row that chose where every club's FDR run starts, with its own write time
     * (KAR-9). {@code id} is null and {@code write} empty when the season has no gameweek row.
     */
    public record StartingGameweek(Integer id, DataAge.Span write) {
    }

    /**
     * @param available whether the database could be used at all — false across the season
     *        changeover, when its ids belong to another season
     * @param startingGameweek null when no FDR run was looked up: the gate refused, or no squad
     *        player had a club
     */
    public record Enrichment(boolean available, SeasonContext season, Map<Integer, Depth> byElement,
                             StartingGameweek startingGameweek) {
        public Depth of(int elementId) {
            return byElement.get(elementId);
        }
    }

    /** A club's FDR average and the write times of exactly the fixtures that went into it. */
    private record Run(double average, DataAge.Span writes) {
    }

    private record Admitted(int difficulty, Object writtenAt) {
    }

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public SquadEnrichment(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    public Enrichment forElements(Set<Integer> elementIds) {
        SeasonContext season = seasonResolver.resolve();
        if (!season.currentSeason() || elementIds.isEmpty()) {
            return new Enrichment(false, season, Map.of(), null);
        }

        Map<Integer, Integer> teamByElement = new LinkedHashMap<>();
        Map<Integer, Depth> depth = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(elementIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(season.seasonId());
        args.addAll(elementIds);

        List<Map<String, Object>> players = jdbc.queryForList(
                "SELECT id, team_id, expected_goals, expected_assists, updated_at FROM player"
                        + " WHERE season_id = ? AND id IN (" + placeholders + ")", args.toArray());
        if (players.isEmpty()) {
            // The season is flagged current but its players have not landed yet: the same empty
            // window SeasonResolver exists for, seen from the other side.
            return new Enrichment(false, season, Map.of(), null);
        }
        for (Map<String, Object> player : players) {
            int id = ((Number) player.get("id")).intValue();
            Integer teamId = player.get("team_id") == null
                    ? null : ((Number) player.get("team_id")).intValue();
            if (teamId != null) {
                teamByElement.put(id, teamId);
            }
            depth.put(id, new Depth(null, (BigDecimal) player.get("expected_goals"),
                    (BigDecimal) player.get("expected_assists"),
                    DataAge.Span.of(Collections.singletonList(player.get("updated_at"))), null));
        }

        Set<Integer> clubs = Set.copyOf(teamByElement.values());
        StartingGameweek start = clubs.isEmpty() ? null : startingGameweek(season);
        Map<Integer, Run> runs = start == null || start.id() == null
                ? Map.of() : fixtureRuns(season, start.id(), clubs);
        for (Map.Entry<Integer, Integer> entry : teamByElement.entrySet()) {
            Depth current = depth.get(entry.getKey());
            Run run = runs.get(entry.getValue());
            if (run != null) {
                depth.put(entry.getKey(), new Depth(run.average(), current.expectedGoals(),
                        current.expectedAssists(), current.playerWrite(), run.writes()));
            }
        }
        return new Enrichment(true, season, Map.copyOf(depth), start);
    }

    /** The current gameweek row, else the next, else the season's first — with its write time. */
    private StartingGameweek startingGameweek(SeasonContext season) {
        List<Map<String, Object>> start = jdbc.queryForList("""
                SELECT id, updated_at FROM gameweek
                WHERE season_id = ? AND id = COALESCE(
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                    (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                """, season.seasonId(), season.seasonId(), season.seasonId(), season.seasonId());
        if (start.isEmpty()) {
            return new StartingGameweek(null, DataAge.Span.of(List.of()));
        }
        Map<String, Object> row = start.getFirst();
        return new StartingGameweek(((Number) row.get("id")).intValue(),
                DataAge.Span.of(Collections.singletonList(row.get("updated_at"))));
    }

    /** Average difficulty over each team's next {@value #FIXTURE_RUN} rated fixtures. */
    private Map<Integer, Run> fixtureRuns(SeasonContext season, int startGw, Set<Integer> teamIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(teamIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(season.seasonId());
        args.add(startGw);
        args.addAll(teamIds);
        args.addAll(teamIds);
        List<Map<String, Object>> fixtures = jdbc.queryForList(
                "SELECT gameweek, team_h, team_a, team_h_difficulty, team_a_difficulty, updated_at"
                        + " FROM fixture"
                        + " WHERE season_id = ? AND gameweek IS NOT NULL AND gameweek >= ?"
                        + " AND (team_h IN (" + placeholders + ") OR team_a IN (" + placeholders + "))"
                        + " ORDER BY gameweek", args.toArray());

        Map<Integer, List<Admitted>> admitted = new HashMap<>();
        for (Map<String, Object> fixture : fixtures) {
            addDifficulty(admitted, teamIds, fixture, "team_h", "team_h_difficulty");
            addDifficulty(admitted, teamIds, fixture, "team_a", "team_a_difficulty");
        }
        // The age covers the same admitted fixtures as the average, never every row fetched.
        Map<Integer, Run> runs = new HashMap<>();
        admitted.forEach((teamId, run) -> runs.put(teamId, new Run(
                run.stream().mapToInt(Admitted::difficulty).average().orElse(0),
                DataAge.Span.of(run.stream().map(Admitted::writtenAt).toList()))));
        return runs;
    }

    private static void addDifficulty(Map<Integer, List<Admitted>> runs, Set<Integer> teamIds,
                                      Map<String, Object> fixture, String teamColumn,
                                      String difficultyColumn) {
        Object team = fixture.get(teamColumn);
        Object difficulty = fixture.get(difficultyColumn);
        if (!(team instanceof Number teamNumber) || !(difficulty instanceof Number)) {
            return;
        }
        int teamId = teamNumber.intValue();
        if (!teamIds.contains(teamId)) {
            return;
        }
        List<Admitted> run = runs.computeIfAbsent(teamId, ignored -> new ArrayList<>());
        if (run.size() < FIXTURE_RUN) {
            run.add(new Admitted(((Number) difficulty).intValue(), fixture.get("updated_at")));
        }
    }
}
