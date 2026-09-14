package dev.quantti.fplassistant.sync;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.FixtureVerification.Decision;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;
import dev.quantti.fplassistant.sync.OwnedSyncStore.ConditionalPublication;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;

/** Explicit, network-free fixture observation and verified PFS publication boundary. */
public final class FixtureSyncPublisher {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnedSyncStore store;
    private final FixtureSyncRepository repository;
    private final OwnedSyncTables tables;

    public FixtureSyncPublisher(JdbcTemplate jdbc, Clock clock, OwnedSyncStore store,
                                FixtureSyncRepository repository, OwnedSyncTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.store = Objects.requireNonNull(store);
        this.repository = Objects.requireNonNull(repository);
        this.tables = Objects.requireNonNull(tables);
    }

    public static String publicationKey(int fixtureId) {
        if (fixtureId <= 0) throw new IllegalArgumentException("fixture id must be positive");
        return "fixture:" + fixtureId;
    }

    /**
     * Public boundary: accepts only raw independently supplied source documents, parses the full
     * canonical catalogue/schedule snapshot, and parses every element history against that full
     * schedule before entering the fenced publication transaction.
     */
    public Decision publish(Lease lease, Season season, int fixtureId, SourceDocument bootstrap,
                            SourceDocument fixtures, SourceDocument liveDocument,
                            Map<Integer, SourceDocument> elementSummaries) {
        Objects.requireNonNull(elementSummaries, "elementSummaries");
        var snapshot = CatalogueScheduleParser.parse(season, bootstrap, fixtures);
        FixtureRow fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "fixture is absent from canonical schedule: " + fixtureId));
        List<HistoryParser.CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        List<HistoryBatch> histories = elementSummaries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> HistoryParser.parse(season, entry.getKey(), entry.getValue(), canonical))
                .toList();
        SourceRead allReads = combined(bootstrap.readInterval(), fixtures.readInterval(),
                liveDocument.readInterval(), elementSummaries.values());
        List<SourceRead> demandReads = new java.util.ArrayList<>();
        demandReads.add(sourceRead(bootstrap.readInterval()));
        demandReads.add(sourceRead(fixtures.readInterval()));
        demandReads.add(sourceRead(liveDocument.readInterval()));
        elementSummaries.values().forEach(document -> demandReads.add(sourceRead(document.readInterval())));
        Set<Integer> cataloguePlayers = snapshot.players().stream()
                .map(CatalogueScheduleParser.PlayerRow::id).collect(java.util.stream.Collectors.toSet());
        return publishParsed(lease, season, fixture, fixtures.readInterval(), liveDocument,
                histories, allReads, List.copyOf(demandReads), cataloguePlayers);
    }

    Decision publishParsed(Lease lease, Season season, FixtureRow fixture, ReadInterval fixtureRead,
                           SourceDocument liveDocument, Collection<HistoryBatch> histories) {
        return publishParsed(lease, season, fixture, fixtureRead, liveDocument, histories, null);
    }

    Decision publishParsed(Lease lease, Season season, FixtureRow fixture, ReadInterval fixtureRead,
                           SourceDocument liveDocument, Collection<HistoryBatch> histories,
                           Set<Integer> cataloguePlayers) {
        return publishParsed(lease, season, fixture, fixtureRead, liveDocument, histories,
                combined(fixtureRead, liveDocument.readInterval(), histories), List.of(), cataloguePlayers);
    }

    private Decision publishParsed(Lease lease, Season season, FixtureRow fixture,
                                   ReadInterval fixtureRead, SourceDocument liveDocument,
                                   Collection<HistoryBatch> histories, SourceRead combined,
                                   List<SourceRead> demandReads, Set<Integer> cataloguePlayers) {
        requireLease(lease, season, fixture);
        Objects.requireNonNull(histories);
        return store.publishConditionally(lease, combined, demandReads, () -> {
            Decision decision = publishInsideFence(lease, season, fixture, fixtureRead,
                    liveDocument, histories, cataloguePlayers);
            return new ConditionalPublication<>(decision,
                    decision.state() == FixtureVerification.State.VERIFIED);
        });
    }

    private Decision publishInsideFence(Lease lease, Season season, FixtureRow fixture,
                                        ReadInterval fixtureRead, SourceDocument liveDocument,
                                        Collection<HistoryBatch> histories,
                                        Set<Integer> cataloguePlayers) {
        Instant now = clock.instant();
        repository.lockAndValidatePlayingSeason(season);
        var stored = repository.lockObservation(season, fixture.id(), now).orElse(null);
        if (stored != null && lease.generation() <= stored.generation()) {
            throw new OwnedSyncStore.StaleGenerationException(lease.generation(), stored.generation(),
                    season.code(), publicationKey(fixture.id()));
        }
        var prior = stored == null ? null : stored.observation();
        List<HistoryRow> rows = histories.stream().flatMap(batch -> batch.rows().stream())
                .filter(row -> row.fixtureId() == fixture.id()).toList();
        Set<FixtureVerification.Reason> parentFailures = repository.missingParents(season, fixture,
                rows.stream().map(HistoryRow::playerId).collect(java.util.stream.Collectors.toSet()),
                cataloguePlayers);
        Decision decision = FixtureVerification.verify(season, fixture, fixtureRead, liveDocument,
                histories, prior, now, parentFailures);
        if (decision.state() == FixtureVerification.State.VERIFIED) {
            writePfs(rows, now);
            writeEvidence(rows, histories, lease.generation());
        }
        boolean unresolvedParent = decision.reasons().contains(
                FixtureVerification.Reason.UNRESOLVED_PARENT);
        repository.saveDecision(lease.generation(), fixture, decision, now, null,
                unresolvedParent ? "UNRESOLVED_PARENT: player is absent from the fresh bootstrap catalogue" : null);
        if (decision.state() == FixtureVerification.State.PENDING) {
            repository.scheduleNextAttempt(season, fixture.id(), now);
        }
        return decision;
    }

    Set<Integer> reconcileHistoryFingerprints(HistoryBatch batch) {
        return repository.reconcileHistoryFingerprints(batch, clock.instant());
    }

    FixtureSyncRepository.AttemptAdmission admitAttempt(Season season, int fixtureId) {
        return repository.admitAttempt(season, fixtureId, clock.instant());
    }

    Instant recordFailedAttempt(String seasonCode, int fixtureId, Instant minimumDue, String error) {
        return repository.recordFailedAttempt(seasonCode, fixtureId, minimumDue, error, clock.instant());
    }

    private void requireLease(Lease lease, Season season, FixtureRow fixture) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(season);
        Objects.requireNonNull(fixture);
        if (lease.kind() != SyncKind.FIXTURE_VERIFICATION
                || !season.code().equals(lease.season())
                || !publicationKey(fixture.id()).equals(lease.publicationKey())
                || fixture.seasonId() != season.id() || !fixture.seasonCode().equals(season.code())) {
            throw new IllegalArgumentException("Lease and fixture must identify one canonical verification unit");
        }
    }

    private void writePfs(List<HistoryRow> rows, Instant publishedAt) {
        String sql = """
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
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
                    expected_goals=CASE WHEN ? THEN EXCLUDED.expected_goals ELSE player_fixture_stats.expected_goals END,
                    expected_assists=CASE WHEN ? THEN EXCLUDED.expected_assists ELSE player_fixture_stats.expected_assists END,
                    expected_goal_involvements=CASE WHEN ? THEN EXCLUDED.expected_goal_involvements ELSE player_fixture_stats.expected_goal_involvements END,
                    clean_sheets=EXCLUDED.clean_sheets, goals_conceded=EXCLUDED.goals_conceded,
                    own_goals=EXCLUDED.own_goals, penalties_saved=EXCLUDED.penalties_saved,
                    penalties_missed=EXCLUDED.penalties_missed, saves=EXCLUDED.saves,
                    expected_goals_conceded=CASE WHEN ? THEN EXCLUDED.expected_goals_conceded ELSE player_fixture_stats.expected_goals_conceded END,
                    yellow_cards=EXCLUDED.yellow_cards, red_cards=EXCLUDED.red_cards,
                    influence=CASE WHEN ? THEN EXCLUDED.influence ELSE player_fixture_stats.influence END,
                    creativity=CASE WHEN ? THEN EXCLUDED.creativity ELSE player_fixture_stats.creativity END,
                    threat=CASE WHEN ? THEN EXCLUDED.threat ELSE player_fixture_stats.threat END,
                    ict_index=CASE WHEN ? THEN EXCLUDED.ict_index ELSE player_fixture_stats.ict_index END,
                    value=EXCLUDED.value,
                    selected=EXCLUDED.selected, transfers_in=EXCLUDED.transfers_in,
                    transfers_out=EXCLUDED.transfers_out, starts=EXCLUDED.starts,
                    clearances_blocks_interceptions=CASE WHEN ? THEN EXCLUDED.clearances_blocks_interceptions ELSE player_fixture_stats.clearances_blocks_interceptions END,
                    recoveries=CASE WHEN ? THEN EXCLUDED.recoveries ELSE player_fixture_stats.recoveries END,
                    tackles=CASE WHEN ? THEN EXCLUDED.tackles ELSE player_fixture_stats.tackles END,
                    defensive_contribution=CASE WHEN ? THEN EXCLUDED.defensive_contribution ELSE player_fixture_stats.defensive_contribution END,
                    updated_at=EXCLUDED.updated_at
                """;
        jdbc.batchUpdate(sql, rows.stream().map(row -> new Object[]{row.fixtureId(), row.playerId(),
                row.seasonId(), row.gameweek(), row.playerTeamId(), row.opponentTeamId(), row.wasHome(),
                timestamp(row.kickoffTime()), row.minutes(), row.totalPoints(), row.bonus(), row.bps(),
                row.goalsScored(), row.assists(), zero(row.expectedGoals()), zero(row.expectedAssists()),
                zero(row.expectedGoalInvolvements()), row.cleanSheets(), row.goalsConceded(), row.ownGoals(),
                row.penaltiesSaved(), row.penaltiesMissed(), row.saves(), zero(row.expectedGoalsConceded()),
                row.yellowCards(), row.redCards(), zero(row.influence()), zero(row.creativity()),
                zero(row.threat()), zero(row.ictIndex()), row.value(), row.selected(), row.transfersIn(),
                row.transfersOut(), row.starts(), row.clearancesBlocksInterceptions(), row.recoveries(),
                row.tackles(), row.defensiveContribution(), timestamp(publishedAt),
                present(row, HistoryParser.Component.EXPECTED_GOALS),
                present(row, HistoryParser.Component.EXPECTED_ASSISTS),
                present(row, HistoryParser.Component.EXPECTED_GOAL_INVOLVEMENTS),
                present(row, HistoryParser.Component.EXPECTED_GOALS_CONCEDED),
                present(row, HistoryParser.Component.INFLUENCE),
                present(row, HistoryParser.Component.CREATIVITY),
                present(row, HistoryParser.Component.THREAT),
                present(row, HistoryParser.Component.ICT_INDEX),
                present(row, HistoryParser.Component.CLEARANCES_BLOCKS_INTERCEPTIONS),
                present(row, HistoryParser.Component.RECOVERIES),
                present(row, HistoryParser.Component.TACKLES),
                present(row, HistoryParser.Component.DEFENSIVE_CONTRIBUTION)}).toList());
    }

    private void writeEvidence(List<HistoryRow> rows, Collection<HistoryBatch> histories, long generation) {
        var reads = histories.stream().collect(java.util.stream.Collectors.toMap(HistoryBatch::playerId,
                HistoryBatch::sourceRead, (first, second) -> first));
        String sql = "INSERT INTO " + tables.playerFixtureEvidence()
                + " (season_id, season_code, fixture_id, player_id, generation, component_presence,"
                + " source_read_started_at, source_read_finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.batchUpdate(sql, rows.stream().map(row -> {
            ReadInterval read = reads.get(row.playerId());
            return new Object[]{row.seasonId(), row.seasonCode(), row.fixtureId(), row.playerId(), generation,
                    row.presenceMask(), timestamp(read.startedAt()), timestamp(read.completedAt())};
        }).toList());
    }

    private static SourceRead sourceRead(ReadInterval read) {
        return new SourceRead(read.startedAt(), read.completedAt());
    }

    private static SourceRead combined(ReadInterval fixture, ReadInterval live,
                                       Collection<HistoryBatch> histories) {
        Instant start = fixture.startedAt().isBefore(live.startedAt()) ? fixture.startedAt() : live.startedAt();
        Instant end = fixture.completedAt().isAfter(live.completedAt()) ? fixture.completedAt() : live.completedAt();
        for (HistoryBatch batch : histories) {
            if (batch.sourceRead().startedAt().isBefore(start)) start = batch.sourceRead().startedAt();
            if (batch.sourceRead().completedAt().isAfter(end)) end = batch.sourceRead().completedAt();
        }
        return new SourceRead(start, end);
    }

    private static SourceRead combined(ReadInterval bootstrap, ReadInterval fixtures,
                                       ReadInterval live, Collection<SourceDocument> histories) {
        Instant start = List.of(bootstrap, fixtures, live).stream().map(ReadInterval::startedAt)
                .min(Instant::compareTo).orElseThrow();
        Instant end = List.of(bootstrap, fixtures, live).stream().map(ReadInterval::completedAt)
                .max(Instant::compareTo).orElseThrow();
        for (SourceDocument history : histories) {
            if (history.readInterval().startedAt().isBefore(start)) start = history.readInterval().startedAt();
            if (history.readInterval().completedAt().isAfter(end)) end = history.readInterval().completedAt();
        }
        return new SourceRead(start, end);
    }


    private static boolean present(HistoryRow row, HistoryParser.Component component) {
        return (row.presenceMask() & component.bit()) != 0;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
