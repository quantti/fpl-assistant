package dev.quantti.fplassistant.sync;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.DailyPriceRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.TeamRow;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;

/**
 * Explicit, inert publication boundary for catalogue/price and schedule snapshots.
 *
 * <p>The two methods are separate fenced units because a failed schedule must not roll back a
 * usable catalogue capture (and conversely). Both keys are fixed: allowing callers to invent a
 * publication key would bypass ordering while both units update {@code team}. Source chronology
 * is fenced by the fixed per-season receipts below, never by core {@code updated_at}: that column
 * remains the actual owned publication time. At cutover there must still be one dispatcher and
 * every legacy core/capture writer must be drained first. The first owned publication must use a
 * newly validated source batch; legacy {@code updated_at} values cannot seed source chronology.
 * This class is deliberately not a Spring bean and performs no fetching or activation.
 */
public final class CatalogueScheduleWriter {

    public static final String CATALOGUE_PRICE_PUBLICATION_KEY = "catalogue-price";
    public static final String SCHEDULE_PUBLICATION_KEY = "schedule";
    static final String SHARED_TEAM_SOURCE_RECEIPT_KEY = "source:bootstrap-static";
    static final String FIXTURE_SOURCE_RECEIPT_KEY = "source:fixtures";

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final LocalTime PRICE_CUTOVER = LocalTime.of(2, 0);
    private static final LocalTime SCHEDULE_CUTOVER = LocalTime.of(3, 0);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnedSyncStore store;
    private final OwnedSyncTables ownedTables;
    private final CatalogueScheduleTables capturedTables;
    private final FixtureSyncRepository fixtureSync;

    public CatalogueScheduleWriter(JdbcTemplate jdbc, Clock clock, OwnedSyncStore store,
                                   OwnedSyncTables ownedTables,
                                   CatalogueScheduleTables capturedTables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.store = Objects.requireNonNull(store);
        this.ownedTables = Objects.requireNonNull(ownedTables);
        this.capturedTables = Objects.requireNonNull(capturedTables);
        this.fixtureSync = new FixtureSyncRepository(jdbc, ownedTables);
    }

    /** Parses the source documents again at the publication boundary, then atomically publishes. */
    public int publishCataloguePrice(Lease lease, Season season, SourceDocument bootstrap,
                                     SourceDocument fixtures) {
        return publishCataloguePrice(lease, season, bootstrap, fixtures, false, null);
    }

    int publishCataloguePriceForDemand(Lease lease, Season season, SourceDocument bootstrap,
                                       SourceDocument fixtures, OwnedSyncStore.CanonicalSourcePair sourcePair) {
        return publishCataloguePrice(lease, season, bootstrap, fixtures, true, sourcePair);
    }

    private int publishCataloguePrice(Lease lease, Season season, SourceDocument bootstrap,
                                      SourceDocument fixtures, boolean enforceDemandFloor,
                                      OwnedSyncStore.CanonicalSourcePair sourcePair) {
        requireLease(lease, season, CATALOGUE_PRICE_PUBLICATION_KEY);
        Snapshot snapshot = CatalogueScheduleParser.parse(season, bootstrap, fixtures);
        requirePublicationDay(snapshot, PRICE_CUTOVER, "catalogue-price");
        SourceRead priceRead = sourceRead(snapshot.bootstrapRead());
        SourceRead publicationRead = sourcePair == null ? priceRead
                : combinedRead(snapshot.bootstrapRead(), snapshot.fixturesRead());
        List<SourceRead> demandReads = enforceDemandFloor
                ? List.of(priceRead, sourceRead(snapshot.fixturesRead())) : List.of();
        return store.publish(lease, publicationRead, demandReads, sourcePair, () -> {
            lockAndValidateCurrentSeason(snapshot.season());
            recordSourceReceipt(lease, SHARED_TEAM_SOURCE_RECEIPT_KEY, snapshot.bootstrapRead());
            Instant publishedAt = clock.instant();
            int written = writeTeams(snapshot.teams(), publishedAt);
            written += writePlayers(snapshot.players(), publishedAt);
            written += writeDailyPrices(snapshot.dailyPrices(), priceRead.finishedAt());
            deleteAbsentDailyPrices(season.code(), date(priceRead.finishedAt()), snapshot.dailyPrices());
            return written;
        });
    }

    /** Parses one timestamp-coherent source batch, then publishes both schedule representations. */
    public int publishSchedule(Lease lease, Season season, SourceDocument bootstrap,
                               SourceDocument fixtures) {
        return publishSchedule(lease, season, bootstrap, fixtures, false, null);
    }

    int publishScheduleForDemand(Lease lease, Season season, SourceDocument bootstrap,
                                 SourceDocument fixtures, OwnedSyncStore.CanonicalSourcePair sourcePair) {
        return publishSchedule(lease, season, bootstrap, fixtures, true, sourcePair);
    }

