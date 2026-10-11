package dev.quantti.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
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

    public record FixtureSide(int fixtureId, int clubId) {
    }

    /** Receipt-backed complete-positive-population proof for one fixture side. */
    public record SidePopulation(FixtureReceipt fixtureReceipt, boolean sourceRosterKnown,
                                 boolean sourceRosterComplete, int expectedPositive,
                                 int matchedPositive, int unexpectedPositive) {
        public SidePopulation {
            Objects.requireNonNull(fixtureReceipt);
            if (expectedPositive < 0 || matchedPositive < 0 || unexpectedPositive < 0
                    || matchedPositive > expectedPositive) {
                throw new IllegalArgumentException("Invalid fixture-side population counts");
            }
        }

        public boolean incremental() {
            return fixtureReceipt == FixtureReceipt.INCREMENTAL || sourceRosterKnown;
        }

        public boolean complete() {
            return sourceRosterComplete && expectedPositive > 0
                    && matchedPositive == expectedPositive && unexpectedPositive == 0;
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

    /**
     * Proves fixture-side positive-appearance coverage in one set-based read. It reads only the
     * exact live-event generation and source fixture identity represented by the durable receipts;
     * zero-minute rows are intentionally outside the mandatory participant population.
     */
    public Map<FixtureSide, SidePopulation> fixtureSidePopulation(
            int seasonId, String seasonCode, String sourceTable, String sourceSeasonColumn,
            Object sourceSeasonValue, Set<Integer> fixtureIds, Instant asOf) {
        if (fixtureIds.isEmpty()) return Map.of();
        String fixtures = identifier(sourceTable);
        String seasonColumn = identifier(sourceSeasonColumn);
        boolean hasCode = hasColumn(fixtures, "code");
        boolean hasProvisional = hasColumn(fixtures, "finished_provisional");
        boolean hasMinutes = hasColumn(fixtures, "minutes");
        Map<Integer, FixtureReceipt> receipts = fixtureReceipts(seasonId, seasonCode, fixtureIds);
        if (!exists(pageReceipt)) {
            return unprovenFixtureSides(fixtures, seasonColumn, sourceSeasonValue, fixtureIds, receipts);
        }
        List<Integer> orderedFixtures = fixtureIds.stream().sorted().toList();
        List<Object> args = new java.util.ArrayList<>();
        List<String> values = new java.util.ArrayList<>();
        for (int fixtureId : orderedFixtures) {
            values.add("(?)");
            args.add(fixtureId);
        }
        String playerPrefix = dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt.PLAYER_PREFIX;
        String snapshotPrefix = dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt.SNAPSHOT_PREFIX;
        String appearanceStatePrefix =
                dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt.APPEARANCE_STATE_PREFIX;
        String liveEventSourcePrefix =
                dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt.LIVE_EVENT_SOURCE_PREFIX;
        String sql = """
                WITH %s, requested_fixture(fixture_id) AS (VALUES %s),
                source_fixture AS (
                    SELECT f.id AS fixture_id, %s AS code, f.gameweek, f.team_h, f.team_a,
                           f.kickoff_time, f.started, f.finished, %s AS finished_provisional, %s AS minutes,
                           f.team_h_score, f.team_a_score
                    FROM %s f JOIN requested_fixture r ON r.fixture_id=f.id
                    WHERE f.%s=?
                ), source_revisions AS (
                    SELECT split_part(r.publication_key, ':', 2)::integer AS fixture_id,
                           r.latest_generation, r.source_read_started_at, r.source_read_finished_at,
                           r.source_pair_fingerprint
                    FROM %s r
                    WHERE r.season=? AND r.publication_key LIKE '%s%%'
                      AND r.publication_key ~ '^incremental-source-fixture:[0-9]+$'
                      AND r.latest_generation IS NOT NULL
                      AND r.source_read_started_at IS NOT NULL
                      AND r.source_read_finished_at>=r.source_read_started_at
                      AND r.source_read_finished_at<=?
                ), appearance_state AS (
                    SELECT split_part(r.publication_key, ':', 3)::integer AS fixture_id,
                           r.latest_generation, r.source_read_started_at, r.source_read_finished_at,
                           r.source_pair_fingerprint
                    FROM %s r
                    WHERE r.season=? AND r.publication_key LIKE '%s%%'
                      AND r.publication_key ~ '^incremental-live-appearance-state:fixture:[0-9]+$'
                      AND r.latest_generation IS NOT NULL
                      AND r.source_read_started_at IS NOT NULL
                      AND r.source_read_finished_at>=r.source_read_started_at
                      AND r.source_pair_fingerprint ~ '^[0-9a-f]{64}$'
                      AND r.source_read_finished_at<=?
                ), snapshot_fixtures AS (
                    SELECT DISTINCT split_part(r.publication_key, ':', 3)::integer AS fixture_id
                    FROM %s r
                    WHERE r.season=? AND r.publication_key LIKE '%s%%'
                      AND split_part(r.publication_key, ':', 3) ~ '^[0-9]+$'
                ), snapshots AS (
                    SELECT r.season, r.latest_generation, r.source_read_started_at,
                           r.source_read_finished_at, r.source_pair_fingerprint,
                           split_part(r.publication_key, ':', 3)::integer AS fixture_id,
                           split_part(r.publication_key, ':', 5)::integer AS event_id,
                           split_part(r.publication_key, ':', 7)::integer AS home_id,
                           split_part(r.publication_key, ':', 9)::integer AS away_id,
                           split_part(r.publication_key, ':', 11) AS gameweek_key,
                           split_part(r.publication_key, ':', 13) AS kickoff_key,
                           split_part(r.publication_key, ':', 15) AS finished_key,
                           split_part(r.publication_key, ':', 17)='1' AS source_complete,
                           split_part(r.publication_key, ':', 19)::integer AS expected_appearance_rows,
                           split_part(r.publication_key, ':', 21)::integer AS fixture_code,
                           split_part(r.publication_key, ':', 23)='1' AS started,
                           split_part(r.publication_key, ':', 25)='1' AS provisional,
                           split_part(r.publication_key, ':', 27)::integer AS fixture_minutes,
                           split_part(r.publication_key, ':', 29) AS score_home,
                           split_part(r.publication_key, ':', 31) AS score_away
                    FROM %s r
                    WHERE r.season=? AND r.publication_key LIKE '%s%%'
                      AND r.publication_key ~ '^incremental-live-appearance:fixture:[0-9]+:e:[0-9]+:h:[0-9]+:a:[0-9]+:g:([0-9]+|n):k:(-?[0-9]+|n):f:[01]:c:[01]:p:[0-9]+:cd:-?[0-9]+:st:[01]:pr:[01]:m:[0-9]+:sh:(-?[0-9]+|n):sa:(-?[0-9]+|n):r:-?[0-9]+:-?[0-9]+$'
                      AND r.latest_generation IS NOT NULL
                      AND r.source_read_started_at IS NOT NULL
                      AND r.source_read_finished_at>=r.source_read_started_at
                ), appearance_rows AS (
                    SELECT r.season, r.latest_generation, r.source_read_started_at,
                           r.source_read_finished_at,
                           split_part(r.publication_key, ':', 3)::integer AS fixture_id,
                           split_part(r.publication_key, ':', 5)::integer AS player_id,
                           split_part(r.publication_key, ':', 7) AS side,
                           split_part(r.publication_key, ':', 9)::integer AS minutes
                    FROM %s r JOIN requested_fixture f
                      ON f.fixture_id=split_part(r.publication_key, ':', 3)::integer
                    WHERE r.season=? AND r.publication_key LIKE '%s%%'
                      AND r.publication_key ~ '^incremental-live-player:fixture:[0-9]+:player:[0-9]+:side:(HOME|AWAY):minutes:[0-9]+:readstart:-?[0-9]+:readfinish:-?[0-9]+$'
                      AND r.latest_generation IS NOT NULL
                      AND r.source_read_started_at IS NOT NULL
                      AND r.source_read_finished_at>=r.source_read_started_at
                ), appearance_digest AS (
                    SELECT a.fixture_id, a.latest_generation, a.source_read_started_at,
                           a.source_read_finished_at, count(*) AS appearance_rows,
                           encode(sha256(convert_to(string_agg(
                               a.player_id::text || ':' || a.side || ':' || a.minutes::text,
                               '|' ORDER BY a.player_id), 'UTF8')), 'hex') AS fingerprint
                    FROM appearance_rows a JOIN snapshots s
                      ON s.season=a.season AND s.fixture_id=a.fixture_id
                     AND s.latest_generation=a.latest_generation
                     AND s.source_read_started_at=a.source_read_started_at
                     AND s.source_read_finished_at=a.source_read_finished_at
                    GROUP BY a.fixture_id, a.latest_generation,
                             a.source_read_started_at, a.source_read_finished_at
                ), source_snapshots AS (
                    SELECT s.*, f.team_h, f.team_a,
                           current_state.source_read_finished_at AS appearance_floor,
                           (COALESCE(d.appearance_rows, 0)=s.expected_appearance_rows
                            AND s.source_pair_fingerprint=encode(sha256(convert_to(
                                sr.source_pair_fingerprint || ':' || COALESCE(d.fingerprint,
                                    encode(sha256(convert_to('', 'UTF8')), 'hex')), 'UTF8')), 'hex'))
                               AS digest_valid
                    FROM snapshots s
                    JOIN source_fixture f ON f.fixture_id=s.fixture_id
                    JOIN source_revisions sr ON sr.fixture_id=s.fixture_id
                    JOIN appearance_state current_state ON current_state.fixture_id=s.fixture_id
                      AND current_state.source_pair_fingerprint=s.source_pair_fingerprint
                      AND s.source_read_finished_at>=current_state.source_read_finished_at
                    JOIN %s event_source_receipt ON event_source_receipt.season=s.season
                      AND event_source_receipt.publication_key='%s' || s.event_id::text
                          || ':generation:' || s.latest_generation::text
                          || ':started:' || round(extract(epoch FROM s.source_read_started_at)*1000000)::bigint::text
                          || ':finished:' || round(extract(epoch FROM s.source_read_finished_at)*1000000)::bigint::text
                      AND event_source_receipt.latest_generation=s.latest_generation
                      AND event_source_receipt.source_read_started_at=s.source_read_started_at
                      AND event_source_receipt.source_read_finished_at=s.source_read_finished_at
                      AND event_source_receipt.source_read_finished_at<=?
                    LEFT JOIN appearance_digest d ON d.fixture_id=s.fixture_id
                      AND d.latest_generation=s.latest_generation
                      AND d.source_read_started_at=s.source_read_started_at
                      AND d.source_read_finished_at=s.source_read_finished_at
                    WHERE s.source_read_finished_at<=?
                      AND s.source_read_started_at>sr.source_read_finished_at
                      AND sr.source_pair_fingerprint ~ '^[0-9a-f]{64}$'
                      AND f.gameweek=s.event_id
                      AND f.team_h=s.home_id AND f.team_a=s.away_id
                      AND ((f.gameweek IS NULL AND s.gameweek_key='n')
                           OR f.gameweek::text=s.gameweek_key)
                      AND ((f.kickoff_time IS NULL AND s.kickoff_key='n')
                           OR (f.kickoff_time IS NOT NULL AND s.kickoff_key=
                               round(extract(epoch FROM f.kickoff_time)*1000000)::bigint::text))
                      AND s.finished_key=CASE WHEN f.finished THEN '1' ELSE '0' END
                      AND %s AND f.started=s.started
                      AND %s AND %s
                      AND ((f.team_h_score IS NULL AND s.score_home='n')
                           OR f.team_h_score::text=s.score_home)
                      AND ((f.team_a_score IS NULL AND s.score_away='n')
                           OR f.team_a_score::text=s.score_away)
                ), current_snapshot AS (
                    SELECT DISTINCT ON (s.fixture_id) s.*,
                           s.appearance_floor AS proof_floor
                    FROM source_snapshots s
                    ORDER BY s.fixture_id,s.source_read_started_at DESC,
                             s.source_read_finished_at DESC,s.latest_generation DESC
                ), appearances AS (
                    SELECT s.season,s.fixture_id,s.latest_generation,
                           a.player_id,a.side,a.minutes,s.proof_floor AS appearance_floor,
                           CASE WHEN a.side='HOME' THEN s.team_h ELSE s.team_a END AS club_id
                    FROM current_snapshot s JOIN appearance_rows a
                      ON a.season=s.season AND a.fixture_id=s.fixture_id
                     AND a.latest_generation=s.latest_generation
                     AND a.source_read_started_at=s.source_read_started_at
                     AND a.source_read_finished_at=s.source_read_finished_at
                    WHERE s.digest_valid AND s.source_complete
                ), expected AS (
                    SELECT a.fixture_id, a.club_id, count(*) AS expected_positive,
                           count(*) FILTER (WHERE p.player_id IS NOT NULL
                               AND p.evidence_status='PROVISIONAL' AND p.owned_evidence
                               AND watermark.source_read_started_at>=a.appearance_floor
                               AND watermark.source_read_finished_at>=a.appearance_floor
                               AND watermark.source_read_started_at<=watermark.source_read_finished_at
                               AND watermark.source_read_finished_at<=?
                               AND p.minutes BETWEEN 1 AND 90 AND p.total_points IS NOT NULL
                               AND p.minutes=a.minutes AND p.player_team_id=a.club_id
                               AND p.was_home=(a.side='HOME')
                               AND p.opponent_team_id=CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END)
                               AS matched_positive
                    FROM appearances a JOIN source_fixture f ON f.fixture_id=a.fixture_id
                    LEFT JOIN pfs_read p ON p.season_id=? AND p.fixture_id=a.fixture_id
                      AND p.player_id=a.player_id
                    LEFT JOIN %s watermark ON watermark.season=a.season
                      AND watermark.publication_key='%s' || a.player_id::text
                    GROUP BY a.fixture_id, a.club_id
                ), unexpected AS (
                    SELECT f.fixture_id,
                           CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END AS club_id,
                           count(*) AS unexpected_positive
                    FROM pfs_read p JOIN source_fixture f ON f.fixture_id=p.fixture_id
                    WHERE p.season_id=? AND p.minutes>0
                      AND NOT EXISTS (
                          SELECT 1 FROM appearances a WHERE a.fixture_id=p.fixture_id
                            AND a.player_id=p.player_id
                            AND a.club_id=CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END
                            AND a.minutes=p.minutes AND p.minutes BETWEEN 1 AND 90
                            AND p.total_points IS NOT NULL
                            AND p.opponent_team_id=CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END)
                    GROUP BY f.fixture_id, CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END
                ), side_rows AS (
                    SELECT f.fixture_id, f.team_h AS club_id FROM source_fixture f
                    UNION ALL
                    SELECT f.fixture_id, f.team_a AS club_id FROM source_fixture f
                )
                SELECT s.fixture_id, s.club_id,
                       (c.fixture_id IS NOT NULL OR (r.fixture_id IS NOT NULL AND old_snapshot.fixture_id IS NOT NULL))
                           AS source_roster_known,
                       COALESCE(c.source_complete AND c.digest_valid, false) AS source_roster_complete,
                       COALESCE(e.expected_positive, 0) AS expected_positive,
                       COALESCE(e.matched_positive, 0) AS matched_positive,
                       COALESCE(u.unexpected_positive, 0) AS unexpected_positive
                FROM side_rows s
                LEFT JOIN current_snapshot c ON c.fixture_id=s.fixture_id
                LEFT JOIN source_revisions r ON r.fixture_id=s.fixture_id
                LEFT JOIN snapshot_fixtures old_snapshot ON old_snapshot.fixture_id=s.fixture_id
                LEFT JOIN expected e ON e.fixture_id=s.fixture_id AND e.club_id=s.club_id
                LEFT JOIN unexpected u ON u.fixture_id=s.fixture_id AND u.club_id=s.club_id
                """.formatted(cte(), String.join(",", values), hasCode ? "f.code" : "NULL::integer",
                hasProvisional ? "f.finished_provisional" : "NULL::boolean",
                hasMinutes ? "f.minutes" : "NULL::integer", fixtures, seasonColumn, pageReceipt,
                dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt.SOURCE_FIXTURE_PREFIX,
                pageReceipt, appearanceStatePrefix, pageReceipt, snapshotPrefix, pageReceipt, snapshotPrefix,
                pageReceipt, playerPrefix,
                pageReceipt, liveEventSourcePrefix,
                hasCode ? "f.code=s.fixture_code" : "TRUE",
                hasProvisional ? "COALESCE(f.finished_provisional,FALSE)=s.provisional" : "TRUE",
                hasMinutes ? "f.minutes=s.fixture_minutes" : "TRUE", pageReceipt,
                dev.quantti.fplassistant.sync.IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX);
        args.add(sourceSeasonValue);
        args.add(seasonCode);
        args.add(Timestamp.from(asOf));
        args.add(seasonCode);
        args.add(Timestamp.from(asOf));
        args.add(seasonCode);
        args.add(seasonCode);
        args.add(seasonCode);
        args.add(Timestamp.from(asOf));
        args.add(Timestamp.from(asOf));
        args.add(Timestamp.from(asOf));
        args.add(seasonId);
        args.add(seasonId);
        Map<FixtureSide, SidePopulation> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            int fixtureId = rs.getInt("fixture_id");
            boolean sourceRosterKnown = rs.getBoolean("source_roster_known");
            FixtureReceipt receipt = sourceRosterKnown ? FixtureReceipt.INCREMENTAL
                    : receipts.getOrDefault(fixtureId, FixtureReceipt.LEGACY);
            result.put(new FixtureSide(fixtureId, rs.getInt("club_id")), new SidePopulation(receipt,
                    sourceRosterKnown, rs.getBoolean("source_roster_complete"),
                    rs.getInt("expected_positive"), rs.getInt("matched_positive"),
                    rs.getInt("unexpected_positive")));
        }, args.toArray());
        return Map.copyOf(result);
    }

    private Map<FixtureSide, SidePopulation> unprovenFixtureSides(
            String fixtureTable, String seasonColumn, Object seasonValue, Set<Integer> fixtureIds,
            Map<Integer, FixtureReceipt> receipts) {
        List<Integer> ordered = fixtureIds.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(seasonValue);
        args.addAll(ordered);
        Map<FixtureSide, SidePopulation> result = new HashMap<>();
        jdbc.query("SELECT id,team_h,team_a FROM " + fixtureTable + " WHERE " + seasonColumn
                + "=? AND id IN (" + placeholders + ")", rs -> {
            int fixtureId = rs.getInt("id");
            FixtureReceipt receipt = receipts.getOrDefault(fixtureId, FixtureReceipt.LEGACY);
            SidePopulation population = new SidePopulation(receipt, false, false, 0, 0, 0);
            result.put(new FixtureSide(fixtureId, rs.getInt("team_h")), population);
            result.put(new FixtureSide(fixtureId, rs.getInt("team_a")), population);
        }, args.toArray());
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

    private boolean hasColumn(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema=ANY(current_schemas(false)) AND table_name=? AND column_name=?)
                """, Boolean.class, table, column));
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
