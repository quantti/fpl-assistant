package dev.quantti.fplassistant.price;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.quantti.fplassistant.price.PricePredictionSchema.Tables;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.*;
import dev.quantti.fplassistant.price.PricePredictionStore.Lease;

import static dev.quantti.fplassistant.price.PricePredictionFixtures.*;
import static dev.quantti.fplassistant.price.PricePredictionStore.PublicationResult.*;
import static org.assertj.core.api.Assertions.*;

class PricePredictionStoreIntegrationTest {

    private static final Tables TABLES = Tables.test();
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private PricePredictionStore store;

    @BeforeAll
    static void disposableDatabaseOnly() {
        String url = System.getenv("DATABASE_URL");
        boolean local = url != null && url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):55432/postgres");
        boolean ci = "true".equals(System.getenv("GITHUB_ACTIONS")) && url != null
                && url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/postgres");
        if (!local && !ci) {
            throw new IllegalStateException("Requires scripts/test-db.sh loopback disposable DB on port 55432; "
                    + "load documented local DATABASE_URL, DB_USER and DB_PASSWORD");
        }
        dataSource = new DriverManagerDataSource(url, System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
    }

    @BeforeEach
    void resetOnlyOwnedTestTables() {
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of(TABLES.changeEvent(), TABLES.changeCapture(),
                TABLES.forecastPlayer(), TABLES.forecastCapture())) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        PricePredictionSchema.create(jdbc, TABLES);
        store = new PricePredictionStore(jdbc, new DataSourceTransactionManager(dataSource), TABLES);
    }

    @Test
    void runtimeAccessorsObserveDurableRevocationAndLeaseRelease() {
        assertThat(store.publishingEnabled()).isFalse();
        Lease lease = lease();
        assertThat(store.publishingEnabled()).isTrue();
        assertThat(store.leaseIsLive(lease)).isTrue();
        store.release(lease);
        assertThat(store.leaseIsLive(lease)).isFalse();
        store.revokeAndDisable();
        assertThat(store.publishingEnabled()).isFalse();
    }

    @Test
    void startsDisabledAndEmptyAndSchemaCreationIsIdempotent() {
        assertThat(store.acquireLease(Duration.ofMinutes(1))).isEmpty();
        assertThat(store.readForecast()).isEmpty();
        assertThat(store.readRecentChanges()).isEmpty();
        Lease lease = lease();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        PricePredictionSchema.create(jdbc, TABLES);
        assertThat(store.readForecast()).contains(snapshot());
        assertThat(store.acquireLease(Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void leaseUsesDatabaseTimeAndReleaseFencesTheFormerHolder() {
        Instant before = databaseNow();
        Lease first = lease();
        Instant after = databaseNow();
        assertThat(first.expiresAt()).isBetween(before.plusSeconds(60), after.plusSeconds(60));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(store.acquireLease(Duration.ofMinutes(1))).isEmpty();
        store.release(first);
        assertThat(store.publishForecast(first, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        Lease next = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        assertThat(next.token()).isGreaterThan(first.token());
        store.release(first);
        assertThat(store.publishForecast(next, HASH_A, snapshot(), () -> true)).isEqualTo(REPLACED);
    }

    @Test
    void expiredLeaseCannotPublishAndSuccessorFencesOutOfOrderCompletion() {
        Lease old = lease();
        expire();
        assertThat(store.publishForecast(old, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        Lease next = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        assertThat(next.token()).isGreaterThan(old.token());
        var newer = at(snapshot(), READ.plusSeconds(60));
        assertThat(store.publishForecast(next, HASH_B, newer, () -> true)).isEqualTo(REPLACED);
        assertThat(store.publishForecast(old, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        assertThat(store.readForecast()).contains(newer);
    }

    @Test
    void disableIsDurableAcrossStoreInstancesAndReenableDoesNotReviveToken() {
        Lease old = lease();
        store.revokeAndDisable();
        var restarted = new PricePredictionStore(jdbc, new DataSourceTransactionManager(dataSource), TABLES);
        assertThat(restarted.acquireLease(Duration.ofMinutes(1))).isEmpty();
        assertThat(restarted.publishForecast(old, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        restarted.enablePublishing();
        assertThat(restarted.publishForecast(old, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        assertThat(restarted.acquireLease(Duration.ofMinutes(1)).orElseThrow().token()).isGreaterThan(old.token());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void workerRestartPreservesDurableOperatorChoice(boolean disabled) {
        var clock = org.mockito.Mockito.mock(java.time.Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(READ.minusSeconds(1));
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("fpl.price-prediction.enabled", "true");
        var requests = new AtomicInteger();
        PricePredictionHttp.Fetcher http = uri -> {
            requests.incrementAndGet();
            return new PricePredictionHttp.Response(200, null,
                    bytes(uri.getPath().contains("bootstrap") ? bootstrap() : history()));
        };
        store.enablePublishing();
        try (var first = new PricePredictionWorker(store, new PricePredictionPermission(environment), http,
                clock, SEASON, java.net.URI.create("http://localhost/bootstrap/"),
                java.net.URI.create("http://localhost/history/"))) {
            org.mockito.Mockito.when(clock.instant()).thenReturn(READ);
            first.tick();
            assertThat(requests).hasValue(2);
            if (disabled) store.revokeAndDisable();
        }
        assertThat(store.publishingEnabled()).isEqualTo(!disabled);
        var restartedStore = new PricePredictionStore(jdbc, new DataSourceTransactionManager(dataSource), TABLES);
        try (var restarted = new PricePredictionWorker(restartedStore, new PricePredictionPermission(environment), http,
                clock, SEASON, java.net.URI.create("http://localhost/bootstrap/"),
                java.net.URI.create("http://localhost/history/"))) {
            org.mockito.Mockito.when(clock.instant()).thenReturn(READ.plusSeconds(600));
            restarted.tick();
            assertThat(requests).hasValue(disabled ? 2 : 4);
            assertThat(restartedStore.publishingEnabled()).isEqualTo(!disabled);
        }
    }

    @Test
    void publicationPermissionIsCheckedBeforeAndAfterWrites() {
        Lease lease = lease();
        assertThat(store.publishForecast(lease, HASH_A, snapshot(), () -> false)).isEqualTo(REJECTED);
        AtomicInteger checks = new AtomicInteger();
        assertThat(store.publishForecast(lease, HASH_A, snapshot(), () -> checks.incrementAndGet() == 1))
                .isEqualTo(REJECTED);
        assertThat(checks).hasValue(2);
        assertThat(store.readForecast()).isEmpty();
        assertThat(count(TABLES.forecastPlayer())).isZero();
    }

    @Test
    void expiryDetectedAfterWritesRollsBackTheWholePublication() {
        Lease lease = lease();
        assertThat(store.publishForecast(lease, HASH_A, snapshot(), () -> {
            expire();
            return true;
        })).isEqualTo(REJECTED);
        assertThat(store.readForecast()).isEmpty();
        assertThat(count(TABLES.forecastPlayer())).isZero();
    }

    @Test
    void forecastRoundTripPreservesTimesTeamsAndImmutablePlayers() {
        var root = bootstrap();
        firstPlayer(root).put("price_change_percent", "-140.125");
        var snapshot = parse(root);
        store.publishForecast(lease(), HASH_A, snapshot, () -> true);
        var read = store.readForecastView().orElseThrow();
        assertThat(read.snapshot()).isEqualTo(snapshot);
        assertThat(read.publication().generation()).isEqualTo(1);
        assertThat(read.readyCount()).isEqualTo(2);
        assertThatThrownBy(() -> read.snapshot().players().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> read.snapshot().teams().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForMap("SELECT web_name, full_name, team_name, team_short, season, element_id FROM "
                + TABLES.forecastPlayer() + " WHERE element_id = 1"))
                .containsEntry("web_name", "Player 1").containsEntry("team_name", "Club 1")
                .containsEntry("team_short", "C1").containsEntry("season", SEASON);
    }

    @Test
    void optionalForecastFieldsRemainNullAndUnavailable() {
        var root = bootstrap();
        root.withObject("/game_config/status").remove("price_change_last_updated");
        root.withObject("/game_config/settings").remove("price_change_deadlines");
        firstPlayer(root).remove("price_change_projections");
        var expected = parse(root);
        store.publishForecast(lease(), HASH_A, expected, () -> true);
        assertThat(store.readForecast()).contains(expected);
        assertThat(store.readForecastView().orElseThrow().readyCount()).isEqualTo(1);
    }

    @Test
    void identicalFingerprintOnlyRefreshesCheckedTimeWithoutTouchingRowsOrSourceTimes() {
        Lease lease = lease();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        var before = store.readForecastView().orElseThrow();
        List<String> rowVersions = rowVersions(TABLES.forecastPlayer());
        assertThat(store.publishForecast(lease, HASH_A, at(snapshot(), READ.plusSeconds(60)), () -> true)).isEqualTo(CHECKED);
        var after = store.readForecastView().orElseThrow();
        assertThat(after.snapshot()).isEqualTo(before.snapshot());
        assertThat(after.publication().generation()).isEqualTo(before.publication().generation());
        assertThat(after.publication().changedAt()).isEqualTo(before.publication().changedAt());
        assertThat(after.publication().checkedAt()).isEqualTo(READ.plusSeconds(60));
        assertThat(rowVersions(TABLES.forecastPlayer())).isEqualTo(rowVersions);
    }

    @Test
    void changedCaptureReplacesAbsentPlayersAndPreservesPreviousTargetWithoutAnArchive() {
        Lease lease = lease();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        var next = new PricePredictionSnapshot(SEASON, READ.plusSeconds(60), UPDATED.plusSeconds(60),
                TARGET.plusSeconds(86400), snapshot().teams(), Map.of(2, snapshot().players().get(2)));
        assertThat(store.publishForecast(lease, HASH_B, next, () -> true)).isEqualTo(REPLACED);
        assertThat(store.readForecast()).contains(next);
        assertThat(store.readForecastView().orElseThrow().previousTarget()).isEqualTo(TARGET);
        assertThat(count(TABLES.forecastPlayer())).isEqualTo(1);
        assertThat(count(TABLES.forecastCapture())).isEqualTo(1);
    }

    @Test
    void olderOrSameReadCannotReplaceOrRefreshNewerEvidenceEvenWithLiveToken() {
        Lease lease = lease();
        store.publishForecast(lease, HASH_B, at(snapshot(), READ.plusSeconds(60)), () -> true);
        assertThat(store.publishForecast(lease, HASH_A, snapshot(), () -> true)).isEqualTo(REJECTED);
        assertThat(store.publishForecast(lease, HASH_B, snapshot(), () -> true)).isEqualTo(REJECTED);
        assertThat(store.publishForecast(lease, HASH_A, at(snapshot(), READ.plusSeconds(60)), () -> true)).isEqualTo(REJECTED);
        assertThat(store.readForecast().orElseThrow().fetchedAt()).isEqualTo(READ.plusSeconds(60));
    }

    @Test
    void constraintFailureAfterDeletePreservesPriorForecastMetadataAndPlayers() {
        Lease lease = lease();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        var before = store.readForecastView();
        Player original = snapshot().players().get(1);
        Player invalid = new Player(1, 1, 3, original.webName(), original.fullName(), -1, original.forecast());
        var bad = new PricePredictionSnapshot(SEASON, READ.plusSeconds(60), UPDATED, TARGET,
                snapshot().teams(), Map.of(1, invalid));
        assertThatThrownBy(() -> store.publishForecast(lease, HASH_B, bad, () -> true)).isInstanceOf(DataAccessException.class);
        assertThat(store.readForecastView()).isEqualTo(before);
        assertThat(count(TABLES.forecastPlayer())).isEqualTo(2);
    }

    @Test
    void historyRoundTripRetainsEventClubAndIdentityAfterForecastPlayerDisappearsOrSeasonChanges() {
        Lease lease = lease();
        var history = changes();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        assertThat(store.publishRecentChanges(lease, HASH_A, history, snapshot(), () -> true)).isEqualTo(REPLACED);
        var before = store.readRecentView().orElseThrow();
        assertThat(before.snapshot()).isEqualTo(history);
        assertThat(before.namedChanges().getFirst().webName()).isEqualTo("Player 1");
        assertThat(before.namedChanges().getFirst().eventTeam().id()).isEqualTo(2);
        var laterSeason = new PricePredictionSnapshot("2027-28", READ.plusSeconds(60), UPDATED, TARGET,
                snapshot().teams(), Map.of(2, snapshot().players().get(2)));
        store.publishForecast(lease, HASH_B, laterSeason, () -> true);
        assertThat(store.readRecentView()).contains(before);
        assertThatThrownBy(() -> before.namedChanges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.snapshot().changes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void historyFailureRollsBackOnlyHistoryAndForecastStillPublishes() {
        Lease lease = lease();
        store.publishRecentChanges(lease, HASH_A, changes(), snapshot(), () -> true);
        var before = store.readRecentView();
        Change duplicate = changes().changes().getFirst();
        var bad = new RecentChanges(SEASON, READ.plusSeconds(2), READ, List.of(duplicate, duplicate));
        assertThatThrownBy(() -> store.publishRecentChanges(lease, HASH_B, bad, snapshot(), () -> true))
                .isInstanceOf(DataAccessException.class);
        assertThat(store.readRecentView()).isEqualTo(before);
        assertThat(store.publishForecast(lease, HASH_A, snapshot(), () -> true)).isEqualTo(REPLACED);
        assertThat(store.readForecast()).contains(snapshot());
    }

    @Test
    void historyRejectsWrongCompanionUnknownIdentityAndExpiredOrRevokedLease() {
        Lease lease = lease();
        assertThatThrownBy(() -> store.publishRecentChanges(lease, HASH_A, changes(),
                at(snapshot(), READ.plusSeconds(1)), () -> true)).isInstanceOf(IllegalArgumentException.class);
        var unknown = new RecentChanges(SEASON, READ, READ,
                List.of(new Change(999, 1, 7, READ.minusSeconds(1), 75, 76)));
        assertThatThrownBy(() -> store.publishRecentChanges(lease, HASH_A, unknown, snapshot(), () -> true))
                .isInstanceOf(IllegalArgumentException.class);
        expire();
        assertThat(store.publishRecentChanges(lease, HASH_A, changes(), snapshot(), () -> true)).isEqualTo(REJECTED);
        Lease next = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        store.revokeAndDisable();
        assertThat(store.publishRecentChanges(next, HASH_A, changes(), snapshot(), () -> true)).isEqualTo(REJECTED);
        assertThat(store.readRecentChanges()).isEmpty();
    }

    @Test
    void historyUnchangedCheckKeepsIdentityAndReadTimeThenEmptyFeedRemovesEvents() {
        Lease lease = lease();
        store.publishRecentChanges(lease, HASH_A, changes(), snapshot(), () -> true);
        var before = store.readRecentView().orElseThrow();
        var versions = rowVersions(TABLES.changeEvent());
        var repeat = new RecentChanges(SEASON, READ.plusSeconds(10), READ, changes().changes());
        assertThat(store.publishRecentChanges(lease, HASH_A, repeat, snapshot(), () -> true)).isEqualTo(CHECKED);
        assertThat(store.readRecentChanges()).contains(changes());
        assertThat(store.readRecentView().orElseThrow().publication().changedAt()).isEqualTo(before.publication().changedAt());
        assertThat(rowVersions(TABLES.changeEvent())).isEqualTo(versions);
        assertThat(store.publishRecentChanges(lease, HASH_B, changes(), snapshot(), () -> true)).isEqualTo(REJECTED);
        var empty = new RecentChanges(SEASON, READ.plusSeconds(20), READ, List.of());
        assertThat(store.publishRecentChanges(lease, HASH_B, empty, snapshot(), () -> true)).isEqualTo(REPLACED);
        assertThat(store.readRecentChanges()).contains(empty);
        assertThat(count(TABLES.changeEvent())).isZero();
        assertThat(jdbc.queryForMap("SELECT earliest_event, latest_event FROM " + TABLES.changeCapture()))
                .containsEntry("earliest_event", null).containsEntry("latest_event", null);
    }

    @Test
    void historyPermissionLossAfterWritesPreservesPreviousSnapshot() {
        Lease lease = lease();
        store.publishRecentChanges(lease, HASH_A, changes(), snapshot(), () -> true);
        var before = store.readRecentView();
        AtomicInteger checks = new AtomicInteger();
        var empty = new RecentChanges(SEASON, READ.plusSeconds(1), READ, List.of());
        assertThat(store.publishRecentChanges(lease, HASH_B, empty, snapshot(), () -> checks.incrementAndGet() == 1))
                .isEqualTo(REJECTED);
        assertThat(store.readRecentView()).isEqualTo(before);
    }

    @Test
    void forecastReaderKeepsOneGenerationWhenPublicationCommitsBetweenMetadataAndRows() {
        Lease lease = lease();
        store.publishForecast(lease, HASH_A, snapshot(), () -> true);
        var later = new PricePredictionSnapshot(SEASON, READ.plusSeconds(60), UPDATED, TARGET,
                snapshot().teams(), Map.of(2, snapshot().players().get(2)));
        var reader = interleavingReader(TABLES.forecastCapture(),
                () -> assertThat(store.publishForecast(lease, HASH_B, later, () -> true)).isEqualTo(REPLACED));
        assertThat(reader.readForecast()).contains(snapshot());
        assertThat(store.readForecast()).contains(later);
    }

    @Test
    void historyReaderKeepsOneGenerationWhenPublicationCommitsBetweenMetadataAndRows() {
        Lease lease = lease();
        store.publishRecentChanges(lease, HASH_A, changes(), snapshot(), () -> true);
        var empty = new RecentChanges(SEASON, READ.plusSeconds(60), READ, List.of());
        var reader = interleavingReader(TABLES.changeCapture(),
                () -> assertThat(store.publishRecentChanges(lease, HASH_B, empty, snapshot(), () -> true)).isEqualTo(REPLACED));
        assertThat(reader.readRecentChanges()).contains(changes());
        assertThat(store.readRecentChanges()).contains(empty);
    }

    private PricePredictionStore interleavingReader(String captureTable, Runnable publication) {
        var interleaving = new JdbcTemplate(dataSource) {
            private boolean fired;

            @Override
            public <T> T queryForObject(String sql, RowMapper<T> mapper) {
                T result = super.queryForObject(sql, mapper);
                if (!fired && sql.equals("SELECT * FROM " + captureTable + " WHERE id = 1")) {
                    fired = true;
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                            .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                    publication.run();
                }
                return result;
            }
        };
        return new PricePredictionStore(interleaving, new DataSourceTransactionManager(dataSource), TABLES);
    }

    private Lease lease() {
        store.enablePublishing();
        return store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
    }

    private void expire() {
        jdbc.update("UPDATE " + TABLES.forecastCapture() + " SET lease_until = clock_timestamp() - interval '1 second'");
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<String> rowVersions(String table) {
        return jdbc.queryForList("SELECT xmin::text FROM " + table + " ORDER BY element_id", String.class);
    }

    private static PricePredictionSnapshot snapshot() {
        return parse(bootstrap());
    }

    private static PricePredictionSnapshot at(PricePredictionSnapshot snapshot, Instant fetchedAt) {
        return new PricePredictionSnapshot(snapshot.season(), fetchedAt, snapshot.sourceUpdatedAt(), snapshot.target(),
                snapshot.teams(), snapshot.players());
    }

    private static RecentChanges changes() {
        return PricePredictionParser.parseRecentChanges(bytes(history()), snapshot(), READ);
    }
}
