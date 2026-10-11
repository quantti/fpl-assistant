package dev.quantti.fplassistant.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.sync.HistoryParser;
import dev.quantti.fplassistant.sync.IncrementalPfsEvidenceSql;
import dev.quantti.fplassistant.sync.IncrementalPlayerFixturePublisher;
import dev.quantti.fplassistant.sync.OwnedSyncMode;

/**
 * Shared SQL boundary for PFS readers. Legacy fixtures retain their existing receipt contract;
 * incremental rows require a valid current pointer, matching immutable evidence and the dedicated
 * page watermark. Incremental markers prevent unproven physical rows from falling back to legacy.
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
    private final String playerFixtureCurrent;
    private final String pageReceipt;

    @Autowired
    public PfsReadSource(JdbcTemplate jdbc,
                         @Value("${fpl.owned-sync.mode:OFF}") String mode,
                         @Value("${fpl.owned-sync.playing-season:}") String playingSeason,
                         @Value("${fpl.owned-sync.fixture-table-name:fpl_fixture_sync}") String fixtureSync,
                         @Value("${fpl.owned-sync.evidence-table-name:fpl_player_fixture_evidence}") String evidence,
                         @Value("${fpl.owned-sync.current-table-name:fpl_player_fixture_current}") String playerFixtureCurrent,
                         @Value("${fpl.owned-sync.receipt-table-name:fpl_sync_receipt}") String pageReceipt) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mode = OwnedSyncMode.valueOf(mode.trim().toUpperCase());
        this.playingSeason = playingSeason == null ? "" : playingSeason.trim();
        this.fixtureSync = identifier(fixtureSync);
        this.evidence = identifier(evidence);
        this.playerFixtureCurrent = identifier(playerFixtureCurrent);
        this.pageReceipt = identifier(pageReceipt);
    }

    public PfsReadSource(JdbcTemplate jdbc, String mode, String playingSeason,
                         String fixtureSync, String evidence) {
        this(jdbc, mode, playingSeason, fixtureSync, evidence,
                "fpl_player_fixture_current", "fpl_sync_receipt");
    }

    public enum FixtureReceipt {
        LEGACY,
        VERIFIED,
        UNVERIFIED_OWNED,
        INCREMENTAL
    }

    public record IncrementalRows(int incrementalRows, int otherRows) {
        public IncrementalRows {
            if (incrementalRows < 0 || otherRows < 0) {
                throw new IllegalArgumentException("Row counts cannot be negative");
            }
        }

        public int totalRows() {
            return incrementalRows + otherRows;
        }
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
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        Object[] args = new Object[fixtureIds.size() + 1];
        args[0] = seasonId;
        int index = 1;
        for (Integer fixtureId : fixtureIds) {
            args[index++] = fixtureId;
        }
        if (exists(fixtureSync)) {
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
        }
        if (exists(playerFixtureCurrent)) {
            String currentSql = "SELECT DISTINCT fixture_id FROM " + playerFixtureCurrent
                    + " WHERE season_id=? AND fixture_id IN (" + placeholders + ")";
            for (Integer fixtureId : jdbc.queryForList(currentSql, Integer.class, args)) {
                result.put(fixtureId, FixtureReceipt.INCREMENTAL);
            }
        }
        return Map.copyOf(result);
    }

    /** Row evidence in an exact fixture set, grouped once for output finality attribution. */
    public Map<Integer, IncrementalRows> incrementalRowsByGameweek(int seasonId, Set<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(seasonId);
        args.addAll(fixtureIds);
        Map<Integer, IncrementalRows> result = new HashMap<>();
        jdbc.query("WITH " + cte() + " SELECT gameweek,"
                + " count(*) FILTER (WHERE evidence_status='PROVISIONAL') AS incremental_rows,"
                + " count(*) FILTER (WHERE evidence_status<>'PROVISIONAL') AS other_rows"
                + " FROM pfs_read WHERE season_id=? AND fixture_id IN (" + placeholders + ")"
                + " GROUP BY gameweek", rs -> {
                    result.put(rs.getInt("gameweek"), new IncrementalRows(
                            rs.getInt("incremental_rows"), rs.getInt("other_rows")));
                },
                args.toArray());
        return Map.copyOf(result);
    }

    /** Incremental history pages that completed in this season, including pages with no fixture rows. */
    public Set<Integer> incrementalPagePlayers(String seasonCode, Set<Integer> playerIds) {
        if (playerIds.isEmpty() || !exists(pageReceipt)) return Set.of();
        List<String> keys = playerIds.stream()
                .map(IncrementalPlayerFixturePublisher::pageReceiptKey).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(seasonCode);
        args.addAll(keys);
        return Set.copyOf(jdbc.query("""
                SELECT publication_key FROM %s
                WHERE season=? AND publication_key IN (%s)
                  AND latest_generation IS NOT NULL
                  AND source_read_started_at IS NOT NULL
                  AND source_read_finished_at >= source_read_started_at
                """.formatted(pageReceipt, placeholders), (rs, rowNum) ->
                Integer.parseInt(rs.getString("publication_key").substring(
                        IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX.length())), args.toArray()));
    }

    /** Must be called in the caller's transaction when several reads form one snapshot. */
    public String cte() {
        boolean fixtureTable = exists(fixtureSync);
        boolean evidenceTable = exists(evidence);
        boolean currentTable = exists(playerFixtureCurrent);
        boolean receiptTable = exists(pageReceipt);
        if (!fixtureTable) {
            return !evidenceTable && !currentTable && !receiptTable && mode != OwnedSyncMode.ACTIVE
                    ? legacyCte() : emptyCte();
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
        String oldOwned = "(fs.observation_generation IS NOT NULL OR fs.last_verified_generation IS NOT NULL)";
        String currentJoin = currentTable
                ? "LEFT JOIN " + playerFixtureCurrent + " c ON c.season_id=p.season_id"
                    + " AND c.fixture_id=p.fixture_id AND c.player_id=p.player_id"
                : "LEFT JOIN (SELECT NULL::integer AS season_id, NULL::integer AS fixture_id,"
                    + " NULL::integer AS player_id, NULL::varchar AS row_state,"
                    + " NULL::varchar AS season_code, NULL::bigint AS source_run_generation,"
                    + " NULL::bigint AS evidence_generation, NULL::timestamptz AS source_read_started_at,"
                    + " NULL::timestamptz AS source_read_finished_at) c ON FALSE";
        String incrementalEvidenceJoin = currentTable
                ? "LEFT JOIN " + evidence + " ie ON ie.season_id=c.season_id"
                    + " AND ie.season_code=c.season_code AND ie.fixture_id=c.fixture_id"
                    + " AND ie.player_id=c.player_id AND ie.generation=c.evidence_generation"
                : "LEFT JOIN (SELECT NULL::integer AS season_id, NULL::varchar AS season_code,"
                    + " NULL::integer AS fixture_id, NULL::integer AS player_id, NULL::bigint AS generation,"
                    + " NULL::bigint AS component_presence, NULL::timestamptz AS source_read_started_at,"
                    + " NULL::timestamptz AS source_read_finished_at) ie ON FALSE";
        String pageMarker = receiptTable
                ? "EXISTS (SELECT 1 FROM " + pageReceipt + " page_receipt"
                    + " WHERE page_receipt.season=s.code AND page_receipt.publication_key='"
                    + sqlLiteral(IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX)
                    + "' || p.player_id::text AND page_receipt.latest_generation IS NOT NULL"
                    + " AND page_receipt.source_read_started_at IS NOT NULL"
                    + " AND page_receipt.source_read_finished_at >= page_receipt.source_read_started_at)"
                : "FALSE";
        String fixtureMarker = currentTable
                ? "EXISTS (SELECT 1 FROM " + playerFixtureCurrent + " own_fixture"
                    + " WHERE own_fixture.season_id=p.season_id AND own_fixture.fixture_id=p.fixture_id)"
                : "FALSE";
        String currentEvidence = currentTable && receiptTable
                ? IncrementalPfsEvidenceSql.currentRowProof("c", "p", "s", "ie", pageReceipt)
                : "FALSE";
        String legacyVisible = "(c.fixture_id IS NULL AND NOT (" + fixtureMarker + ") AND NOT ("
                + pageMarker + ") AND (((fs.fixture_id IS NULL OR NOT " + oldOwned + ")"
                + " AND NOT (" + activeWithoutReceipt + ")) OR (fs.state='VERIFIED'"
                + " AND fs.current_verified_generation IS NOT NULL"
                + " AND e.generation=fs.current_verified_generation)))";
        String optionalSource = "CASE WHEN " + currentEvidence + " THEN ie.component_presence"
                + " WHEN fs.fixture_id IS NULL OR NOT " + oldOwned + " THEN NULL"
                + " ELSE e.component_presence END";
        String optionalVisible = "(fs.fixture_id IS NOT NULL AND " + oldOwned
                + " AND e.generation=fs.current_verified_generation)";
        String sourceStarted = "CASE WHEN " + currentEvidence + " THEN c.source_read_started_at"
                + " ELSE e.source_read_started_at END";
        String sourceFinished = "CASE WHEN " + currentEvidence + " THEN c.source_read_finished_at"
                + " ELSE e.source_read_finished_at END";
        return """
                pfs_read AS (
                    SELECT p.fixture_id, p.player_id, p.season_id, p.gameweek,
                           p.player_team_id, p.opponent_team_id, p.was_home, p.kickoff_time,
                           p.minutes, p.total_points, p.goals_scored, p.assists,
                           CASE WHEN %s THEN CASE WHEN (%s & %d) <> 0 THEN p.expected_goals END
                                WHEN %s THEN CASE WHEN (e.component_presence & %d) <> 0 THEN p.expected_goals END
                                WHEN %s THEN p.expected_goals END AS expected_goals,
                           CASE WHEN %s THEN CASE WHEN (%s & %d) <> 0 THEN p.expected_assists END
                                WHEN %s THEN CASE WHEN (e.component_presence & %d) <> 0 THEN p.expected_assists END
                                WHEN %s THEN p.expected_assists END AS expected_assists,
                           CASE WHEN %s THEN CASE WHEN (%s & %d) <> 0 THEN p.expected_goal_involvements END
                                WHEN %s THEN CASE WHEN (e.component_presence & %d) <> 0 THEN p.expected_goal_involvements END
                                WHEN %s THEN p.expected_goal_involvements END AS expected_goal_involvements,
                           p.goals_conceded,
                           CASE WHEN %s THEN CASE WHEN (%s & %d) <> 0 THEN p.expected_goals_conceded END
                                WHEN %s THEN CASE WHEN (e.component_presence & %d) <> 0 THEN p.expected_goals_conceded END
                                WHEN %s THEN p.expected_goals_conceded END AS expected_goals_conceded,
                           CASE WHEN %s THEN CASE WHEN (%s & %d) <> 0 THEN p.defensive_contribution END
                                WHEN %s THEN CASE WHEN (e.component_presence & %d) <> 0 THEN p.defensive_contribution END
                                WHEN %s THEN p.defensive_contribution END AS defensive_contribution,
                           %s AS source_read_started_at, %s AS source_read_finished_at,
                           CASE WHEN %s THEN NULL ELSE fs.last_verified_at END AS verified_published_at,
                           (%s OR (%s AND e.generation=fs.current_verified_generation)) AS owned_evidence,
                           CASE WHEN %s THEN 'PROVISIONAL'
                                WHEN fs.state='VERIFIED' AND fs.current_verified_generation IS NOT NULL THEN 'VERIFIED'
                                ELSE 'LEGACY' END AS evidence_status
                    FROM player_fixture_stats p
                    LEFT JOIN %s fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id
                    LEFT JOIN %s e ON e.season_id=p.season_id AND e.fixture_id=p.fixture_id
                         AND e.player_id=p.player_id AND e.generation=fs.current_verified_generation
                    %s
                    %s
                    LEFT JOIN season s ON s.id=p.season_id
                    WHERE (%s OR %s)
                )
                """.formatted(
                currentEvidence, optionalSource, xg, optionalVisible, xg, legacyVisible,
                currentEvidence, optionalSource, xa, optionalVisible, xa, legacyVisible,
                currentEvidence, optionalSource, xgi, optionalVisible, xgi, legacyVisible,
                currentEvidence, optionalSource, xgc, optionalVisible, xgc, legacyVisible,
                currentEvidence, optionalSource, dc, optionalVisible, dc, legacyVisible,
                sourceStarted, sourceFinished, currentEvidence,
                currentEvidence, oldOwned, currentEvidence, fixtureSync, evidence,
                currentJoin, incrementalEvidenceJoin, currentEvidence, legacyVisible);
    }

    private String noIncrementalOwnerPredicate(String pfsAlias, String seasonAlias) {
        java.util.List<String> predicates = new java.util.ArrayList<>();
        if (exists(playerFixtureCurrent)) {
            predicates.add("NOT EXISTS (SELECT 1 FROM " + playerFixtureCurrent + " own_fixture"
                    + " WHERE own_fixture.season_id=" + pfsAlias + ".season_id"
                    + " AND own_fixture.fixture_id=" + pfsAlias + ".fixture_id)");
        }
        if (exists(pageReceipt)) {
            predicates.add("NOT EXISTS (SELECT 1 FROM " + pageReceipt + " page_receipt"
                    + " WHERE page_receipt.season=" + seasonAlias + ".code"
                    + " AND page_receipt.publication_key='"
                    + sqlLiteral(IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX) + "' || "
                    + pfsAlias + ".player_id::text AND page_receipt.latest_generation IS NOT NULL"
                    + " AND page_receipt.source_read_started_at IS NOT NULL"
                    + " AND page_receipt.source_read_finished_at >= page_receipt.source_read_started_at)");
        }
        return predicates.isEmpty() ? "TRUE" : String.join(" AND ", predicates);
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private String receiptOnlyCte() {
        String activeWithoutReceipt = mode != OwnedSyncMode.ACTIVE ? "FALSE"
                : playingSeason.isEmpty() ? "s.is_current=TRUE"
                : "s.code='" + sqlLiteral(playingSeason) + "'";
        String noIncrementalOwner = noIncrementalOwnerPredicate("p", "s");
        return """
                pfs_read AS (
                    SELECT p.fixture_id, p.player_id, p.season_id, p.gameweek, p.player_team_id,
                           p.opponent_team_id, p.was_home, p.kickoff_time, p.minutes, p.total_points,
                           p.goals_scored, p.assists, p.expected_goals, p.expected_assists,
                           p.expected_goal_involvements, p.goals_conceded, p.expected_goals_conceded,
                           p.defensive_contribution, NULL::timestamptz AS source_read_started_at,
                           NULL::timestamptz AS source_read_finished_at,
                           NULL::timestamptz AS verified_published_at, FALSE AS owned_evidence,
                           'LEGACY'::text AS evidence_status
                    FROM player_fixture_stats p
                    LEFT JOIN %s fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id
                    LEFT JOIN season s ON s.id=p.season_id
                    WHERE (fs.fixture_id IS NULL OR (fs.observation_generation IS NULL
                               AND fs.last_verified_generation IS NULL)) AND NOT (%s)
                      AND (%s)
                )
                """.formatted(fixtureSync, activeWithoutReceipt, noIncrementalOwner);
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
                           FALSE AS owned_evidence, 'LEGACY'::text AS evidence_status
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
