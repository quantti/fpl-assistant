package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fi.karivanttinen.fplassistant.sync.HistoryParser;
import fi.karivanttinen.fplassistant.sync.OwnedSyncMode;

/**
 * Shared SQL boundary for PFS readers. Legacy fixtures remain readable, while a fixture that has
 * ever entered the owned publication protocol is visible only at its current verified generation.
 * Optional values are masked before any caller can aggregate retained physical values/defaults.
 */
@Component
public final class PfsReadSource {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    private final JdbcTemplate jdbc;
    private final OwnedSyncMode mode;
    private final String playingSeason;
    private final String fixtureSync;
    private final String evidence;

    public PfsReadSource(JdbcTemplate jdbc,
                         @Value("${fpl.owned-sync.mode:OFF}") String mode,
                         @Value("${fpl.owned-sync.playing-season:}") String playingSeason,
                         @Value("${fpl.owned-sync.fixture-table-name:fpl_fixture_sync}") String fixtureSync,
                         @Value("${fpl.owned-sync.evidence-table-name:fpl_player_fixture_evidence}") String evidence) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mode = OwnedSyncMode.valueOf(mode.trim().toUpperCase());
        this.playingSeason = playingSeason == null ? "" : playingSeason.trim();
        this.fixtureSync = identifier(fixtureSync);
        this.evidence = identifier(evidence);
    }

    public enum FixtureReceipt {
        LEGACY,
        VERIFIED,
        UNVERIFIED_OWNED
    }

    /**
     * Receipt state for a bulk fixture population. This must be called inside the ranking
     * transaction: it is coverage metadata for the same PFS rows exposed by {@link #cte()}.
     */
    public Map<Integer, FixtureReceipt> fixtureReceipts(int seasonId, String seasonCode,
                                                        Set<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) {
            return Map.of();
        }
        boolean activeSeason = mode == OwnedSyncMode.ACTIVE && (playingSeason.isEmpty()
                ? Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT is_current FROM season WHERE id = ? AND code = ?",
                        Boolean.class, seasonId, seasonCode))
                : playingSeason.equals(seasonCode));
        FixtureReceipt defaultState = activeSeason
                ? FixtureReceipt.UNVERIFIED_OWNED : FixtureReceipt.LEGACY;
        Map<Integer, FixtureReceipt> result = new HashMap<>();
        fixtureIds.forEach(id -> result.put(id, defaultState));
        if (!exists(fixtureSync)) {
            return Map.copyOf(result);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        Object[] args = new Object[fixtureIds.size() + 1];
        args[0] = seasonId;
        int index = 1;
        for (Integer fixtureId : fixtureIds) {
            args[index++] = fixtureId;
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT fixture_id, state,"
                + " observation_generation, current_verified_generation, last_verified_generation"
                + " FROM " + fixtureSync + " WHERE season_id = ? AND fixture_id IN ("
                + placeholders + ")", args)) {
            int fixtureId = ((Number) row.get("fixture_id")).intValue();
            boolean enteredProtocol = row.get("observation_generation") != null
                    || row.get("last_verified_generation") != null;
            if (!enteredProtocol && defaultState == FixtureReceipt.LEGACY) {
                result.put(fixtureId, FixtureReceipt.LEGACY);
            } else if ("VERIFIED".equals(row.get("state"))
                    && row.get("current_verified_generation") != null) {
                result.put(fixtureId, FixtureReceipt.VERIFIED);
            } else {
                result.put(fixtureId, FixtureReceipt.UNVERIFIED_OWNED);
            }
        }
        return Map.copyOf(result);
    }

    /** Must be called in the caller's transaction when several reads form one snapshot. */
    public String cte() {
        boolean fixtureTable = exists(fixtureSync);
        boolean evidenceTable = exists(evidence);
        if (!fixtureTable) {
            return !evidenceTable && mode != OwnedSyncMode.ACTIVE ? legacyCte() : emptyCte();
        }
        if (!evidenceTable) {
            return receiptOnlyCte();
        }
        long xg = HistoryParser.Component.EXPECTED_GOALS.bit();
        long xa = HistoryParser.Component.EXPECTED_ASSISTS.bit();
        long xgi = HistoryParser.Component.EXPECTED_GOAL_INVOLVEMENTS.bit();
        long xgc = HistoryParser.Component.EXPECTED_GOALS_CONCEDED.bit();
        long dc = HistoryParser.Component.DEFENSIVE_CONTRIBUTION.bit();
        String activeWithoutReceipt = mode != OwnedSyncMode.ACTIVE ? "FALSE"
                : playingSeason.isEmpty() ? "s.is_current=TRUE"
                : "s.code='" + sqlLiteral(playingSeason) + "'";
        return """
                pfs_read AS (
                    SELECT p.fixture_id, p.player_id, p.season_id, p.gameweek,
                           p.player_team_id, p.opponent_team_id, p.was_home, p.kickoff_time,
                           p.minutes, p.total_points, p.goals_scored, p.assists,
                           CASE WHEN fs.fixture_id IS NULL OR (e.component_presence & %d) <> 0 THEN p.expected_goals END AS expected_goals,
                           CASE WHEN fs.fixture_id IS NULL OR (e.component_presence & %d) <> 0 THEN p.expected_assists END AS expected_assists,
                           CASE WHEN fs.fixture_id IS NULL OR (e.component_presence & %d) <> 0 THEN p.expected_goal_involvements END AS expected_goal_involvements,
                           p.goals_conceded,
                           CASE WHEN fs.fixture_id IS NULL OR (e.component_presence & %d) <> 0 THEN p.expected_goals_conceded END AS expected_goals_conceded,
                           CASE WHEN fs.fixture_id IS NULL OR (e.component_presence & %d) <> 0 THEN p.defensive_contribution END AS defensive_contribution,
                           e.source_read_started_at, e.source_read_finished_at,
                           fs.last_verified_at AS verified_published_at,
                           (fs.observation_generation IS NOT NULL
                               OR fs.last_verified_generation IS NOT NULL) AS owned_evidence
                    FROM player_fixture_stats p
                    LEFT JOIN %s fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id
                    LEFT JOIN %s e ON e.season_id=p.season_id AND e.fixture_id=p.fixture_id
                         AND e.player_id=p.player_id AND e.generation=fs.current_verified_generation
                    LEFT JOIN season s ON s.id=p.season_id
                    WHERE ((fs.fixture_id IS NULL OR (fs.observation_generation IS NULL
                                AND fs.last_verified_generation IS NULL)) AND NOT (%s))
                       OR (fs.state='VERIFIED' AND fs.current_verified_generation IS NOT NULL
                           AND e.generation=fs.current_verified_generation)
                )
                """.formatted(xg, xa, xgi, xgc, dc, fixtureSync, evidence, activeWithoutReceipt);
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private String receiptOnlyCte() {
        String activeWithoutReceipt = mode != OwnedSyncMode.ACTIVE ? "FALSE"
                : playingSeason.isEmpty() ? "s.is_current=TRUE"
                : "s.code='" + sqlLiteral(playingSeason) + "'";
        return """
                pfs_read AS (
                    SELECT p.fixture_id, p.player_id, p.season_id, p.gameweek, p.player_team_id,
                           p.opponent_team_id, p.was_home, p.kickoff_time, p.minutes, p.total_points,
                           p.goals_scored, p.assists, p.expected_goals, p.expected_assists,
                           p.expected_goal_involvements, p.goals_conceded, p.expected_goals_conceded,
                           p.defensive_contribution, NULL::timestamptz AS source_read_started_at,
                           NULL::timestamptz AS source_read_finished_at,
                           NULL::timestamptz AS verified_published_at, FALSE AS owned_evidence
                    FROM player_fixture_stats p
                    LEFT JOIN %s fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id
                    LEFT JOIN season s ON s.id=p.season_id
                    WHERE (fs.fixture_id IS NULL OR (fs.observation_generation IS NULL
                               AND fs.last_verified_generation IS NULL)) AND NOT (%s)
                )
                """.formatted(fixtureSync, activeWithoutReceipt);
    }

    private static String legacyCte() {
        return """
                pfs_read AS (
                    SELECT fixture_id, player_id, season_id, gameweek, player_team_id,
                           opponent_team_id, was_home, kickoff_time, minutes, total_points,
                           goals_scored, assists, expected_goals, expected_assists,
                           expected_goal_involvements, goals_conceded, expected_goals_conceded,
                           defensive_contribution, NULL::timestamptz AS source_read_started_at,
                           NULL::timestamptz AS source_read_finished_at,
                           NULL::timestamptz AS verified_published_at,
                           FALSE AS owned_evidence
                    FROM player_fixture_stats
                )
                """;
    }

    private static String emptyCte() {
        return legacyCte().replace("FROM player_fixture_stats\n", "FROM player_fixture_stats WHERE FALSE\n");
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid owned evidence table name: " + value);
        }
        return value;
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
