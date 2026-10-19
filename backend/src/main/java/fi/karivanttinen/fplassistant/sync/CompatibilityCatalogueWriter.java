package fi.karivanttinen.fplassistant.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.CanonicalSourcePair;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.IncrementalDiscovery;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;

/** Explicit compatibility-table writer for a validated incremental catalogue/schedule snapshot. */
public final class CompatibilityCatalogueWriter {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnedSyncStore store;
    private final FixtureSyncRepository fixtures;
    private final CatalogueScheduleTables capturedTables;

    public CompatibilityCatalogueWriter(JdbcTemplate jdbc, Clock clock, OwnedSyncStore store,
                                        OwnedSyncTables tables, CatalogueScheduleTables capturedTables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.store = Objects.requireNonNull(store);
        this.fixtures = new FixtureSyncRepository(jdbc, Objects.requireNonNull(tables));
        this.capturedTables = Objects.requireNonNull(capturedTables);
    }

    Publication publish(Lease lease, Season season, SourceDocument bootstrap, SourceDocument fixtureDocument) {
        Snapshot snapshot = CatalogueScheduleParser.parse(season, bootstrap, fixtureDocument);
        CanonicalSourcePair sourcePair = store.incrementalSourcePair(lease, bootstrap, fixtureDocument);
        SourceRead sourceRead = combined(snapshot.bootstrapRead(), snapshot.fixturesRead());
        return store.publishIncrementalCompatibility(lease, season, sourcePair, sourceRead, () -> {
            fixtures.lockAndValidatePlayingSeason(season);
            Instant publishedAt = clock.instant();
            int teamRows = CatalogueCompatibilityRows.writeTeams(
                    jdbc, snapshot.teams(), publishedAt, true);
            int playerRows = CatalogueCompatibilityRows.writePlayers(
                    jdbc, snapshot.players(), publishedAt, true);
            int gameweekRows = CatalogueCompatibilityRows.writeGameweeks(
                    jdbc, snapshot.gameweeks(), publishedAt, true);

            Map<Integer, ExistingFixtureEvidence> existingFixtures = existingFixtureEvidence(
                    season, snapshot.fixtures());
            Set<Integer> invalidatedFixtureIds = fixtures.invalidateCorrections(snapshot.fixtures(), publishedAt);
            List<Integer> writtenFixtureIds = CatalogueCompatibilityRows.writeFixtures(
                    jdbc, snapshot.fixtures(), publishedAt, true);
            Map<Integer, FixtureRow> sourceFixtures = new HashMap<>();
            snapshot.fixtures().forEach(fixture -> sourceFixtures.put(fixture.id(), fixture));
            List<Integer> changedFixtureIds = writtenFixtureIds.stream()
                    .filter(existingFixtures::containsKey)
                    .filter(id -> evidenceChanged(existingFixtures.get(id), sourceFixtures.get(id)))
                    .sorted().toList();
            CatalogueScheduleWriter.writeCapturedGameweeks(jdbc, capturedTables,
                    snapshot.gameweeks(), sourceRead.finishedAt());
            CatalogueScheduleWriter.writeCapturedFixtures(jdbc, capturedTables,
                    snapshot.fixtures(), sourceRead.finishedAt());
            CatalogueScheduleWriter.deleteAbsentSchedule(jdbc, capturedTables, season.code(),
                    snapshot.gameweeks(), snapshot.fixtures());
            IncrementalDiscovery discovery = store.observeIncrementalSnapshot(lease, snapshot);
            return new Publication(teamRows, playerRows, gameweekRows, writtenFixtureIds.size(),
                    changedFixtureIds, invalidatedFixtureIds, discovery);
        });
    }

    private Map<Integer, ExistingFixtureEvidence> existingFixtureEvidence(Season season,
            List<FixtureRow> fixtures) {
        String ids = String.join(", ", java.util.Collections.nCopies(fixtures.size(), "?"));
        String sql = """
                SELECT id, code, gameweek, team_h, team_a, kickoff_time, started, finished,
                       finished_provisional, minutes, team_h_score, team_a_score, stats::text
                FROM fixture WHERE season_id=? AND id IN (%s)
                """.formatted(ids);
        List<Object> parameters = new ArrayList<>(fixtures.size() + 1);
        parameters.add(season.id());
        fixtures.stream().map(FixtureRow::id).forEach(parameters::add);
        Map<Integer, ExistingFixtureEvidence> existing = new HashMap<>();
        jdbc.query(sql, rs -> {
            int id = rs.getInt("id");
            String stats = rs.getString("stats");
            String statsFingerprint = stats == null ? null : FixtureStatsFingerprint.of(stats);
            existing.put(id, new ExistingFixtureEvidence(
                    FixtureVerification.verifiedHeader(season.id(), season.code(), id, rs.getInt("code"),
                            rs.getObject("gameweek", Integer.class), rs.getInt("team_h"), rs.getInt("team_a"),
                            rs.getTimestamp("kickoff_time") == null ? null : rs.getTimestamp("kickoff_time").toInstant(),
                            rs.getObject("started", Boolean.class), rs.getObject("finished", Boolean.class),
                            rs.getObject("finished_provisional", Boolean.class),
                            rs.getObject("minutes", Integer.class), rs.getObject("team_h_score", Integer.class),
                            rs.getObject("team_a_score", Integer.class)), statsFingerprint));
        }, parameters.toArray());
        return existing;
    }

    private static boolean evidenceChanged(ExistingFixtureEvidence existing, FixtureRow incoming) {
        return !existing.header().equals(FixtureVerification.verifiedHeader(incoming))
                || !Objects.equals(existing.statsFingerprint(), FixtureStatsFingerprint.of(incoming));
    }

    private static SourceRead combined(ReadInterval first, ReadInterval second) {
        Instant started = first.startedAt().isBefore(second.startedAt())
                ? first.startedAt() : second.startedAt();
        Instant finished = first.completedAt().isAfter(second.completedAt())
                ? first.completedAt() : second.completedAt();
        return new SourceRead(started, finished);
    }

    private record ExistingFixtureEvidence(String header, String statsFingerprint) { }

    record Publication(int teamRows, int playerRows, int gameweekRows, int fixtureRows,
                       List<Integer> changedFixtureIds, Set<Integer> invalidatedFixtureIds,
                       IncrementalDiscovery discovery) {
        Publication {
            changedFixtureIds = List.copyOf(changedFixtureIds);
            invalidatedFixtureIds = Set.copyOf(invalidatedFixtureIds);
            Objects.requireNonNull(discovery);
        }

        int writtenRows() {
            return teamRows + playerRows + gameweekRows + fixtureRows;
        }
    }
}
