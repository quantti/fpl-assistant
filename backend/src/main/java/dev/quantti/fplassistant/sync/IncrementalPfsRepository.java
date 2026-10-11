package dev.quantti.fplassistant.sync;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.sync.GameweekFinality.RowState;
import dev.quantti.fplassistant.sync.HistoryParser.Component;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;

/** Database-only, per-player-page publisher. It is not wired to an automatic runtime. */
public final class IncrementalPfsRepository {

    private static final List<Component> OPTIONAL_COLUMN_ORDER = List.of(
            Component.EXPECTED_GOALS, Component.EXPECTED_ASSISTS,
            Component.EXPECTED_GOAL_INVOLVEMENTS, Component.EXPECTED_GOALS_CONCEDED,
            Component.INFLUENCE, Component.CREATIVITY, Component.THREAT, Component.ICT_INDEX,
            Component.CLEARANCES_BLOCKS_INTERCEPTIONS, Component.RECOVERIES,
            Component.TACKLES, Component.DEFENSIVE_CONTRIBUTION);

    private static final String PFS_UPSERT = """
            INSERT INTO player_fixture_stats AS existing (fixture_id, player_id, season_id, gameweek,
                player_team_id, opponent_team_id, was_home, kickoff_time, minutes, total_points,
                bonus, bps, goals_scored, assists, expected_goals, expected_assists,
                expected_goal_involvements, clean_sheets, goals_conceded, own_goals,
                penalties_saved, penalties_missed, saves, expected_goals_conceded,
                yellow_cards, red_cards, influence, creativity, threat, ict_index, value,
                selected, transfers_in, transfers_out, starts, clearances_blocks_interceptions,
                recoveries, tackles, defensive_contribution, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (fixture_id, player_id, season_id) DO UPDATE SET
                gameweek=EXCLUDED.gameweek, player_team_id=EXCLUDED.player_team_id,
                opponent_team_id=EXCLUDED.opponent_team_id, was_home=EXCLUDED.was_home,
                kickoff_time=EXCLUDED.kickoff_time, minutes=EXCLUDED.minutes,
                total_points=EXCLUDED.total_points, bonus=EXCLUDED.bonus, bps=EXCLUDED.bps,
                goals_scored=EXCLUDED.goals_scored, assists=EXCLUDED.assists,
                expected_goals=CASE WHEN ? THEN EXCLUDED.expected_goals ELSE existing.expected_goals END,
                expected_assists=CASE WHEN ? THEN EXCLUDED.expected_assists ELSE existing.expected_assists END,
                expected_goal_involvements=CASE WHEN ? THEN EXCLUDED.expected_goal_involvements ELSE existing.expected_goal_involvements END,
                clean_sheets=EXCLUDED.clean_sheets, goals_conceded=EXCLUDED.goals_conceded,
                own_goals=EXCLUDED.own_goals, penalties_saved=EXCLUDED.penalties_saved,
                penalties_missed=EXCLUDED.penalties_missed, saves=EXCLUDED.saves,
                expected_goals_conceded=CASE WHEN ? THEN EXCLUDED.expected_goals_conceded ELSE existing.expected_goals_conceded END,
                yellow_cards=EXCLUDED.yellow_cards, red_cards=EXCLUDED.red_cards,
                influence=CASE WHEN ? THEN EXCLUDED.influence ELSE existing.influence END,
                creativity=CASE WHEN ? THEN EXCLUDED.creativity ELSE existing.creativity END,
                threat=CASE WHEN ? THEN EXCLUDED.threat ELSE existing.threat END,
                ict_index=CASE WHEN ? THEN EXCLUDED.ict_index ELSE existing.ict_index END,
                value=EXCLUDED.value, selected=EXCLUDED.selected,
                transfers_in=EXCLUDED.transfers_in, transfers_out=EXCLUDED.transfers_out,
                starts=EXCLUDED.starts,
                clearances_blocks_interceptions=CASE WHEN ? THEN EXCLUDED.clearances_blocks_interceptions ELSE existing.clearances_blocks_interceptions END,
                recoveries=CASE WHEN ? THEN EXCLUDED.recoveries ELSE existing.recoveries END,
                tackles=CASE WHEN ? THEN EXCLUDED.tackles ELSE existing.tackles END,
                defensive_contribution=CASE WHEN ? THEN EXCLUDED.defensive_contribution ELSE existing.defensive_contribution END,
                updated_at=EXCLUDED.updated_at
            WHERE (existing.gameweek, existing.player_team_id, existing.opponent_team_id,
                   existing.was_home, existing.kickoff_time, existing.minutes, existing.total_points,
                   existing.bonus, existing.bps, existing.goals_scored, existing.assists,
                   existing.expected_goals, existing.expected_assists,
                   existing.expected_goal_involvements, existing.clean_sheets, existing.goals_conceded,
                   existing.own_goals, existing.penalties_saved, existing.penalties_missed, existing.saves,
                   existing.expected_goals_conceded, existing.yellow_cards, existing.red_cards,
                   existing.influence, existing.creativity, existing.threat, existing.ict_index,
                   existing.value, existing.selected, existing.transfers_in, existing.transfers_out,
                   existing.starts, existing.clearances_blocks_interceptions, existing.recoveries,
                   existing.tackles, existing.defensive_contribution)
              IS DISTINCT FROM
                  (EXCLUDED.gameweek, EXCLUDED.player_team_id, EXCLUDED.opponent_team_id,
                   EXCLUDED.was_home, EXCLUDED.kickoff_time, EXCLUDED.minutes, EXCLUDED.total_points,
                   EXCLUDED.bonus, EXCLUDED.bps, EXCLUDED.goals_scored, EXCLUDED.assists,
                   CASE WHEN ? THEN EXCLUDED.expected_goals ELSE existing.expected_goals END,
                   CASE WHEN ? THEN EXCLUDED.expected_assists ELSE existing.expected_assists END,
                   CASE WHEN ? THEN EXCLUDED.expected_goal_involvements ELSE existing.expected_goal_involvements END,
                   EXCLUDED.clean_sheets, EXCLUDED.goals_conceded, EXCLUDED.own_goals,
                   EXCLUDED.penalties_saved, EXCLUDED.penalties_missed, EXCLUDED.saves,
                   CASE WHEN ? THEN EXCLUDED.expected_goals_conceded ELSE existing.expected_goals_conceded END,
                   EXCLUDED.yellow_cards, EXCLUDED.red_cards,
                   CASE WHEN ? THEN EXCLUDED.influence ELSE existing.influence END,
                   CASE WHEN ? THEN EXCLUDED.creativity ELSE existing.creativity END,
                   CASE WHEN ? THEN EXCLUDED.threat ELSE existing.threat END,
                   CASE WHEN ? THEN EXCLUDED.ict_index ELSE existing.ict_index END,
                   EXCLUDED.value, EXCLUDED.selected, EXCLUDED.transfers_in, EXCLUDED.transfers_out,
                   EXCLUDED.starts,
                   CASE WHEN ? THEN EXCLUDED.clearances_blocks_interceptions ELSE existing.clearances_blocks_interceptions END,
                   CASE WHEN ? THEN EXCLUDED.recoveries ELSE existing.recoveries END,
                   CASE WHEN ? THEN EXCLUDED.tackles ELSE existing.tackles END,
                   CASE WHEN ? THEN EXCLUDED.defensive_contribution ELSE existing.defensive_contribution END)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnedSyncStore store;
    private final OwnedSyncTables tables;

    public IncrementalPfsRepository(JdbcTemplate jdbc, Clock clock, OwnedSyncStore store,
                                    OwnedSyncTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.store = Objects.requireNonNull(store);
        this.tables = Objects.requireNonNull(tables);
    }

    PublicationResult publishValidated(Lease lease, HistoryBatch batch) {
        validateBatch(batch);
        SourceRead read = new SourceRead(OwnedSyncStore.postgresTimestampPrecision(batch.sourceRead().startedAt()),
                OwnedSyncStore.postgresTimestampPrecision(batch.sourceRead().completedAt()));
        return store.publishPlayerHistoryPage(lease, batch.playerId(), batch.seasonId(), batch.seasonCode(), read,
                () -> publishInsideLease(lease, batch, read));
    }

    private PublicationResult publishInsideLease(Lease lease, HistoryBatch batch, SourceRead read) {
        // Serializes complete-page reconciliation for one player, including a first-page insert.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + batch.seasonId() + ", " + batch.playerId() + ")");
        jdbc.query("SELECT fixture_id FROM player_fixture_stats WHERE season_id=? AND player_id=?"
                        + " ORDER BY fixture_id FOR UPDATE", rs -> { }, batch.seasonId(), batch.playerId());
        Map<Integer, CurrentPointer> current = loadCurrent(batch.seasonId(), batch.seasonCode(), batch.playerId());
        Map<Integer, LegacyOwnedRow> legacyOwned = loadLegacyOwnedRows(batch.seasonId(), batch.playerId());
        Map<Integer, HistoryRow> received = new HashMap<>();
        for (HistoryRow row : batch.rows()) received.put(row.fixtureId(), row);

        Map<Integer, Boolean> apply = new HashMap<>();
        Map<Integer, Boolean> physicalProofByFixture = new HashMap<>();
        Map<Integer, String> fingerprints = new HashMap<>();
        for (HistoryRow row : batch.rows()) {
            String fingerprint = PlayerFixtureFingerprint.of(row);
            boolean physicalProofMatches = currentPhysicalProofMatches(current.get(row.fixtureId()), row);
            fingerprints.put(row.fixtureId(), fingerprint);
            physicalProofByFixture.put(row.fixtureId(), physicalProofMatches);
            apply.put(row.fixtureId(), shouldApply(current.get(row.fixtureId()), RowState.VALID,
                    fingerprint, lease.generation(), read, physicalProofMatches));
        }
        for (CurrentPointer pointer : current.values()) {
            if (!received.containsKey(pointer.fixtureId())) {
                apply.put(pointer.fixtureId(), shouldApply(pointer, RowState.WITHDRAWN,
                        null, lease.generation(), read, true));
            }
        }

        Instant publishedAt = clock.instant();
        Set<RowKey> changed = new LinkedHashSet<>();
        int pfsRowsWritten = 0;
        int evidenceRowsWritten = 0;
        int currentPointersUpdated = 0;
        Set<Integer> observedFixtures = new LinkedHashSet<>();

        for (HistoryRow row : batch.rows().stream()
                .sorted(java.util.Comparator.comparingInt(HistoryRow::fixtureId)).toList()) {
            if (!apply.get(row.fixtureId())) continue;
            CurrentPointer prior = current.get(row.fixtureId());
            pfsRowsWritten += upsertPfs(row, publishedAt);
            evidenceRowsWritten += insertEvidence(row, lease.generation(), read);
            String physicalFingerprint = physicalFingerprint(row);
            upsertCurrent(batch.seasonId(), batch.seasonCode(), row.fixtureId(), batch.playerId(),
                    RowState.VALID, lease.generation(), lease.generation(), fingerprints.get(row.fixtureId()),
                    physicalFingerprint, read, publishedAt);
            currentPointersUpdated++;
            observedFixtures.add(row.fixtureId());
            Integer priorGameweek = trustedPriorGameweek(prior);
            if (priorGameweek != null && priorGameweek != row.gameweek()) {
                changed.add(new RowKey(batch.seasonId(), batch.seasonCode(), priorGameweek,
                        row.fixtureId(), batch.playerId()));
            }
            if (prior == null || prior.state() != RowState.VALID
                    || !Objects.equals(prior.fingerprint(), fingerprints.get(row.fixtureId()))
                    || !physicalProofByFixture.get(row.fixtureId())) {
                changed.add(new RowKey(batch.seasonId(), batch.seasonCode(), row.gameweek(),
                        row.fixtureId(), batch.playerId()));
            }
        }

        for (CurrentPointer pointer : current.values().stream()
                .filter(row -> !received.containsKey(row.fixtureId()))
                .sorted(java.util.Comparator.comparingInt(CurrentPointer::fixtureId)).toList()) {
            if (!apply.get(pointer.fixtureId())) continue;
            upsertCurrent(batch.seasonId(), batch.seasonCode(), pointer.fixtureId(), batch.playerId(),
                    RowState.WITHDRAWN, lease.generation(), null, null, null, read, publishedAt);
            currentPointersUpdated++;
            observedFixtures.add(pointer.fixtureId());
            Integer priorGameweek = trustedPriorGameweek(pointer);
            if (priorGameweek != null) {
                changed.add(new RowKey(batch.seasonId(), batch.seasonCode(), priorGameweek,
                        pointer.fixtureId(), batch.playerId()));
            }
        }

        for (LegacyOwnedRow legacy : legacyOwned.values()) {
            if (received.containsKey(legacy.fixtureId()) || current.containsKey(legacy.fixtureId())) continue;
            upsertCurrent(batch.seasonId(), batch.seasonCode(), legacy.fixtureId(), batch.playerId(),
                    RowState.WITHDRAWN, lease.generation(), null, null, null, read, publishedAt);
            currentPointersUpdated++;
            observedFixtures.add(legacy.fixtureId());
            if (validGameweek(legacy.gameweek())) {
                changed.add(new RowKey(batch.seasonId(), batch.seasonCode(), legacy.gameweek(),
                        legacy.fixtureId(), batch.playerId()));
            }
        }

        for (int fixtureId : observedFixtures) revokeObsoleteFixtureVerification(batch.seasonId(), fixtureId,
                publishedAt);
        if (!changed.isEmpty()) store.invalidateIncrementalGameweeks(lease, changed, read);

        return new PublicationResult(changed, pfsRowsWritten, evidenceRowsWritten, currentPointersUpdated);
    }

    private void revokeObsoleteFixtureVerification(int seasonId, int fixtureId, Instant updatedAt) {
        jdbc.update("""
                UPDATE %s SET state='WAITING_STATS', current_verified_generation=NULL, updated_at=?
                WHERE season_id=? AND fixture_id=? AND current_verified_generation IS NOT NULL
                """.formatted(tables.fixtureSync()), timestamp(updatedAt), seasonId, fixtureId);
    }

    private Map<Integer, LegacyOwnedRow> loadLegacyOwnedRows(int seasonId, int playerId) {
        String sql = """
                SELECT p.fixture_id, p.gameweek
                FROM player_fixture_stats p
                JOIN %s fs ON fs.season_id=p.season_id AND fs.fixture_id=p.fixture_id
                WHERE p.season_id=? AND p.player_id=?
                  AND (fs.current_verified_generation IS NOT NULL
                       OR fs.last_verified_generation IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM %s c WHERE c.season_id=p.season_id
                      AND c.fixture_id=p.fixture_id AND c.player_id=p.player_id)
                ORDER BY p.fixture_id
                """.formatted(tables.fixtureSync(), tables.playerFixtureCurrent());
        Map<Integer, LegacyOwnedRow> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            int fixtureId = rs.getInt("fixture_id");
            int gameweekValue = rs.getInt("gameweek");
            Integer gameweek = rs.wasNull() ? null : gameweekValue;
            result.put(fixtureId, new LegacyOwnedRow(fixtureId, gameweek));
        }, seasonId, playerId);
        return result;
    }

    private Map<Integer, CurrentPointer> loadCurrent(int seasonId, String seasonCode, int playerId) {
        String actualPhysicalFingerprint = PlayerFixtureFingerprint.physicalSqlExpression("p", "s",
                "e.component_presence");
        String sql = """
                SELECT c.fixture_id, c.row_state, c.source_run_generation, c.row_fingerprint,
                       c.physical_fingerprint, c.evidence_generation, c.source_read_started_at,
                       c.source_read_finished_at, p.gameweek, p.fixture_id IS NOT NULL AS physical_present,
                       e.component_presence,
                       CASE WHEN p.fixture_id IS NOT NULL AND e.generation IS NOT NULL
                           THEN %s END AS actual_physical_fingerprint,
                       c.evidence_generation=c.source_run_generation
                           AND e.generation=c.evidence_generation AND e.season_code=c.season_code
                           AND e.source_read_started_at=c.source_read_started_at
                           AND e.source_read_finished_at=c.source_read_finished_at AS evidence_matches
                FROM %s c
                LEFT JOIN player_fixture_stats p ON p.season_id=c.season_id
                    AND p.fixture_id=c.fixture_id AND p.player_id=c.player_id
                LEFT JOIN %s e ON e.season_id=c.season_id AND e.fixture_id=c.fixture_id
                    AND e.player_id=c.player_id AND e.generation=c.evidence_generation
                LEFT JOIN season s ON s.id=c.season_id
                WHERE c.season_id=? AND c.season_code=? AND c.player_id=?
                ORDER BY c.fixture_id FOR UPDATE OF c
                """.formatted(actualPhysicalFingerprint, tables.playerFixtureCurrent(),
                tables.playerFixtureEvidence());
        Map<Integer, CurrentPointer> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            int fixtureId = rs.getInt("fixture_id");
            RowState state = RowState.valueOf(rs.getString("row_state"));
            int gameweekValue = rs.getInt("gameweek");
            Integer gameweek = rs.wasNull() ? null : gameweekValue;
            CurrentPointer pointer = new CurrentPointer(fixtureId, state,
                    rs.getLong("source_run_generation"), rs.getString("row_fingerprint"),
                    instant(rs, "source_read_started_at"), instant(rs, "source_read_finished_at"),
                    gameweek, rs.getBoolean("physical_present"), rs.getBoolean("evidence_matches"),
                    rs.getObject("component_presence", Long.class), rs.getString("physical_fingerprint"),
                    rs.getString("actual_physical_fingerprint"));
            result.put(fixtureId, pointer);
        }, seasonId, seasonCode, playerId);
        return result;
    }

    private static boolean currentPhysicalProofMatches(CurrentPointer current, HistoryRow row) {
        return currentPhysicalRowMatchesPointer(current)
                && current.componentPresence() == row.presenceMask();
    }

    private static Integer trustedPriorGameweek(CurrentPointer current) {
        // Never infer an old assignment from the opaque row fingerprint when physical proof fails.
        return currentPhysicalRowMatchesPointer(current) && validGameweek(current.gameweek())
                ? current.gameweek() : null;
    }

    private static boolean currentPhysicalRowMatchesPointer(CurrentPointer current) {
        return current != null && current.state() == RowState.VALID && current.physicalPresent()
                && current.evidenceMatches() && current.componentPresence() != null
                && current.physicalFingerprint() != null
                && current.physicalFingerprint().equals(current.actualPhysicalFingerprint());
    }

    private static boolean validGameweek(Integer gameweek) {
        return gameweek != null && gameweek >= 1 && gameweek <= 38;
    }

    private static boolean shouldApply(CurrentPointer current, RowState desiredState, String fingerprint,
                                       long generation, SourceRead read, boolean physicalProofMatches) {
        if (current == null) return true;
        boolean sameRevision = current.state() == desiredState
                && Objects.equals(current.fingerprint(), fingerprint);
        boolean proofMatches = desiredState != RowState.VALID || physicalProofMatches;
        int generationOrder = Long.compare(generation, current.sourceRunGeneration());
        int sourceOrder = read.finishedAt().compareTo(current.sourceReadFinishedAt());
        if (generationOrder < 0) {
            if (sameRevision && proofMatches) return false;
            throw sourceConflict(current, generation, read,
                    "older observation cannot replace or repair the current row");
        }
        if (generationOrder == 0) {
            if (sameRevision && proofMatches && read.startedAt().equals(current.sourceReadStartedAt())
                    && read.finishedAt().equals(current.sourceReadFinishedAt())) return false;
            throw sourceConflict(current, generation, read, "one run cannot publish conflicting page revisions");
        }
        if (sourceOrder < 0 || read.startedAt().isBefore(current.sourceReadFinishedAt())) {
            throw sourceConflict(current, generation, read, "newer generation has an older or overlapping interval");
        }
        if (sameRevision && proofMatches) return false;
        return true;
    }

    private static SourceOrderConflictException sourceConflict(CurrentPointer current, long generation,
                                                               SourceRead read, String reason) {
        return new SourceOrderConflictException("Player-fixture " + current.fixtureId() + " run "
                + generation + " read " + read + " cannot replace run " + current.sourceRunGeneration()
                + " read " + current.sourceReadStartedAt() + ".." + current.sourceReadFinishedAt()
                + ": " + reason);
    }

    private int upsertPfs(HistoryRow row, Instant updatedAt) {
        List<Object> parameters = new ArrayList<>(64);
        parameters.addAll(java.util.Arrays.asList(row.fixtureId(), row.playerId(), row.seasonId(), row.gameweek(),
                row.playerTeamId(), row.opponentTeamId(), row.wasHome(), timestamp(row.kickoffTime()),
                row.minutes(), row.totalPoints(), row.bonus(), row.bps(), row.goalsScored(), row.assists(),
                zero(row.expectedGoals()), zero(row.expectedAssists()),
                zero(row.expectedGoalInvolvements()), row.cleanSheets(), row.goalsConceded(), row.ownGoals(),
                row.penaltiesSaved(), row.penaltiesMissed(), row.saves(), zero(row.expectedGoalsConceded()),
                row.yellowCards(), row.redCards(), zero(row.influence()), zero(row.creativity()),
                zero(row.threat()), zero(row.ictIndex()), row.value(), row.selected(), row.transfersIn(),
                row.transfersOut(), row.starts()));
        parameters.add(row.clearancesBlocksInterceptions());
        parameters.add(row.recoveries());
        parameters.add(row.tackles());
        parameters.add(row.defensiveContribution());
        parameters.add(timestamp(updatedAt));
        parameters.addAll(presenceFlags(row));
        parameters.addAll(presenceFlags(row));
        return jdbc.update(PFS_UPSERT, parameters.toArray());
    }

    private int insertEvidence(HistoryRow row, long generation, SourceRead read) {
        int inserted = jdbc.update("""
                INSERT INTO %s (season_id, season_code, fixture_id, player_id, generation,
                    component_presence, source_read_started_at, source_read_finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """.formatted(tables.playerFixtureEvidence()), row.seasonId(), row.seasonCode(),
                row.fixtureId(), row.playerId(), generation, row.presenceMask(),
                timestamp(read.startedAt()), timestamp(read.finishedAt()));
        if (inserted == 1) return 1;
        Boolean same = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM %s WHERE season_id=? AND season_code=? AND fixture_id=?
                    AND player_id=? AND generation=? AND component_presence=?
                    AND source_read_started_at=? AND source_read_finished_at=?)
                """.formatted(tables.playerFixtureEvidence()), Boolean.class, row.seasonId(), row.seasonCode(),
                row.fixtureId(), row.playerId(), generation, row.presenceMask(),
                timestamp(read.startedAt()), timestamp(read.finishedAt()));
        if (!Boolean.TRUE.equals(same)) {
            throw new SourceOrderConflictException("Evidence generation already contains another source page");
        }
        return 0;
    }

    private String physicalFingerprint(HistoryRow row) {
        String sql = "SELECT " + PlayerFixtureFingerprint.physicalSqlExpression("p", "s",
                Long.toString(row.presenceMask())) + " FROM player_fixture_stats p"
                + " JOIN season s ON s.id=p.season_id"
                + " WHERE p.season_id=? AND p.fixture_id=? AND p.player_id=?";
        return jdbc.queryForObject(sql, String.class, row.seasonId(), row.fixtureId(), row.playerId());
    }

    private void upsertCurrent(int seasonId, String seasonCode, int fixtureId, int playerId,
                               RowState state, long sourceGeneration, Long evidenceGeneration,
                               String fingerprint, String physicalFingerprint, SourceRead read,
                               Instant updatedAt) {
        int changed = jdbc.update("""
                INSERT INTO %s AS current_row (season_id, season_code, fixture_id, player_id, row_state,
                    source_run_generation, evidence_generation, row_fingerprint, physical_fingerprint,
                    source_read_started_at, source_read_finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season_id, fixture_id, player_id) DO UPDATE SET
                    row_state=EXCLUDED.row_state, source_run_generation=EXCLUDED.source_run_generation,
                    evidence_generation=EXCLUDED.evidence_generation, row_fingerprint=EXCLUDED.row_fingerprint,
                    physical_fingerprint=EXCLUDED.physical_fingerprint,
                    source_read_started_at=EXCLUDED.source_read_started_at,
                    source_read_finished_at=EXCLUDED.source_read_finished_at, updated_at=EXCLUDED.updated_at
                WHERE EXCLUDED.source_run_generation > current_row.source_run_generation
                    AND EXCLUDED.source_read_started_at >= current_row.source_read_finished_at
                """.formatted(tables.playerFixtureCurrent()), seasonId, seasonCode, fixtureId, playerId,
                state.name(), sourceGeneration, evidenceGeneration, fingerprint, physicalFingerprint,
                timestamp(read.startedAt()), timestamp(read.finishedAt()), timestamp(updatedAt));
        if (changed != 1) {
            throw new SourceOrderConflictException("Current player-fixture pointer changed during publication");
        }
    }

    private static List<Boolean> presenceFlags(HistoryRow row) {
        return OPTIONAL_COLUMN_ORDER.stream().map(row::has).toList();
    }

    private static void validateBatch(HistoryBatch batch) {
        Objects.requireNonNull(batch);
        if (batch.seasonId() <= 0 || batch.seasonCode() == null || batch.seasonCode().isBlank()
                || batch.playerId() <= 0) {
            throw new IllegalArgumentException("Invalid validated player page identity");
        }
        Set<Integer> fixtures = new HashSet<>();
        for (HistoryRow row : batch.rows()) {
            if (row.seasonId() != batch.seasonId() || !batch.seasonCode().equals(row.seasonCode())
                    || row.playerId() != batch.playerId() || row.fixtureId() <= 0
                    || row.gameweek() <= 0 || !fixtures.add(row.fixtureId())) {
                throw new IllegalArgumentException("History batch is not one complete validated player page");
            }
        }
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(OwnedSyncStore.postgresTimestampPrecision(value));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record CurrentPointer(int fixtureId, RowState state, long sourceRunGeneration, String fingerprint,
                                  Instant sourceReadStartedAt, Instant sourceReadFinishedAt, Integer gameweek,
                                  boolean physicalPresent, boolean evidenceMatches, Long componentPresence,
                                  String physicalFingerprint, String actualPhysicalFingerprint) { }

    private record LegacyOwnedRow(int fixtureId, Integer gameweek) { }

    public record RowKey(int seasonId, String seasonCode, int gameweek, int fixtureId, int playerId) { }

    public record PublicationResult(Set<RowKey> changedKeys, int pfsRowsWritten,
                                    int evidenceRowsWritten, int currentPointersUpdated) {
        public PublicationResult {
            changedKeys = Set.copyOf(changedKeys);
            if (pfsRowsWritten < 0 || evidenceRowsWritten < 0 || currentPointersUpdated < 0) {
                throw new IllegalArgumentException("Publication counts cannot be negative");
            }
        }
    }

    public static final class SourceOrderConflictException extends IllegalStateException {
        public SourceOrderConflictException(String message) { super(message); }
    }
}