    private int publishSchedule(Lease lease, Season season, SourceDocument bootstrap,
                                SourceDocument fixtures, boolean enforceDemandFloor,
                                OwnedSyncStore.CanonicalSourcePair sourcePair) {
        requireLease(lease, season, SCHEDULE_PUBLICATION_KEY);
        Snapshot snapshot = CatalogueScheduleParser.parse(season, bootstrap, fixtures);
        requirePublicationDay(snapshot, SCHEDULE_CUTOVER, "schedule");
        SourceRead read = combinedRead(snapshot.bootstrapRead(), snapshot.fixturesRead());
        List<SourceRead> demandReads = enforceDemandFloor
                ? List.of(sourceRead(snapshot.bootstrapRead()), sourceRead(snapshot.fixturesRead())) : List.of();
        return store.publish(lease, read, demandReads, sourcePair, () -> {
            lockAndValidateCurrentSeason(snapshot.season());
            recordSourceReceipt(lease, SHARED_TEAM_SOURCE_RECEIPT_KEY, snapshot.bootstrapRead());
            recordSourceReceipt(lease, FIXTURE_SOURCE_RECEIPT_KEY, snapshot.fixturesRead());
            Instant publishedAt = clock.instant();
            int written = writeTeams(snapshot.teams(), publishedAt);
            written += writeCoreGameweeks(snapshot.gameweeks(), publishedAt);
            Set<Integer> correctedFixtures = fixtureSync.invalidateCorrections(snapshot.fixtures(), publishedAt);
            correctedFixtures.stream().sorted().forEach(fixtureId -> {
                String key = FixtureSyncPublisher.publicationKey(fixtureId);
                store.enqueue(SyncKind.FIXTURE_VERIFICATION, season.code(), key, key, read.finishedAt());
            });
            written += writeCoreFixtures(snapshot.fixtures(), publishedAt);
            written += writeCapturedGameweeks(snapshot.gameweeks(), read.finishedAt());
            written += writeCapturedFixtures(snapshot.fixtures(), read.finishedAt());
            deleteAbsentSchedule(season.code(), snapshot.gameweeks(), snapshot.fixtures());
            return written;
        });
    }

