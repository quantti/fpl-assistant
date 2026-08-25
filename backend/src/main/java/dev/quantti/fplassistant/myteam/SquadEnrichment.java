package dev.quantti.fplassistant.myteam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public record Depth(Double nextFiveFdr, BigDecimal expectedGoals, BigDecimal expectedAssists) {
    }

    /**
     * @param available whether the database could be used at all — false across the season
     *        changeover, when its ids belong to another season
     */
    public record Enrichment(boolean available, SeasonContext season, Map<Integer, Depth> byElement) {
        public Depth of(int elementId) {
            return byElement.get(elementId);
        }
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
            return new Enrichment(false, season, Map.of());
        }

        Map<Integer, Integer> teamByElement = new LinkedHashMap<>();
        Map<Integer, Depth> depth = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(elementIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(season.seasonId());
        args.addAll(elementIds);

        List<Map<String, Object>> players = jdbc.queryForList(
                "SELECT id, team_id, expected_goals, expected_assists FROM player"
                        + " WHERE season_id = ? AND id IN (" + placeholders + ")", args.toArray());
        if (players.isEmpty()) {
            // The season is flagged current but its players have not landed yet: the same empty
            // window SeasonResolver exists for, seen from the other side.
            return new Enrichment(false, season, Map.of());
        }
        for (Map<String, Object> player : players) {
            int id = ((Number) player.get("id")).intValue();
            Integer teamId = player.get("team_id") == null
                    ? null : ((Number) player.get("team_id")).intValue();
            if (teamId != null) {
                teamByElement.put(id, teamId);
            }
            depth.put(id, new Depth(null, (BigDecimal) player.get("expected_goals"),
                    (BigDecimal) player.get("expected_assists")));
        }

        Map<Integer, Double> fdr = fixtureDifficulty(season, Set.copyOf(teamByElement.values()));
        for (Map.Entry<Integer, Integer> entry : teamByElement.entrySet()) {
            Depth current = depth.get(entry.getKey());
            depth.put(entry.getKey(), new Depth(fdr.get(entry.getValue()),
                    current.expectedGoals(), current.expectedAssists()));
        }
        return new Enrichment(true, season, Map.copyOf(depth));
    }

    /** Average difficulty over each team's next {@value #FIXTURE_RUN} scheduled fixtures. */
    private Map<Integer, Double> fixtureDifficulty(SeasonContext season, Set<Integer> teamIds) {
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        Integer startGw = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                    (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                """, Integer.class, season.seasonId(), season.seasonId(), season.seasonId());
        if (startGw == null) {
            return Map.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(teamIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(season.seasonId());
        args.add(startGw);
        args.addAll(teamIds);
        args.addAll(teamIds);
        List<Map<String, Object>> fixtures = jdbc.queryForList(
                "SELECT gameweek, team_h, team_a, team_h_difficulty, team_a_difficulty FROM fixture"
                        + " WHERE season_id = ? AND gameweek IS NOT NULL AND gameweek >= ?"
                        + " AND (team_h IN (" + placeholders + ") OR team_a IN (" + placeholders + "))"
                        + " ORDER BY gameweek", args.toArray());

        Map<Integer, List<Integer>> runs = new HashMap<>();
        for (Map<String, Object> fixture : fixtures) {
            addDifficulty(runs, teamIds, fixture, "team_h", "team_h_difficulty");
            addDifficulty(runs, teamIds, fixture, "team_a", "team_a_difficulty");
        }
        Map<Integer, Double> averages = new HashMap<>();
        runs.forEach((teamId, difficulties) -> averages.put(teamId,
                difficulties.stream().mapToInt(Integer::intValue).average().orElse(0)));
        return averages;
    }

    private static void addDifficulty(Map<Integer, List<Integer>> runs, Set<Integer> teamIds,
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
        List<Integer> run = runs.computeIfAbsent(teamId, ignored -> new ArrayList<>());
        if (run.size() < FIXTURE_RUN) {
            run.add(((Number) difficulty).intValue());
        }
    }
}
