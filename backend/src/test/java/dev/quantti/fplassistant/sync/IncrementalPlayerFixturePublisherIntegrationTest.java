package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.defcon.DefensiveStatsSchema;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.CanonicalFixture;
import dev.quantti.fplassistant.sync.HistoryParser.Component;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.tools.PfsReadSource;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

class IncrementalPlayerFixturePublisherIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_inc_run_test", "fpl_inc_payload_test", "fpl_inc_receipt_test");
    private static final int SEASON_ID = 939_926;
    private static final int FIXTURE_HOME = 96_901;
    private static final int FIXTURE_TRANSFER = 96_902;
    private static final int PLAYER = 96_911;
    private static final int ZERO_PLAYER = 96_912;
    private static final int MISSING_PLAYER = 96_913;
    private static final int NEW_ONLY_PLAYER = 96_914;
    private static final String SEASON_CODE = "2031-32";
    private static final Season SEASON = new Season(SEASON_ID, SEASON_CODE,
            LocalDate.parse("2031-08-01"), LocalDate.parse("2032-05-30"));
    private static final Instant BASE = Instant.parse("2031-09-01T10:00:00Z");
    private static final Instant FIXED_CREATED_AT = Instant.parse("2001-01-01T00:00:00Z");
    private static final List<CanonicalFixture> FIXTURES = List.of(
            new CanonicalFixture(SEASON_ID, SEASON_CODE, FIXTURE_HOME, 1,
                    97_001, 97_002, Instant.parse("2031-08-20T14:00:00Z")),
            new CanonicalFixture(SEASON_ID, SEASON_CODE, FIXTURE_TRANSFER, 2,
                    97_003, 97_004, Instant.parse("2031-08-27T14:00:00Z")));

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static List<Integer> previousCurrentSeasons;
    private static boolean testSeasonCreated;
    private static boolean disposableDatabaseValidated;
    private static boolean seasonBaselineCaptured;
    private static boolean seasonFlagsChanged;
    private static boolean cleanupAuthorized;
    private static MutableClock clock;
    private OwnedSyncStore store;
    private IncrementalPlayerFixturePublisher publisher;

    @BeforeAll
    static void initialize() {
        String url = requiredEnv("DATABASE_URL");
        String user = requiredEnv("DB_USER");
        String password = requiredEnv("DB_PASSWORD");
        initialize(url, user, password,
                () -> new JdbcTemplate(new DriverManagerDataSource(url, user, password)));
    }

    static void initialize(String url, String user, String password, Supplier<JdbcTemplate> jdbcSupplier) {
        jdbc = SharedStatsSeed.openDisposableTestJdbc(url, user, password, jdbcSupplier);
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        SharedStatsSeed.ensure(jdbc);
        disposableDatabaseValidated = true;
        previousCurrentSeasons = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true ORDER BY id", Integer.class);
        seasonBaselineCaptured = true;
        testSeasonCreated = jdbc.update("INSERT INTO season (id, code, name, start_date, end_date, is_current) "
                        + "VALUES (?, ?, 'Incremental PFS test', DATE '2031-08-01', DATE '2032-05-30', false) "
                        + "ON CONFLICT (id) DO NOTHING", SEASON_ID, SEASON_CODE) == 1;
        if (!SEASON_CODE.equals(jdbc.queryForObject("SELECT code FROM season WHERE id=?",
                String.class, SEASON_ID))) {
            throw new IllegalStateException("Incremental PFS test season id is already in use");
        }
        tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM season ORDER BY id FOR UPDATE", Integer.class);
            jdbc.update("UPDATE season SET is_current=false");
            jdbc.update("UPDATE season SET is_current=true WHERE id=?", SEASON_ID);
        });
        seasonFlagsChanged = true;
        new DefensiveStatsSchema(jdbc).ensureColumns();
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        cleanupAuthorized = true;
    }

    @BeforeEach
    void resetIsolatedRows() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", "
                + TABLES.playerFixtureEvidence() + ", " + TABLES.gameweekStatSync() + ", "
                + TABLES.fixtureSync() + ", " + TABLES.payload() + ", " + TABLES.run() + ", "
                + TABLES.receipt() + ", " + TABLES.plannerSlot() + ", " + TABLES.plannerRuntime()
                + " CASCADE");
        jdbc.update("DELETE FROM player_fixture_stats WHERE fixture_id IN (?, ?) AND season_id IN (?, ?)",
                FIXTURE_HOME, FIXTURE_TRANSFER, SEASON_ID, SharedStatsSeed.POPULATED_SEASON_ID);
        clock = new MutableClock(BASE);
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                + "(instance_id, started_at, heartbeat_at, runtime_mode, runtime_epoch, activation_boundary)"
                + " VALUES (?, ?, ?, 'ACTIVE', 1, ?)", "mode:" + SEASON_CODE,
                java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE));
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES,
                new OwnedSyncStore.RuntimeFence(SEASON_CODE, OwnedSyncMode.ACTIVE, 1, BASE));
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, store, TABLES));
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc == null || !disposableDatabaseValidated || !seasonBaselineCaptured) return;
        if (cleanupAuthorized) {
            jdbc.update("DELETE FROM player_fixture_stats WHERE fixture_id IN (?, ?) AND season_id IN (?, ?)",
                    FIXTURE_HOME, FIXTURE_TRANSFER, SEASON_ID, SharedStatsSeed.POPULATED_SEASON_ID);
            jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", "
                + TABLES.playerFixtureEvidence() + ", " + TABLES.gameweekStatSync() + ", "
                + TABLES.fixtureSync() + ", " + TABLES.payload() + ", " + TABLES.run() + ", "
                + TABLES.receipt() + ", " + TABLES.plannerSlot() + ", " + TABLES.plannerRuntime()
                + " CASCADE");
        }
        if (!seasonFlagsChanged) return;
        tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM season ORDER BY id FOR UPDATE", Integer.class);
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrentSeasons.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
            if (cleanupAuthorized && testSeasonCreated) jdbc.update("DELETE FROM season WHERE id=?", SEASON_ID);
        });
        seasonFlagsChanged = false;
    }

    @Test
    void changedOnlyWritesTrackOptionalPresenceAndLeaveUnchangedPfsAgeAlone() throws Exception {
        SourceDocument firstPage = page(PLAYER, BASE, BASE.plusSeconds(1),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.40"));
        var first = publish(PLAYER, "first", firstPage);
        assertThat(first.pfsRowsWritten()).isEqualTo(1);
        assertThat(first.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER));
        assertThat(pfsDecimal(PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo("0.40");
        assertThat(hasComponent(PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isTrue();
        assertThat(runState(firstLeaseGeneration(PLAYER, "first"))).isEqualTo("LEASED");
        jdbc.update("UPDATE player_fixture_stats SET created_at=? WHERE season_id=? AND fixture_id=? AND player_id=?",
                java.sql.Timestamp.from(FIXED_CREATED_AT), SEASON_ID, FIXTURE_HOME, PLAYER);
        TimestampValue originalAge = pfsAge(PLAYER, FIXTURE_HOME);

        SourceDocument samePage = page(PLAYER, BASE.plusSeconds(2), BASE.plusSeconds(3),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.4"));
        var same = publish(PLAYER, "same", samePage);
        assertThat(same.pfsRowsWritten()).isZero();
        assertThat(same.changedKeys()).isEmpty();
        assertThat(same.evidenceRowsWritten()).isZero();
        assertThat(currentSourceGeneration(PLAYER, FIXTURE_HOME)).isEqualTo(
                firstLeaseGeneration(PLAYER, "first"));
        assertThat(pfsAge(PLAYER, FIXTURE_HOME)).isEqualTo(originalAge);

        SourceDocument missingOptional = page(PLAYER, BASE.plusSeconds(4), BASE.plusSeconds(5),
                row(PLAYER, FIXTURE_HOME, 2, true, null));
        var presenceOnly = publish(PLAYER, "presence-only", missingOptional);
        assertThat(presenceOnly.pfsRowsWritten()).isZero();
        assertThat(presenceOnly.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER));
        assertThat(pfsDecimal(PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo("0.40");
        assertThat(hasComponent(PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isFalse();
        assertThat(pfsAge(PLAYER, FIXTURE_HOME)).isEqualTo(originalAge);

        SourceDocument trueZeroPage = page(ZERO_PLAYER, BASE.plusSeconds(6), BASE.plusSeconds(7),
                row(ZERO_PLAYER, FIXTURE_HOME, 0, true, "0.00"));
        var trueZero = publish(ZERO_PLAYER, "zero", trueZeroPage);
        assertThat(trueZero.pfsRowsWritten()).isEqualTo(1);
        assertThat(pfsDecimal(ZERO_PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(hasComponent(ZERO_PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isTrue();

        ObjectNode changed = row(PLAYER, FIXTURE_HOME, 8, true, null);
        SourceDocument changedPage = page(PLAYER, BASE.plusSeconds(8), BASE.plusSeconds(9), changed);
        var corrected = publish(PLAYER, "changed", changedPage);
        assertThat(corrected.pfsRowsWritten()).isEqualTo(1);
        assertThat(corrected.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER));
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "total_points")).isEqualTo(8);
        assertThat(pfsDecimal(PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo("0.40");
        assertThat(hasComponent(PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isFalse();
        assertThat(pfsCreatedAt(PLAYER, FIXTURE_HOME)).isEqualTo(FIXED_CREATED_AT);

        SourceDocument missingPage = page(MISSING_PLAYER, BASE.plusSeconds(10), BASE.plusSeconds(11),
                row(MISSING_PLAYER, FIXTURE_HOME, 0, true, null));
        var missing = publish(MISSING_PLAYER, "missing", missingPage);
        assertThat(missing.pfsRowsWritten()).isEqualTo(1);
        assertThat(pfsDecimal(MISSING_PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(hasComponent(MISSING_PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isFalse();
    }

    @Test
    void unchangedNewerPageAdvancesWatermarkWithoutRewritingRowEvidenceOrPointer() throws Exception {
        SourceDocument firstPage = page(PLAYER, BASE, BASE.plusSeconds(1),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.40"));
        Lease firstLease = newLease(PLAYER, "unchanged-watermark-first");
        clock.set(firstPage.readInterval().completedAt());
        assertThat(publisher.publish(firstLease, SEASON, PLAYER, firstPage, FIXTURES).pfsRowsWritten()).isEqualTo(1);

        PfsReadSource off = oldOffReader();
        PfsReadSource active = new PfsReadSource(jdbc, "ACTIVE", SEASON_CODE, TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
        assertThat(oldReaderRows(off, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));
        assertThat(oldReaderRows(active, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));
        long originalPointerGeneration = currentSourceGeneration(PLAYER, FIXTURE_HOME);
        SourceInterval originalPointerInterval = currentSourceInterval(PLAYER, FIXTURE_HOME);
        Instant originalPointerUpdatedAt = currentPointerUpdatedAt(PLAYER, FIXTURE_HOME);
        TimestampValue originalPfsAge = pfsAge(PLAYER, FIXTURE_HOME);
        int originalEvidenceCount = playerEvidenceCount(PLAYER);

        SourceDocument laterSamePage = page(PLAYER, BASE.plusSeconds(2), BASE.plusSeconds(3),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.4"));
        Lease laterLease = newLease(PLAYER, "unchanged-watermark-later");
        clock.set(laterSamePage.readInterval().completedAt());
        var later = publisher.publish(laterLease, SEASON, PLAYER, laterSamePage, FIXTURES);
        assertThat(later.pfsRowsWritten()).isZero();
        assertThat(later.evidenceRowsWritten()).isZero();
        assertThat(later.currentPointersUpdated()).isZero();
        assertThat(later.changedKeys()).isEmpty();
        assertThat(currentSourceGeneration(PLAYER, FIXTURE_HOME)).isEqualTo(originalPointerGeneration);
        assertThat(currentSourceInterval(PLAYER, FIXTURE_HOME)).isEqualTo(originalPointerInterval);
        assertThat(currentPointerUpdatedAt(PLAYER, FIXTURE_HOME)).isEqualTo(originalPointerUpdatedAt);
        assertThat(pfsAge(PLAYER, FIXTURE_HOME)).isEqualTo(originalPfsAge);
        assertThat(playerEvidenceCount(PLAYER)).isEqualTo(originalEvidenceCount);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                IncrementalPlayerFixturePublisher.pageReceiptKey(PLAYER))).isEqualTo(laterLease.generation());
        assertThat(oldReaderRows(off, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));
        assertThat(oldReaderRows(active, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));

        var sameWatermark = publisher.publish(laterLease, SEASON, PLAYER, laterSamePage, FIXTURES);
        assertThat(sameWatermark.pfsRowsWritten()).isZero();
        assertThat(sameWatermark.evidenceRowsWritten()).isZero();
        assertThat(sameWatermark.currentPointersUpdated()).isZero();
        assertThat(oldReaderRows(off, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));
        assertThat(oldReaderRows(active, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.40"));
    }

    @Test
    void firstEmptyPageWithdrawsLegacyVerifiedPlayerRowsAndPreservesOldEvidenceAndReceipt() throws Exception {
        Lease oldReceiptLease = newLease(PLAYER, "legacy-empty-baseline");
        seedLegacyPfs(PLAYER, FIXTURE_HOME, 1, "0.90");
        seedOldVerifiedFixture(PLAYER, FIXTURE_HOME, oldReceiptLease.generation());
        seedLegacyPfs(PLAYER, FIXTURE_TRANSFER, 2, "0.75");
        Instant oldReadStarted = BASE.minusSeconds(10);
        Instant oldReadFinished = BASE.minusSeconds(9);
        Instant oldPublishedAt = BASE.minusSeconds(8);
        jdbc.update("INSERT INTO " + TABLES.receipt()
                        + "(season, publication_key, latest_generation, source_read_started_at,"
                        + " source_read_finished_at, published_at) VALUES (?, ?, ?, ?, ?, ?)",
                SEASON_CODE, FixtureSyncPublisher.publicationKey(FIXTURE_HOME), oldReceiptLease.generation(),
                java.sql.Timestamp.from(oldReadStarted), java.sql.Timestamp.from(oldReadFinished),
                java.sql.Timestamp.from(oldPublishedAt));
        PfsReadSource off = oldOffReader();
        PfsReadSource active = new PfsReadSource(jdbc, "ACTIVE", SEASON_CODE, TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
        assertThat(oldReaderRows(off, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.90"));
        assertThat(oldReaderRows(active, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.90"));

        SourceDocument emptyPage = page(PLAYER, BASE.plusSeconds(1), BASE.plusSeconds(2));
        Lease emptyLease = newLease(PLAYER, "legacy-empty-reconcile");
        clock.set(emptyPage.readInterval().completedAt());
        var result = publisher.publish(emptyLease, SEASON, PLAYER, emptyPage, FIXTURES);

        assertThat(result.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER));
        assertThat(result.pfsRowsWritten()).isZero();
        assertThat(currentState(PLAYER, FIXTURE_HOME)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, FIXTURE_TRANSFER, PLAYER)).isZero();
        assertThat(pfsDecimal(PLAYER, FIXTURE_TRANSFER, "expected_goals")).isEqualByComparingTo("0.75");
        assertThat(currentSourceGeneration(PLAYER, FIXTURE_HOME)).isEqualTo(emptyLease.generation());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", String.class, SEASON_ID, FIXTURE_HOME))
                .isEqualTo("WAITING_STATS");
        assertThat(jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON_ID, FIXTURE_HOME)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON_ID, FIXTURE_HOME))
                .isEqualTo(oldReceiptLease.generation());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, FIXTURE_HOME, PLAYER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                FixtureSyncPublisher.publicationKey(FIXTURE_HOME))).isEqualTo(oldReceiptLease.generation());
        assertThat(jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON_CODE,
                FixtureSyncPublisher.publicationKey(FIXTURE_HOME)).toInstant()).isEqualTo(oldReadStarted);
        assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON_CODE,
                FixtureSyncPublisher.publicationKey(FIXTURE_HOME)).toInstant()).isEqualTo(oldReadFinished);
        assertThat(jdbc.queryForObject("SELECT published_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON_CODE,
                FixtureSyncPublisher.publicationKey(FIXTURE_HOME)).toInstant()).isEqualTo(oldPublishedAt);
        assertThat(oldReaderRows(off, FIXTURE_HOME, PLAYER)).isEmpty();
        assertThat(oldReaderRows(active, FIXTURE_HOME, PLAYER)).isEmpty();
    }

    @Test
    void offReaderUsesCurrentRowsAndMasksMissingComponentsAndWithdrawals() throws Exception {
        Lease oldReceiptLease = newLease(PLAYER, "old-verified-receipt");
        seedLegacyPfs(PLAYER, FIXTURE_HOME, 1, "0.90");
        seedOldVerifiedFixture(PLAYER, FIXTURE_HOME, oldReceiptLease.generation());
        PfsReadSource offReader = oldOffReader();
        PfsReadSource activeReader = new PfsReadSource(jdbc, "ACTIVE", SEASON_CODE, TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
        assertThat(oldReaderRows(offReader, FIXTURE_HOME, PLAYER)).containsExactly(new BigDecimal("0.90"));

        SourceDocument firstPage = page(PLAYER, BASE.plusSeconds(2), BASE.plusSeconds(3),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.40"));
        publish(PLAYER, "replace-verified", firstPage);
        assertThat(jdbc.queryForObject("SELECT current_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON_ID, FIXTURE_HOME)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_verified_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Long.class, SEASON_ID, FIXTURE_HOME))
                .isEqualTo(oldReceiptLease.generation());
        assertThat(oldReaderRows(offReader, FIXTURE_HOME, PLAYER))
                .containsExactly(new BigDecimal("0.40"));
        assertThat(oldReaderRows(activeReader, FIXTURE_HOME, PLAYER))
                .containsExactly(new BigDecimal("0.40"));

        SourceDocument presenceOnlyPage = page(PLAYER, BASE.plusSeconds(4), BASE.plusSeconds(5),
                row(PLAYER, FIXTURE_HOME, 2, true, null));
        publish(PLAYER, "presence-only-off-reader", presenceOnlyPage);
        assertThat(pfsDecimal(PLAYER, FIXTURE_HOME, "expected_goals")).isEqualByComparingTo("0.40");
        assertThat(hasComponent(PLAYER, FIXTURE_HOME, Component.EXPECTED_GOALS)).isFalse();
        assertThat(oldReaderRows(offReader, FIXTURE_HOME, PLAYER)).containsExactly((BigDecimal) null);
        assertThat(oldReaderRows(activeReader, FIXTURE_HOME, PLAYER)).containsExactly((BigDecimal) null);

        seedLegacyPfs(NEW_ONLY_PLAYER, FIXTURE_TRANSFER, 2, "0.75");
        assertThat(oldReaderRows(offReader, FIXTURE_TRANSFER, NEW_ONLY_PLAYER))
                .containsExactly(new BigDecimal("0.75"));
        SourceDocument newOnlyPage = page(NEW_ONLY_PLAYER, BASE.plusSeconds(6), BASE.plusSeconds(7),
                row(NEW_ONLY_PLAYER, FIXTURE_TRANSFER, 3, false, "0.80"));
        publish(NEW_ONLY_PLAYER, "new-only-fixture", newOnlyPage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=?", Integer.class, SEASON_ID, FIXTURE_TRANSFER)).isZero();
        assertThat(oldReaderRows(offReader, FIXTURE_TRANSFER, NEW_ONLY_PLAYER))
                .containsExactly(new BigDecimal("0.80"));
        assertThat(oldReaderRows(activeReader, FIXTURE_TRANSFER, NEW_ONLY_PLAYER))
                .containsExactly(new BigDecimal("0.80"));

        SourceDocument withdrawal = page(PLAYER, BASE.plusSeconds(8), BASE.plusSeconds(9));
        publish(PLAYER, "withdrawal-off-reader", withdrawal);
        assertThat(currentState(PLAYER, FIXTURE_HOME)).isEqualTo("WITHDRAWN");
        assertThat(playerPfsCount(PLAYER)).isEqualTo(1);
        assertThat(oldReaderRows(offReader, FIXTURE_HOME, PLAYER)).isEmpty();
        assertThat(oldReaderRows(activeReader, FIXTURE_HOME, PLAYER)).isEmpty();
    }

    @Test
    void pagePublicationRequiresActiveRuntimeAndLockedDatabasePlayingSeason() throws Exception {
        SourceDocument sourcePage = page(PLAYER, BASE, BASE.plusSeconds(1),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.10"));

        OwnedSyncStore offStore = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        Lease offLease = newLease(offStore, PLAYER, "off-mode");
        var offPublisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, offStore, TABLES));
        assertThatThrownBy(() -> offPublisher.publish(offLease, SEASON, PLAYER, sourcePage, FIXTURES))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
        assertThat(playerPfsCount(PLAYER)).isZero();

        setRuntimeMode(OwnedSyncMode.STAGING, 2);
        OwnedSyncStore stagingStore = new OwnedSyncStore(jdbc, tx, clock, TABLES,
                new OwnedSyncStore.RuntimeFence(SEASON_CODE, OwnedSyncMode.STAGING, 2, BASE));
        Lease stagingLease = newLease(stagingStore, PLAYER, "staging-mode");
        var stagingPublisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, stagingStore, TABLES));
        assertThatThrownBy(() -> stagingPublisher.publish(stagingLease, SEASON, PLAYER, sourcePage, FIXTURES))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
        assertThat(playerPfsCount(PLAYER)).isZero();

        setRuntimeMode(OwnedSyncMode.ACTIVE, 1);
        setDatabaseCurrentSeason(SharedStatsSeed.CURRENT_SEASON_ID);
        Lease wrongSeasonLease = newLease(PLAYER, "wrong-db-season");
        assertThatThrownBy(() -> publisher.publish(wrongSeasonLease, SEASON, PLAYER, sourcePage, FIXTURES))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
        assertThat(playerPfsCount(PLAYER)).isZero();

        setDatabaseCurrentSeason(SEASON_ID);
        clock.set(BASE.plusSeconds(10));
        SourceDocument currentPage = page(PLAYER, BASE.plusSeconds(11), BASE.plusSeconds(12),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.10"));
        assertThat(publish(PLAYER, "correct-active", currentPage).pfsRowsWritten()).isEqualTo(1);
    }

    @Test
    void malformedAndDuplicatePagesPublishNothingWhileAnotherPlayerPageCanCommit() throws Exception {
        Lease badLease = newLease(PLAYER, "bad");
        ObjectNode validPrefix = row(PLAYER, FIXTURE_HOME, 3, true, "0.10");
        ObjectNode malformed = row(PLAYER, FIXTURE_TRANSFER, 3, false, "0.20");
        malformed.remove("total_points");
        SourceDocument badPage = page(PLAYER, BASE, BASE.plusSeconds(1), validPrefix, malformed);
        clock.set(BASE.plusSeconds(1));
        assertThatThrownBy(() -> publisher.publish(badLease, SEASON, PLAYER, badPage, FIXTURES))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total_points");
        assertThat(playerPfsCount(PLAYER)).isZero();
        assertThat(playerEvidenceCount(PLAYER)).isZero();
        assertThat(playerCurrentCount(PLAYER)).isZero();

        ObjectNode duplicate = row(PLAYER, FIXTURE_HOME, 4, true, "0.10");
        SourceDocument duplicatePage = page(PLAYER, BASE, BASE.plusSeconds(1), validPrefix, duplicate);
        assertThatThrownBy(() -> publisher.publish(badLease, SEASON, PLAYER, duplicatePage, FIXTURES))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate fixture");
        assertThat(playerPfsCount(PLAYER)).isZero();
        assertThat(playerEvidenceCount(PLAYER)).isZero();
        assertThat(playerCurrentCount(PLAYER)).isZero();

        SourceDocument otherPage = page(ZERO_PLAYER, BASE.plusSeconds(2), BASE.plusSeconds(3),
                row(ZERO_PLAYER, FIXTURE_HOME, 1, true, "0.00"));
        assertThat(publish(ZERO_PLAYER, "independent", otherPage).pfsRowsWritten()).isEqualTo(1);
        assertThat(playerPfsCount(ZERO_PLAYER)).isEqualTo(1);
        assertThat(playerPfsCount(PLAYER)).isZero();
    }

    @Test
    void newerSourceWinsAndWithdrawalsCannotBeReversedByOlderOrEqualPages() throws Exception {
        Lease olderLease = newLease(PLAYER, "old");
        Lease newerLease = newLease(PLAYER, "new");
        Lease equalLease = newLease(PLAYER, "equal");
        Lease equalWithdrawalLease = newLease(PLAYER, "equal-withdrawal");
        Lease olderWithdrawalLease = newLease(PLAYER, "older-withdrawal");
        Lease withdrawalLease = newLease(PLAYER, "withdraw");
        Lease staleResurrectionLease = newLease(PLAYER, "stale-resurrection");
        SourceDocument newerPage = page(PLAYER, BASE.plusSeconds(10), BASE.plusSeconds(11),
                row(PLAYER, FIXTURE_HOME, 7, true, "0.20"));
        clock.set(BASE.plusSeconds(11));
        var newer = publisher.publish(newerLease, SEASON, PLAYER, newerPage, FIXTURES);
        assertThat(newer.changedKeys()).hasSize(1);

        SourceDocument olderPage = page(PLAYER, BASE, BASE.plusSeconds(1),
                row(PLAYER, FIXTURE_HOME, 4, true, "0.10"));
        assertThatThrownBy(() -> publisher.publish(olderLease, SEASON, PLAYER, olderPage, FIXTURES))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "total_points")).isEqualTo(7);
        assertThat(currentSourceGeneration(PLAYER, FIXTURE_HOME)).isEqualTo(newerLease.generation());

        SourceDocument equalConflict = page(PLAYER, BASE.plusSeconds(10), BASE.plusSeconds(11),
                row(PLAYER, FIXTURE_HOME, 8, true, "0.20"));
        assertThatThrownBy(() -> publisher.publish(equalLease, SEASON, PLAYER, equalConflict, FIXTURES))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);

        SourceDocument equalWithdrawal = page(PLAYER, BASE.plusSeconds(10), BASE.plusSeconds(11));
        assertThatThrownBy(() -> publisher.publish(equalWithdrawalLease, SEASON, PLAYER,
                equalWithdrawal, FIXTURES))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);
        SourceDocument olderWithdrawal = page(PLAYER, BASE, BASE.plusSeconds(1));
        assertThatThrownBy(() -> publisher.publish(olderWithdrawalLease, SEASON, PLAYER,
                olderWithdrawal, FIXTURES))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);

        SourceDocument withdrawal = page(PLAYER, BASE.plusSeconds(12), BASE.plusSeconds(13));
        clock.set(withdrawal.readInterval().completedAt());
        var withdrawn = publisher.publish(withdrawalLease, SEASON, PLAYER, withdrawal, FIXTURES);
        assertThat(withdrawn.pfsRowsWritten()).isZero();
        assertThat(withdrawn.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER));
        assertThat(currentState(PLAYER, FIXTURE_HOME)).isEqualTo("WITHDRAWN");
        assertThat(playerPfsCount(PLAYER)).isEqualTo(1);
        assertThat(oldReaderRows(oldOffReader(), FIXTURE_HOME, PLAYER)).isEmpty();

        assertThatThrownBy(() -> publisher.publish(staleResurrectionLease, SEASON, PLAYER,
                newerPage, FIXTURES))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);
        assertThat(currentState(PLAYER, FIXTURE_HOME)).isEqualTo("WITHDRAWN");
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "total_points")).isEqualTo(7);
        assertThat(currentSourceGeneration(PLAYER, FIXTURE_HOME)).isEqualTo(withdrawalLease.generation());
        assertThat(oldReaderRows(oldOffReader(), FIXTURE_HOME, PLAYER)).isEmpty();
    }

    @Test
    void concurrentNewerEmptyPageWatermarkPreventsOlderPageResurrection() throws Exception {
        Lease older = newLease(PLAYER, "concurrent-old");
        Lease newer = newLease(PLAYER, "concurrent-new");
        CountDownLatch olderTransactionOpen = new CountDownLatch(1);
        CountDownLatch newerPageCommitted = new CountDownLatch(1);
        SourceDocument oldPage = page(PLAYER, BASE.plusSeconds(1), BASE.plusSeconds(2),
                row(PLAYER, FIXTURE_HOME, 4, true, "0.10"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldAttempt = executor.submit(() -> tx.execute(status -> {
                olderTransactionOpen.countDown();
                try {
                    if (!newerPageCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("newer empty page did not commit in time");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("older page wait was interrupted", interrupted);
                }
                return publisher.publish(older, SEASON, PLAYER, oldPage, FIXTURES);
            }));
            assertThat(olderTransactionOpen.await(10, TimeUnit.SECONDS)).isTrue();

            clock.set(BASE.plusSeconds(20));
            SourceDocument emptyNewerPage = page(PLAYER, BASE.plusSeconds(10), BASE.plusSeconds(11));
            assertThat(publisher.publish(newer, SEASON, PLAYER, emptyNewerPage, FIXTURES)
                    .currentPointersUpdated()).isZero();
            newerPageCommitted.countDown();

            assertThatThrownBy(oldAttempt::get).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);
            assertThat(playerPfsCount(PLAYER)).isZero();
            assertThat(playerCurrentCount(PLAYER)).isZero();
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                    IncrementalPlayerFixturePublisher.pageReceiptKey(PLAYER))).isEqualTo(newer.generation());
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                    HistoryFixtureDispatcher.playerHistoryPublicationKey(PLAYER))).isNull();
        } finally {
            newerPageCommitted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void incrementalPageWatermarkDoesNotLockOrOverwriteLegacyPlayerHistoryReceipt() throws Exception {
        Lease lease = newLease(PLAYER, "separate-page-receipt");
        String legacyKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(PLAYER);
        jdbc.update("UPDATE " + TABLES.receipt()
                        + " SET latest_generation=999999, source_read_started_at=?,"
                        + " source_read_finished_at=?, published_at=? WHERE season=? AND publication_key=?",
                java.sql.Timestamp.from(BASE.plusSeconds(20)), java.sql.Timestamp.from(BASE.plusSeconds(21)),
                java.sql.Timestamp.from(BASE.plusSeconds(21)), SEASON_CODE, legacyKey);
        SourceDocument page = page(PLAYER, BASE.plusSeconds(1), BASE.plusSeconds(2),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.40"));
        clock.set(page.readInterval().completedAt());

        assertThat(publisher.publish(lease, SEASON, PLAYER, page, FIXTURES).pfsRowsWritten()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                legacyKey)).isEqualTo(999999L);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON_CODE,
                IncrementalPlayerFixturePublisher.pageReceiptKey(PLAYER))).isEqualTo(lease.generation());
        assertThat(lease.publicationKey()).isEqualTo(legacyKey);
    }

    @Test
    void strictPageMapsTransferredClubPerFixtureAndLeavesOtherSeasonUntouched() throws Exception {
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                    player_team_id, opponent_team_id, was_home, minutes, total_points, value, created_at)
                VALUES (?, ?, ?, 1, 97_001, 97_002, TRUE, 90, 55, 50, ?)
                """, FIXTURE_HOME, PLAYER, SharedStatsSeed.POPULATED_SEASON_ID,
                java.sql.Timestamp.from(FIXED_CREATED_AT));

        ObjectNode beforeTransfer = row(PLAYER, FIXTURE_HOME, 3, true, "0.10");
        ObjectNode afterTransfer = rowForSide(PLAYER, FIXTURE_TRANSFER, 5, false, 97_003, "0.30");
        SourceDocument page = page(PLAYER, BASE, BASE.plusSeconds(1), beforeTransfer, afterTransfer);
        var result = publish(PLAYER, "transfer", page);

        assertThat(result.changedKeys()).containsExactlyInAnyOrder(
                new IncrementalPfsRepository.RowKey(SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER),
                new IncrementalPfsRepository.RowKey(SEASON_ID, SEASON_CODE, 2, FIXTURE_TRANSFER, PLAYER));
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "player_team_id")).isEqualTo(97_001);
        assertThat(pfsInteger(PLAYER, FIXTURE_TRANSFER, "player_team_id")).isEqualTo(97_004);
        assertThat(pfsInteger(PLAYER, FIXTURE_TRANSFER, "opponent_team_id")).isEqualTo(97_003);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync(), Integer.class)).isZero();
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "total_points", SharedStatsSeed.POPULATED_SEASON_ID))
                .isEqualTo(55);
        assertThat(pfsCreatedAt(PLAYER, FIXTURE_HOME, SharedStatsSeed.POPULATED_SEASON_ID))
                .isEqualTo(FIXED_CREATED_AT);
    }

    @Test
    void fixtureReassignmentReturnsBothOldAndNewGameweekKeys() throws Exception {
        publish(PLAYER, "before-reassignment", page(PLAYER, BASE, BASE.plusSeconds(1),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.10")));

        CanonicalFixture reassigned = new CanonicalFixture(SEASON_ID, SEASON_CODE, FIXTURE_HOME, 3,
                97_001, 97_002, FIXTURES.getFirst().kickoff());
        ObjectNode movedRow = row(PLAYER, FIXTURE_HOME, 2, true, "0.10");
        movedRow.put("round", 3);
        SourceDocument reassignedPage = page(PLAYER, BASE.plusSeconds(2), BASE.plusSeconds(3), movedRow);
        Lease lease = newLease(PLAYER, "after-reassignment");
        clock.set(reassignedPage.readInterval().completedAt());
        var result = publisher.publish(lease, SEASON, PLAYER, reassignedPage,
                List.of(reassigned, FIXTURES.get(1)));

        assertThat(result.changedKeys()).containsExactlyInAnyOrder(
                new IncrementalPfsRepository.RowKey(SEASON_ID, SEASON_CODE, 1, FIXTURE_HOME, PLAYER),
                new IncrementalPfsRepository.RowKey(SEASON_ID, SEASON_CODE, 3, FIXTURE_HOME, PLAYER));
        assertThat(pfsInteger(PLAYER, FIXTURE_HOME, "gameweek")).isEqualTo(3);
    }

    @Test
    void pointerFailureRollsBackPfsAndEvidenceForTheWholePage() throws Exception {
        String function = "fpl_inc_test_fail_current_insert";
        String trigger = "zz_inc_test_fail_current_insert";
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.fixture_id = %d THEN
                        RAISE EXCEPTION 'forced incremental current-pointer failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(function, FIXTURE_TRANSFER));
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + TABLES.playerFixtureCurrent()
                + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            SourceDocument page = page(PLAYER, BASE, BASE.plusSeconds(1),
                    row(PLAYER, FIXTURE_HOME, 2, true, "0.10"),
                    row(PLAYER, FIXTURE_TRANSFER, 3, false, "0.20"));
            Lease lease = newLease(PLAYER, "rollback");
            clock.set(page.readInterval().completedAt());
            assertThatThrownBy(() -> publisher.publish(lease, SEASON, PLAYER, page, FIXTURES))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("forced incremental current-pointer failure");
            assertThat(playerPfsCount(PLAYER)).isZero();
            assertThat(playerEvidenceCount(PLAYER)).isZero();
            assertThat(playerCurrentCount(PLAYER)).isZero();
            assertThat(runState(lease.generation())).isEqualTo("LEASED");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + TABLES.playerFixtureCurrent());
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    @Test
    void advancedSourceDemandFencesAnAlreadyLeasedOldPlayerPage() throws Exception {
        String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(PLAYER);
        store.enqueue(SyncKind.PLAYER_HISTORY, SEASON_CODE, key, key, BASE);
        Lease lease = store.acquire("incremental-demand", Duration.ofMinutes(30)).orElseThrow();
        clock.set(BASE.plusSeconds(2));
        store.ensurePlayerHistoryAtLeast(SEASON_CODE, PLAYER, BASE.plusSeconds(2));

        SourceDocument stalePage = page(PLAYER, BASE.plusSeconds(1), BASE.plusSeconds(2),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.10"));
        clock.set(BASE.plusSeconds(3));
        assertThatThrownBy(() -> publisher.publish(lease, SEASON, PLAYER, stalePage, FIXTURES))
                .isInstanceOf(OwnedSyncStore.SourceDemandAdvancedException.class);
        assertThat(playerPfsCount(PLAYER)).isZero();
        assertThat(playerEvidenceCount(PLAYER)).isZero();
        assertThat(playerCurrentCount(PLAYER)).isZero();
    }

    @Test
    void leaseExpiringDuringPublicationCallbackRollsBackItsWrites() {
        Lease lease = newLease(PLAYER, "expire-during-callback");
        OwnedSyncStore.SourceRead read = new OwnedSyncStore.SourceRead(BASE, BASE.plusSeconds(1));
        clock.set(read.finishedAt());

        assertThatThrownBy(() -> store.publishPlayerHistoryPage(lease, PLAYER, SEASON_ID,
                SEASON_CODE, read, () -> {
                    jdbc.update("UPDATE " + TABLES.run() + " SET last_error='uncommitted callback'"
                            + " WHERE generation=?", lease.generation());
                    clock.set(lease.expiresAt());
                    return null;
                })).isInstanceOf(OwnedSyncStore.LeaseLostException.class);

        assertThat(runState(lease.generation())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " WHERE generation=?", String.class, lease.generation())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, SEASON_CODE,
                IncrementalPlayerFixturePublisher.pageReceiptKey(PLAYER))).isZero();
    }

    @Test
    void staleFencedLeaseCannotLeaveAnyPageRowsBehind() throws Exception {
        clock.set(BASE);
        String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(PLAYER);
        store.enqueue(SyncKind.PLAYER_HISTORY, SEASON_CODE, key, key, BASE);
        Lease stale = store.acquire("incremental-stale", Duration.ofSeconds(5)).orElseThrow();
        clock.set(BASE.plusSeconds(6));
        Lease replacement = store.acquire("incremental-replacement", Duration.ofMinutes(5)).orElseThrow();
        assertThat(replacement.generation()).isEqualTo(stale.generation());
        assertThat(replacement.fence()).isGreaterThan(stale.fence());

        SourceDocument page = page(PLAYER, BASE.plusSeconds(1), BASE.plusSeconds(2),
                row(PLAYER, FIXTURE_HOME, 2, true, "0.10"));
        assertThatThrownBy(() -> publisher.publish(stale, SEASON, PLAYER, page, FIXTURES))
                .isInstanceOf(OwnedSyncStore.LeaseLostException.class);
        assertThat(playerPfsCount(PLAYER)).isZero();
        assertThat(playerEvidenceCount(PLAYER)).isZero();
        assertThat(playerCurrentCount(PLAYER)).isZero();
        assertThat(runState(replacement.generation())).isEqualTo("LEASED");
    }

    private IncrementalPfsRepository.PublicationResult publish(int playerId, String suffix,
                                                                SourceDocument page) {
        Lease lease = newLease(playerId, suffix);
        clock.set(page.readInterval().completedAt());
        return publisher.publish(lease, SEASON, playerId, page, FIXTURES);
    }

    private Lease newLease(int playerId, String suffix) {
        return newLease(store, playerId, suffix);
    }

    private Lease newLease(OwnedSyncStore selectedStore, int playerId, String suffix) {
        String publicationKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
        String coalescingKey = publicationKey + ":" + suffix + ":" + UUID.randomUUID();
        Instant dueAt = clock.instant();
        selectedStore.enqueue(SyncKind.PLAYER_HISTORY, SEASON_CODE, coalescingKey, publicationKey, dueAt);
        return selectedStore.acquire("incremental-test", Duration.ofMinutes(30)).orElseThrow();
    }

    private void setRuntimeMode(OwnedSyncMode mode, long epoch) {
        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode=?, runtime_epoch=? WHERE instance_id=?",
                mode.name(), epoch, "mode:" + SEASON_CODE);
    }

    private void setDatabaseCurrentSeason(int seasonId) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM season ORDER BY id FOR UPDATE", Integer.class);
            jdbc.update("UPDATE season SET is_current=false");
            jdbc.update("UPDATE season SET is_current=true WHERE id=?", seasonId);
        });
    }

    private static SourceDocument page(int playerId, Instant started, Instant finished,
                                       ObjectNode... rows) throws Exception {
        var root = JSON.createObjectNode();
        var history = root.putArray("history");
        for (ObjectNode row : rows) history.add(row);
        return new SourceDocument(JSON.writeValueAsBytes(root), new ReadInterval(started, finished));
    }

    private static ObjectNode row(int playerId, int fixtureId, int points,
                                  boolean wasHome, String expectedGoals) {
        CanonicalFixture fixture = FIXTURES.stream().filter(candidate -> candidate.fixtureId() == fixtureId)
                .findFirst().orElseThrow();
        int opponent = wasHome ? fixture.awayTeamId() : fixture.homeTeamId();
        return rowForSide(playerId, fixtureId, points, wasHome, opponent, expectedGoals);
    }

    private static ObjectNode rowForSide(int playerId, int fixtureId, int points,
                                         boolean wasHome, int opponent, String expectedGoals) {
        CanonicalFixture fixture = FIXTURES.stream().filter(candidate -> candidate.fixtureId() == fixtureId)
                .findFirst().orElseThrow();
        ObjectNode row = JSON.createObjectNode();
        row.put("element", playerId).put("fixture", fixtureId).put("round", fixture.gameweek())
                .put("opponent_team", opponent).put("was_home", wasHome)
                .put("kickoff_time", fixture.kickoff().toString()).put("minutes", 90)
                .put("total_points", points).put("bonus", 0).put("bps", 10)
                .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0)
                .put("goals_conceded", 1).put("own_goals", 0).put("penalties_saved", 0)
                .put("penalties_missed", 0).put("saves", 0).put("yellow_cards", 0)
                .put("red_cards", 0).put("starts", 1).put("value", 50)
                .put("selected", 100).put("transfers_in", 0).put("transfers_out", 0);
        if (expectedGoals != null) row.put("expected_goals", expectedGoals);
        return row;
    }

    private long firstLeaseGeneration(int playerId, String suffix) {
        return jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE publication_key=? AND coalescing_key LIKE ?", Long.class,
                HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId),
                HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId) + ":" + suffix + ":%");
    }

    private String runState(long generation) {
        return jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, generation);
    }

    private int playerPfsCount(int playerId) {
        return jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=? AND player_id=?",
                Integer.class, SEASON_ID, playerId);
    }

    private int playerEvidenceCount(int playerId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND player_id=?", Integer.class, SEASON_ID, playerId);
    }

    private int playerCurrentCount(int playerId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND player_id=?", Integer.class, SEASON_ID, playerId);
    }

    private PfsReadSource oldOffReader() {
        return new PfsReadSource(jdbc, "OFF", SEASON_CODE, TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
    }

    private List<BigDecimal> oldReaderRows(PfsReadSource source, int fixtureId, int playerId) {
        return jdbc.queryForList("WITH " + source.cte()
                + " SELECT expected_goals FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                BigDecimal.class, SEASON_ID, fixtureId, playerId);
    }

    private void seedLegacyPfs(int playerId, int fixtureId, int gameweek, String expectedGoals) {
        boolean home = fixtureId == FIXTURE_HOME;
        CanonicalFixture fixture = FIXTURES.stream().filter(row -> row.fixtureId() == fixtureId)
                .findFirst().orElseThrow();
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                    player_team_id, opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    value, expected_goals, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 2, 50, ?, ?, ?)
                """, fixtureId, playerId, SEASON_ID, gameweek,
                home ? fixture.homeTeamId() : fixture.awayTeamId(),
                home ? fixture.awayTeamId() : fixture.homeTeamId(), home,
                java.sql.Timestamp.from(fixture.kickoff()), new BigDecimal(expectedGoals),
                java.sql.Timestamp.from(FIXED_CREATED_AT), java.sql.Timestamp.from(FIXED_CREATED_AT));
    }

    private void seedOldVerifiedFixture(int playerId, int fixtureId, long generation) {
        jdbc.update("INSERT INTO " + TABLES.playerFixtureEvidence()
                + "(season_id, season_code, fixture_id, player_id, generation, component_presence,"
                + " source_read_started_at, source_read_finished_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", SEASON_ID, SEASON_CODE, fixtureId, playerId,
                generation, Component.EXPECTED_GOALS.bit(), java.sql.Timestamp.from(BASE.minusSeconds(10)),
                java.sql.Timestamp.from(BASE.minusSeconds(9)));
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                + "(season_id, season_code, fixture_id, state, observation_generation,"
                + " current_verified_generation, last_verified_generation, last_verified_at, updated_at)"
                + " VALUES (?, ?, ?, 'VERIFIED', ?, ?, ?, ?, ?)", SEASON_ID, SEASON_CODE, fixtureId,
                generation, generation, generation, java.sql.Timestamp.from(BASE.minusSeconds(8)),
                java.sql.Timestamp.from(BASE.minusSeconds(8)));
    }

    private String currentState(int playerId, int fixtureId) {
        return jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, fixtureId, playerId);
    }

    private long currentSourceGeneration(int playerId, int fixtureId) {
        return jdbc.queryForObject("SELECT source_run_generation FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Long.class,
                SEASON_ID, fixtureId, playerId);
    }

    private SourceInterval currentSourceInterval(int playerId, int fixtureId) {
        return jdbc.queryForObject("SELECT source_read_started_at, source_read_finished_at FROM "
                        + TABLES.playerFixtureCurrent() + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                (rs, rowNum) -> new SourceInterval(rs.getTimestamp("source_read_started_at").toInstant(),
                        rs.getTimestamp("source_read_finished_at").toInstant()),
                SEASON_ID, fixtureId, playerId);
    }

    private Instant currentPointerUpdatedAt(int playerId, int fixtureId) {
        return jdbc.queryForObject("SELECT updated_at FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                SEASON_ID, fixtureId, playerId).toInstant();
    }

    private boolean hasComponent(int playerId, int fixtureId, Component component) {
        long mask = jdbc.queryForObject("SELECT e.component_presence FROM " + TABLES.playerFixtureCurrent()
                + " c JOIN " + TABLES.playerFixtureEvidence()
                + " e ON e.season_id=c.season_id AND e.fixture_id=c.fixture_id AND e.player_id=c.player_id"
                + " AND e.generation=c.evidence_generation WHERE c.season_id=? AND c.fixture_id=? AND c.player_id=?",
                Long.class, SEASON_ID, fixtureId, playerId);
        return (mask & component.bit()) != 0;
    }

    private BigDecimal pfsDecimal(int playerId, int fixtureId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", BigDecimal.class,
                SEASON_ID, fixtureId, playerId);
    }

    private int pfsInteger(int playerId, int fixtureId, String column) {
        return pfsInteger(playerId, fixtureId, column, SEASON_ID);
    }

    private int pfsInteger(int playerId, int fixtureId, String column, int seasonId) {
        return jdbc.queryForObject("SELECT " + column + " FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                seasonId, fixtureId, playerId);
    }

    private TimestampValue pfsAge(int playerId, int fixtureId) {
        return new TimestampValue(jdbc.queryForObject("SELECT updated_at FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                SEASON_ID, fixtureId, playerId).toInstant());
    }

    private Instant pfsCreatedAt(int playerId, int fixtureId) {
        return pfsCreatedAt(playerId, fixtureId, SEASON_ID);
    }

    private Instant pfsCreatedAt(int playerId, int fixtureId, int seasonId) {
        return jdbc.queryForObject("SELECT created_at FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                seasonId, fixtureId, playerId).toInstant();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record TimestampValue(Instant value) { }

    private record SourceInterval(Instant startedAt, Instant finishedAt) { }

    private static final class MutableClock extends Clock {
        private Instant current;
        MutableClock(Instant current) { this.current = current; }
        void set(Instant value) { current = value; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