    private void requireLease(Lease lease, Season season, String publicationKey) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(season, "season");
        if (lease.kind() != SyncKind.CATALOGUE_SCHEDULE
                || !publicationKey.equals(lease.publicationKey())
                || !season.code().equals(lease.season())) {
            throw new IllegalArgumentException("Lease kind, publication key and season must be canonical for "
                    + publicationKey);
        }
    }

    private void requirePublicationDay(Snapshot snapshot, LocalTime cutoff, String unit) {
        Instant now = clock.instant();
        LocalDate today = date(now);
        for (ReadInterval interval : List.of(snapshot.bootstrapRead(), snapshot.fixturesRead())) {
            if (!date(interval.startedAt()).equals(today) || !date(interval.completedAt()).equals(today)) {
                throw new IllegalArgumentException(unit + " source reads must belong to today's UK date");
            }
            if (interval.startedAt().isAfter(now) || interval.completedAt().isAfter(now)) {
                throw new IllegalArgumentException(unit + " source read timestamps cannot be in the future");
            }
        }
        if (now.atZone(UK).toLocalTime().isBefore(cutoff)) {
            throw new PublicationDeferredException(unit + " publication is deferred until " + cutoff + " UK");
        }
    }

    private void lockAndValidateCurrentSeason(Season season) {
        // Keep season registration stable, then serialize both publication keys on the same row.
        // The row lock must precede every shared team chronology check and write: per-key receipt
        // locks alone do not stop catalogue and schedule transactions from racing each other.
        jdbc.execute("LOCK TABLE season IN SHARE MODE");
        List<DatabaseSeason> configured = jdbc.query("""
                SELECT id, code, start_date, end_date FROM season WHERE id = ? FOR UPDATE
                """, (rs, row) -> databaseSeason(rs), season.id());
        if (configured.size() != 1) {
            throw new IllegalStateException("Configured season does not exist in the database");
        }
        List<Integer> current = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current = true", Integer.class);
        if (current.size() != 1) {
            throw new IllegalStateException("Expected exactly one database season with is_current=true");
        }
        DatabaseSeason db = configured.getFirst();
        if (current.getFirst() != db.id || !db.code.equals(season.code())
                || !db.start.equals(season.startsOn()) || !Objects.equals(db.end, season.endsOn())) {
            throw new IllegalStateException("Configured season does not exactly match the current database season");
        }
    }

    private static DatabaseSeason databaseSeason(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DatabaseSeason(rs.getInt("id"), rs.getString("code"),
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate());
    }

    private void recordSourceReceipt(Lease lease, String streamKey, ReadInterval interval) {
        if (!SHARED_TEAM_SOURCE_RECEIPT_KEY.equals(streamKey)
                && !FIXTURE_SOURCE_RECEIPT_KEY.equals(streamKey)) {
            throw new IllegalArgumentException("Source receipt key must be reserved");
        }
        jdbc.update("INSERT INTO %s(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
                .formatted(ownedTables.receipt()), lease.season(), streamKey);
        Timestamp previous = jdbc.queryForObject("""
                SELECT source_read_finished_at FROM %s
                WHERE season = ? AND publication_key = ? FOR UPDATE
                """.formatted(ownedTables.receipt()), Timestamp.class, lease.season(), streamKey);
        if (previous != null && previous.toInstant().isAfter(interval.completedAt())) {
            throw new SourceRegressionException(streamKey + " completion predates its source receipt");
        }
        Instant recordedAt = clock.instant();
        jdbc.update("""
                UPDATE %s SET latest_generation = GREATEST(COALESCE(latest_generation, ?), ?),
                    source_read_started_at = ?, source_read_finished_at = ?, published_at = ?
                WHERE season = ? AND publication_key = ?
                """.formatted(ownedTables.receipt()), lease.generation(), lease.generation(),
                timestamp(interval.startedAt()), timestamp(interval.completedAt()), timestamp(recordedAt),
                lease.season(), streamKey);
    }

    private int writeTeams(List<TeamRow> rows, Instant publicationTime) {
        return CatalogueCompatibilityRows.writeTeams(jdbc, rows, publicationTime, false);
    }

    private int writePlayers(List<PlayerRow> rows, Instant publicationTime) {
        return CatalogueCompatibilityRows.writePlayers(jdbc, rows, publicationTime, false);
    }

    private int writeDailyPrices(List<DailyPriceRow> rows, Instant sourceTime) {
        String sql = """
                INSERT INTO %s (season, price_date, element_id, web_name, full_name, team_id,
                    team_short, team_name, element_type, now_cost, cost_change_event, cost_change_start,
                    selected_by_percent, status, news, penalties_order, direct_fk_order, corners_order,
                    captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, price_date, element_id) DO UPDATE SET web_name=EXCLUDED.web_name,
                    full_name=EXCLUDED.full_name, team_id=EXCLUDED.team_id,
                    team_short=EXCLUDED.team_short, team_name=EXCLUDED.team_name,
                    element_type=EXCLUDED.element_type, now_cost=EXCLUDED.now_cost,
                    cost_change_event=EXCLUDED.cost_change_event, cost_change_start=EXCLUDED.cost_change_start,
                    selected_by_percent=EXCLUDED.selected_by_percent, status=EXCLUDED.status,
                    news=EXCLUDED.news, penalties_order=EXCLUDED.penalties_order,
                    direct_fk_order=EXCLUDED.direct_fk_order, corners_order=EXCLUDED.corners_order,
                    captured_at=EXCLUDED.captured_at
                """.formatted(capturedTables.dailyPrice());
        return batch(sql, rows.stream().map(row -> new Object[]{row.seasonCode(),
                java.sql.Date.valueOf(row.priceDate()), row.elementId(), row.webName(), row.fullName(),
                row.teamId(), row.teamShort(), row.teamName(), row.elementType(), row.nowCost(),
                field(row.fields(), "cost_change_event"), field(row.fields(), "cost_change_start"),
                field(row.fields(), "selected_by_percent"), field(row.fields(), "status"),
                field(row.fields(), "news"), field(row.fields(), "penalties_order"),
                field(row.fields(), "direct_fk_order"), field(row.fields(), "corners_order"),
                timestamp(sourceTime)}).toList());
    }

    private int writeCoreGameweeks(List<GameweekRow> rows, Instant publicationTime) {
        return CatalogueCompatibilityRows.writeGameweeks(jdbc, rows, publicationTime, false);
    }

    private int writeCoreFixtures(List<FixtureRow> rows, Instant publicationTime) {
        return CatalogueCompatibilityRows.writeFixtures(jdbc, rows, publicationTime, false).size();
    }

    private int writeCapturedGameweeks(List<GameweekRow> rows, Instant sourceTime) {
        String sql = """
                INSERT INTO %s (season, id, name, deadline_time, finished, data_checked, is_current,
                    is_next, average_entry_score, highest_score, most_captained,
                    most_transferred_in, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, id) DO UPDATE SET name=EXCLUDED.name,
                    deadline_time=EXCLUDED.deadline_time, finished=EXCLUDED.finished,
                    data_checked=EXCLUDED.data_checked, is_current=EXCLUDED.is_current,
                    is_next=EXCLUDED.is_next, average_entry_score=EXCLUDED.average_entry_score,
                    highest_score=EXCLUDED.highest_score, most_captained=EXCLUDED.most_captained,
                    most_transferred_in=EXCLUDED.most_transferred_in, captured_at=EXCLUDED.captured_at
                """.formatted(capturedTables.gameweek());
        return batch(sql, rows.stream().map(row -> new Object[]{row.seasonCode(), row.id(), row.name(),
                timestamp(row.deadline()), row.finished(), row.dataChecked(), row.current(), row.next(),
                field(row.fields(), "average_entry_score"), field(row.fields(), "highest_score"),
                field(row.fields(), "most_captained"), field(row.fields(), "most_transferred_in"),
                timestamp(sourceTime)}).toList());
    }

    private int writeCapturedFixtures(List<FixtureRow> rows, Instant sourceTime) {
        String sql = """
                INSERT INTO %s (season, id, gameweek, team_h, team_a, team_h_name, team_h_short,
                    team_a_name, team_a_short, team_h_difficulty, team_a_difficulty, kickoff_time,
                    started, finished, team_h_score, team_a_score, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, id) DO UPDATE SET gameweek=EXCLUDED.gameweek,
                    team_h=EXCLUDED.team_h, team_a=EXCLUDED.team_a,
                    team_h_name=EXCLUDED.team_h_name, team_h_short=EXCLUDED.team_h_short,
                    team_a_name=EXCLUDED.team_a_name, team_a_short=EXCLUDED.team_a_short,
                    team_h_difficulty=EXCLUDED.team_h_difficulty,
                    team_a_difficulty=EXCLUDED.team_a_difficulty, kickoff_time=EXCLUDED.kickoff_time,
                    started=EXCLUDED.started, finished=EXCLUDED.finished,
                    team_h_score=EXCLUDED.team_h_score, team_a_score=EXCLUDED.team_a_score,
                    captured_at=EXCLUDED.captured_at
                """.formatted(capturedTables.fixture());
        return batch(sql, rows.stream().map(row -> new Object[]{row.seasonCode(), row.id(), row.gameweek(),
                row.homeTeamId(), row.awayTeamId(), row.homeTeamName(), row.homeTeamShort(),
                row.awayTeamName(), row.awayTeamShort(), row.homeDifficulty(), row.awayDifficulty(),
                timestamp(row.kickoff()), row.started(), row.finished(), row.homeScore(), row.awayScore(),
                timestamp(sourceTime)}).toList());
    }

    private void deleteAbsentDailyPrices(String season, LocalDate day, List<DailyPriceRow> rows) {
        deleteAbsent(capturedTables.dailyPrice(), "season = ? AND price_date = ?", new Object[]{season,
                java.sql.Date.valueOf(day)}, "element_id", rows.stream().map(DailyPriceRow::elementId).toArray(Integer[]::new));
    }

    private void deleteAbsentSchedule(String season, List<GameweekRow> gameweeks, List<FixtureRow> fixtures) {
        deleteAbsent(capturedTables.fixture(), "season = ?", new Object[]{season}, "id",
                fixtures.stream().map(FixtureRow::id).toArray(Integer[]::new));
        deleteAbsent(capturedTables.gameweek(), "season = ?", new Object[]{season}, "id",
                gameweeks.stream().map(GameweekRow::id).toArray(Integer[]::new));
    }

    private void deleteAbsent(String table, String predicate, Object[] prefix, String idColumn, Integer[] ids) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE " + predicate
                            + " AND NOT (" + idColumn + " = ANY(?))");
            int index = 1;
            for (Object value : prefix) statement.setObject(index++, value);
            Array array = connection.createArrayOf("integer", ids);
            statement.setArray(index, array);
            return statement;
        });
    }

    private int batch(String sql, List<Object[]> rows) {
        int count = 0;
        for (int value : jdbc.batchUpdate(sql, rows)) count += Math.max(0, value);
        return count;
    }

    private static Object field(Map<String, Object> fields, String name) {
        return fields.get(name);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static LocalDate date(Instant value) {
        return LocalDate.ofInstant(value, UK);
    }

    private static SourceRead sourceRead(ReadInterval interval) {
        return new SourceRead(interval.startedAt(), interval.completedAt());
    }

    private static SourceRead combinedRead(ReadInterval first, ReadInterval second) {
        Instant started = first.startedAt().isBefore(second.startedAt()) ? first.startedAt() : second.startedAt();
        Instant finished = first.completedAt().isAfter(second.completedAt())
                ? first.completedAt() : second.completedAt();
        return new SourceRead(started, finished);
    }

    private record DatabaseSeason(int id, String code, LocalDate start, LocalDate end) { }

    public static final class PublicationDeferredException extends IllegalStateException {
        public PublicationDeferredException(String message) { super(message); }
    }

    public static final class SourceRegressionException extends IllegalStateException {
        public SourceRegressionException(String message) { super(message); }
    }
}
