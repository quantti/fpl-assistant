package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.defcon.DefensiveStatsSchema;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.Component;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus;
import dev.quantti.fplassistant.testsupport.TapasSeed;

class FixtureSyncPublisherIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Season SEASON = new Season(9825, "2024-25", LocalDate.parse("2024-08-16"), LocalDate.parse("2025-05-25"));
    private static final Instant FIRST = Instant.parse("2026-09-15T10:00:00Z");
    private static final Season CAPTURED_SEASON = new Season(27, "2026-27",
            LocalDate.parse("2026-08-21"), LocalDate.parse("2027-05-23"));
    private static final String CAPTURES = "/owned-sync-source-fixtures/";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private MutableClock clock;
    private OwnedSyncStore store;
    private FixtureSyncPublisher publisher;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(requiredEnv("DATABASE_URL"),
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        TapasSeed.ensureOwnedSyncFixture(jdbc);
        new DefensiveStatsSchema(jdbc).ensureColumns();
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetOwnedTables() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync()
                + ", " + TABLES.payload() + ", " + TABLES.run() + ", " + TABLES.receipt() + " CASCADE");
        clock = new MutableClock(FIRST.plusSeconds(10));
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
        publisher = new FixtureSyncPublisher(jdbc, clock, store, repository, TABLES);
    }

    @Test
    void explicitFixtureSchemaUsesDedicatedNamesAndRls() {
        assertThat(OwnedSyncTables.defaults().fixtureSync()).isEqualTo("fpl_fixture_sync");
        assertThat(OwnedSyncTables.defaults().playerFixtureEvidence())
                .isEqualTo("fpl_player_fixture_evidence");
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                Boolean.class, TABLES.fixtureSync())).isTrue();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema=current_schema() AND table_name=?", String.class,
                TABLES.fixtureSync())).contains("observation", "current_verified_generation",
                        "last_verified_generation", "expected_participants", "received_participants",
                        "validation_failures", "verified_header", "verified_stats_fingerprint",
                        "last_verified_at", "next_due_at", "attempt_count", "last_error", "alert_state");
    }

    @Test
    void durableFirstObservationThenAtomicVerifiedPfsEvidenceAndState() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs first = inputs(FIRST, false);
            Lease firstLease = lease();
            var pending = publisher.publishParsed(firstLease, SEASON, first.fixture, first.fixtureRead,
                    first.live, first.histories);
            assertThat(pending.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).isEqualTo("VERIFYING");
            assertThat(jdbc.queryForObject("SELECT observation->>'fingerprint' FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).hasSize(64);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence(),
                    Integer.class)).isZero();
            assertThat(store.sourceReceipt(SEASON.code(), FixtureSyncPublisher.publicationKey(98101)))
                    .isEmpty();
            Map<String, Object> pendingState = jdbc.queryForMap("SELECT state, next_due_at, "
                    + "attempt_count, last_error, validation_failures::text validation_failures FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101");
            assertThat(pendingState.get("next_due_at")).isNotNull();
            assertThat(pendingState.get("attempt_count")).isEqualTo(1);
            assertThat(pendingState.get("last_error")).isNull();
            assertThat(pendingState.get("validation_failures"))
                    .isEqualTo("{NEEDS_SECOND_OBSERVATION}");
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, firstLease.generation()))
                    .isEqualTo("COMPLETED");

            clock.set(FIRST.plusSeconds(620));
            // Reconstruct every collaborator: the second observation must use only durable state.
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
            publisher = new FixtureSyncPublisher(jdbc, clock, store,
                    new FixtureSyncRepository(jdbc, TABLES), TABLES);
            Inputs second = inputs(FIRST.plusSeconds(605), false);
            var verified = publisher.publishParsed(lease(), SEASON, second.fixture, second.fixtureRead,
                    second.live, second.histories);
            assertThat(verified.state()).isEqualTo(FixtureVerification.State.VERIFIED);
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).isEqualTo("VERIFIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence(),
                    Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT component_presence FROM "
                    + TABLES.playerFixtureEvidence() + " WHERE player_id=98201", Long.class))
                    .isEqualTo(Component.EXPECTED_GOALS.bit());
            assertThat(jdbc.queryForObject("SELECT expected_assists FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=98101 AND player_id=98201", BigDecimal.class,
                    SEASON.id())).isEqualByComparingTo(BigDecimal.ZERO);
            status.setRollbackOnly();
        });
    }

    @Test
    void pendingOutcomeCommitsWithoutReceiptAndRemainsDue() {
        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        try {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs inputs = inputs(FIRST, true);
            Lease lease = lease();

            var decision = publisher.publishParsed(lease, SEASON, inputs.fixture, inputs.fixtureRead,
                    inputs.live, inputs.histories);

            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(store.sourceReceipt(SEASON.code(), FixtureSyncPublisher.publicationKey(98101)))
                    .isEmpty();
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, lease.generation())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", java.sql.Timestamp.class)).isNotNull();
        } finally {
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrent.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        }
    }

    @Test
    void olderPendingGenerationCannotOverwriteNewerPendingObservationWithoutReceipt() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Lease older = lease("older");
            Lease newer = lease("newer");
            Inputs inputs = inputs(FIRST, false);

            publisher.publishParsed(newer, SEASON, inputs.fixture, inputs.fixtureRead,
                    inputs.live, inputs.histories);

            assertThat(store.sourceReceipt(SEASON.code(), FixtureSyncPublisher.publicationKey(98101)))
                    .isEmpty();
            assertThatThrownBy(() -> publisher.publishParsed(older, SEASON, inputs.fixture,
                    inputs.fixtureRead, inputs.live, inputs.histories))
                    .isInstanceOf(OwnedSyncStore.StaleGenerationException.class);
            assertThat(jdbc.queryForObject("SELECT observation_generation FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101", Long.class))
                    .isEqualTo(newer.generation());
            status.setRollbackOnly();
        });
    }

    @Test
    void aRecheckInsideTheStabilityWindowDoesNotRevokeAVerifiedFixture() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs first = inputs(FIRST, false);
            publisher.publishParsed(lease(), SEASON, first.fixture, first.fixtureRead,
                    first.live, first.histories);
            clock.set(FIRST.plusSeconds(620));
            Inputs second = inputs(FIRST.plusSeconds(605), false);
            assertThat(publisher.publishParsed(lease(), SEASON, second.fixture, second.fixtureRead,
                    second.live, second.histories).state()).isEqualTo(FixtureVerification.State.VERIFIED);
            Map<String, Object> verified = jdbc.queryForMap("SELECT state, current_verified_generation,"
                    + " observation_generation FROM " + TABLES.fixtureSync() + " WHERE fixture_id=98101");

            // A follow-up planned before the fixture verified runs seconds later: too early to
            // compare is not a contradiction, so it must leave the verification alone.
            clock.set(FIRST.plusSeconds(660));
            Inputs early = inputs(FIRST.plusSeconds(650), false);
            var decision = publisher.publishParsed(lease(), SEASON, early.fixture, early.fixtureRead,
                    early.live, early.histories);
            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(decision.reasons()).containsExactly(FixtureVerification.Reason.STABILITY_WINDOW);

            Map<String, Object> after = jdbc.queryForMap("SELECT state, current_verified_generation,"
                    + " observation_generation FROM " + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(after.get("state")).isEqualTo("VERIFIED");
            assertThat(after.get("current_verified_generation"))
                    .isEqualTo(verified.get("current_verified_generation"));
            assertThat(after.get("observation_generation")).isEqualTo(verified.get("observation_generation"));
            status.setRollbackOnly();
        });
    }

    @Test
    void dependencyWaitIsNotAnAttemptAndADailyOnlyFixtureIsAdmittedAgainTheSameDay() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs first = inputs(FIRST, false);
            publisher.publishParsed(lease(), SEASON, first.fixture, first.fixtureRead,
                    first.live, first.histories);
            FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
            // Thirteen hours after our first sighting the fixture is on the daily cadence.
            jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET first_finished_observed_at=?,"
                            + " alert_state='DAILY_ONLY', next_due_at=? WHERE fixture_id=98101",
                    java.sql.Timestamp.from(clock.instant().minusSeconds(13 * 3_600)),
                    java.sql.Timestamp.from(clock.instant()));
            int attemptsBefore = jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", Integer.class);

            Instant requestedWait = clock.instant().plusSeconds(120);
            Instant wait = repository.recordDependencyWait(SEASON, 98101, requestedWait,
                    "Waiting for fresh player histories", clock.instant());
            assertThat(wait).isAfter(requestedWait);

            Map<String, Object> row = jdbc.queryForMap("SELECT attempt_count, next_due_at, last_error,"
                    + " alert_state FROM " + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(row.get("attempt_count")).isEqualTo(attemptsBefore);
            assertThat(row.get("alert_state")).isEqualTo("DAILY_ONLY");
            assertThat(row.get("last_error")).isEqualTo("Waiting for fresh player histories");
            assertThat(((java.sql.Timestamp) row.get("next_due_at")).toInstant()).isEqualTo(wait);
            // One attempt a day can never finish a verification that has to wait for histories,
            // so the follow-up of a wait is admitted at its own due time, not at the next sweep.
            assertThat(repository.admitAttempt(SEASON, 98101, wait, SourceStatus.FINISHED).admitted()).isTrue();
            status.setRollbackOnly();
        });
    }

    @Test
    void failedAttemptMetadataDoesNotReplaceReadinessAndSuccessClearsError() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs first = inputs(FIRST, false);
            publisher.publishParsed(lease(), SEASON, first.fixture, first.fixtureRead,
                    first.live, first.histories);
            Map<String, Object> readiness = jdbc.queryForMap("SELECT state, observation::text, "
                    + "observation_generation, current_verified_generation, last_verified_generation, "
                    + "validation_failures::text validation_failures FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101");

            FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
            Instant due = clock.instant().plusSeconds(60);
            repository.recordFailedAttempt(SEASON, 98101, due,
                    "x".repeat(OwnedSyncStore.MAX_ERROR_CHARS + 25), clock.instant());
            Map<String, Object> failed = jdbc.queryForMap("SELECT state, observation::text, "
                    + "observation_generation, current_verified_generation, last_verified_generation, "
                    + "validation_failures::text validation_failures, attempt_count, "
                    + "length(last_error) error_length, next_due_at "
                    + "FROM " + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(failed.get("state")).isEqualTo(readiness.get("state"));
            assertThat(failed.get("observation")).isEqualTo(readiness.get("observation"));
            assertThat(failed.get("observation_generation"))
                    .isEqualTo(readiness.get("observation_generation"));
            assertThat(failed.get("current_verified_generation"))
                    .isEqualTo(readiness.get("current_verified_generation"));
            assertThat(failed.get("last_verified_generation"))
                    .isEqualTo(readiness.get("last_verified_generation"));
            assertThat(failed.get("validation_failures"))
                    .isEqualTo(readiness.get("validation_failures"));
            assertThat(failed.get("attempt_count")).isEqualTo(2);
            assertThat(failed.get("error_length")).isEqualTo(OwnedSyncStore.MAX_ERROR_CHARS);
            assertThat(((java.sql.Timestamp) failed.get("next_due_at")).toInstant())
                    .isEqualTo(clock.instant().plusSeconds(1_200));

            clock.set(FIRST.plusSeconds(620));
            Inputs second = inputs(FIRST.plusSeconds(605), false);
            publisher.publishParsed(lease(), SEASON, second.fixture, second.fixtureRead,
                    second.live, second.histories);
            assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).isNull();
            status.setRollbackOnly();
        });
    }

    @Test
    void statsOnlyStoredScheduleMismatchStaysPendingWithoutPfsWrites() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            jdbc.update("UPDATE fixture SET stats=?::jsonb WHERE season_id=? AND id=98101",
                    "[{\"identifier\":\"minutes\",\"h\":[{\"element\":98201,\"value\":89}],"
                            + "\"a\":[{\"element\":98202,\"value\":90}]}]",
                    SEASON.id());
            Inputs inputs = inputs(FIRST, true);

            var decision = publisher.publishParsed(lease(), SEASON, inputs.fixture, inputs.fixtureRead,
                    inputs.live, inputs.histories);

            assertThat(decision.reasons()).contains(FixtureVerification.Reason.DATABASE_FIXTURE_MISMATCH);
            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=98101 AND updated_at=?", Integer.class,
                    SEASON.id(), java.sql.Timestamp.from(clock.instant()))).isZero();
            status.setRollbackOnly();
        });
    }

    @Test
    void missingFixtureAndUnknownPlayerParentsStayPendingWithoutPfsWrites() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=98101",
                    SEASON.id());
            jdbc.update("DELETE FROM fixture WHERE season_id=? AND id=98101", SEASON.id());
            jdbc.update("DELETE FROM player WHERE season_id=? AND id=98201", SEASON.id());

            Inputs inputs = inputs(FIRST, true);
            var decision = publisher.publishParsed(lease(), SEASON, inputs.fixture, inputs.fixtureRead,
                    inputs.live, inputs.histories);
            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(decision.reasons()).contains(FixtureVerification.Reason.MISSING_DATABASE_FIXTURE,
                    FixtureVerification.Reason.UNKNOWN_DATABASE_PLAYER);
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).isEqualTo("WAITING_STATS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=98101", Integer.class, SEASON.id())).isZero();
            status.setRollbackOnly();
        });
    }

    @Test
    void missingDatabasePlayerPresentInBootstrapRequestsCatalogueRepairState() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            jdbc.update("DELETE FROM player WHERE season_id=? AND id=98201", SEASON.id());
            Inputs inputs = inputs(FIRST, true);

            var decision = publisher.publishParsed(lease(), SEASON, inputs.fixture,
                    inputs.fixtureRead, inputs.live, inputs.histories, Set.of(98201, 98202));

            assertThat(decision.reasons()).contains(FixtureVerification.Reason.UNKNOWN_DATABASE_PLAYER)
                    .doesNotContain(FixtureVerification.Reason.UNRESOLVED_PARENT);
            Map<String, Object> state = jdbc.queryForMap("SELECT state, last_error, next_due_at FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(state.get("state")).isEqualTo("WAITING_STATS");
            assertThat(state.get("last_error")).isNull();
            assertThat(((java.sql.Timestamp) state.get("next_due_at")).toInstant())
                    .isEqualTo(clock.instant().plusSeconds(600));
            status.setRollbackOnly();
        });
    }

    @Test
    void missingDatabasePlayerAbsentFromBootstrapUsesTheSharedRetryLadder() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            jdbc.update("DELETE FROM player WHERE season_id=? AND id=98201", SEASON.id());
            Inputs inputs = inputs(FIRST, true);

            var decision = publisher.publishParsed(lease(), SEASON, inputs.fixture,
                    inputs.fixtureRead, inputs.live, inputs.histories, Set.of(98202));

            assertThat(decision.reasons()).contains(FixtureVerification.Reason.UNRESOLVED_PARENT)
                    .doesNotContain(FixtureVerification.Reason.UNKNOWN_DATABASE_PLAYER);
            Map<String, Object> state = jdbc.queryForMap("SELECT state, last_error, next_due_at FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(state.get("state")).isEqualTo("WAITING_STATS");
            assertThat(state.get("last_error")).asString().startsWith("UNRESOLVED_PARENT:");
            assertThat(((java.sql.Timestamp) state.get("next_due_at")).toInstant())
                    .isEqualTo(clock.instant().plusSeconds(600));
            status.setRollbackOnly();
        });
    }

    @Test
    void optionalCorrectionsRetainUnknownValuesAndApplyPresentZero() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            publishAt(FIRST, true, new BigDecimal("1.25"));
            publishAt(FIRST.plusSeconds(605), true, new BigDecimal("1.25"));
            assertExpectedAssists("1.25");

            publishAt(FIRST.plusSeconds(1210), false, null);
            publishAt(FIRST.plusSeconds(1815), false, null);
            assertExpectedAssists("1.25");
            assertThat(jdbc.queryForObject("SELECT component_presence FROM "
                    + TABLES.playerFixtureEvidence() + " WHERE player_id=98201 ORDER BY generation DESC LIMIT 1",
                    Long.class) & Component.EXPECTED_ASSISTS.bit()).isZero();

            publishAt(FIRST.plusSeconds(2420), true, BigDecimal.ZERO);
            publishAt(FIRST.plusSeconds(3025), true, BigDecimal.ZERO);
            assertExpectedAssists("0");
            status.setRollbackOnly();
        });
    }

    @Test
    void verifiedPublicationRollsBackPfsEvidenceStateRunAndReceiptOnEvidenceFailure() {
        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        try {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            publishAt(FIRST, true, BigDecimal.ZERO);
            publishAt(FIRST.plusSeconds(605), true, BigDecimal.ZERO);
            long receiptBefore = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                    FixtureSyncPublisher.publicationKey(98101));
            int evidenceBefore = jdbc.queryForObject("SELECT count(*) FROM "
                    + TABLES.playerFixtureEvidence(), Integer.class);

            publishAt(FIRST.plusSeconds(1210), true, new BigDecimal("2.00"));
            long pendingGeneration = jdbc.queryForObject("SELECT observation_generation FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101", Long.class);
            clock.set(FIRST.plusSeconds(1830));
            Inputs stable = inputs(FIRST.plusSeconds(1815), true, new BigDecimal("2.00"));
            Lease failingLease = lease();
            jdbc.execute("CREATE OR REPLACE FUNCTION test_reject_fixture_completion() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.state = 'COMPLETED' THEN "
                    + "RAISE EXCEPTION 'forced completion failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_reject_fixture_completion BEFORE UPDATE ON "
                    + TABLES.run() + " FOR EACH ROW EXECUTE FUNCTION test_reject_fixture_completion()");

            assertThatThrownBy(() -> publisher.publishParsed(failingLease, SEASON, stable.fixture,
                    stable.fixtureRead, stable.live, stable.histories))
                    .rootCause().hasMessageContaining("forced completion failure");

            assertExpectedAssists("0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence(),
                    Integer.class)).isEqualTo(evidenceBefore);
            assertThat(jdbc.queryForObject("SELECT observation_generation FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", Long.class)).isEqualTo(pendingGeneration);
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=98101", String.class)).isEqualTo("VERIFYING");
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, failingLease.generation())).isEqualTo("LEASED");
            assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                    + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                    FixtureSyncPublisher.publicationKey(98101))).isEqualTo(receiptBefore);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_fixture_completion ON " + TABLES.run());
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_fixture_completion()");
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=98101", SEASON.id());
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrent.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        }
    }

    @Test
    void publicRawApiParsesCompleteCapturedSourcesAndReturnsPendingCoverageReason() throws Exception {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=false");
            jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current) "
                    + "VALUES (?, ?, 'Captured season', ?, ?, true) ON CONFLICT (id) DO UPDATE SET "
                    + "code=EXCLUDED.code, start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date, is_current=true",
                    CAPTURED_SEASON.id(), CAPTURED_SEASON.code(), CAPTURED_SEASON.startsOn(), CAPTURED_SEASON.endsOn());
            jdbc.update("INSERT INTO team(id, season_id, code, name, short_name, strength) VALUES "
                    + "(1,27,3,'Arsenal','ARS',3),(6,27,8,'Chelsea','CHE',3) ON CONFLICT DO NOTHING");
            jdbc.update("INSERT INTO player(id, season_id, team_id, first_name, second_name, web_name, "
                    + "element_type, now_cost, status) VALUES (1,27,1,'David','Raya','Raya',1,60,'a'),"
                    + "(2,27,1,'Kepa','Arrizabalaga','Arrizabalaga',1,49,'a'),"
                    + "(557,27,1,'Christos','Tzolis','Tzolis',3,64,'a') ON CONFLICT DO NOTHING");
            jdbc.update("INSERT INTO fixture(id, season_id, gameweek, code, team_h, team_a, team_h_score,"
                    + " team_a_score, kickoff_time, started, finished, finished_provisional, minutes) VALUES "
                    + "(29,27,3,2645215,1,6,2,1,? ,true,true,true,90) ON CONFLICT DO NOTHING",
                    java.sql.Timestamp.from(Instant.parse("2026-09-06T15:30:00Z")));

            clock.set(Instant.parse("2026-09-13T17:44:00Z"));
            store.enqueue(SyncKind.FIXTURE_VERIFICATION, CAPTURED_SEASON.code(), "captured-29",
                    FixtureSyncPublisher.publicationKey(29), clock.instant());
            clock.set(Instant.parse("2026-09-13T17:47:00Z"));
            Lease lease = store.acquire("fixture-test", Duration.ofMinutes(30)).orElseThrow();
            Map<Integer, SourceDocument> histories = new LinkedHashMap<>();
            histories.put(1, capture("element-summary-1.json", "2026-09-13T17:46:18.763437Z", "2026-09-13T17:46:18.868461Z"));
            histories.put(2, capture("element-summary-2.json", "2026-09-13T17:46:19.968870Z", "2026-09-13T17:46:20.060242Z"));
            histories.put(557, capture("element-summary-557.json", "2026-09-13T17:46:21.160577Z", "2026-09-13T17:46:21.222900Z"));
            histories.put(658, capture("element-summary-658.json", "2026-09-13T17:46:22.323202Z", "2026-09-13T17:46:22.405144Z"));

            var decision = publisher.publish(lease, CAPTURED_SEASON, 29,
                    capture("bootstrap-static.json", "2026-09-13T17:45:07.693745Z", "2026-09-13T17:45:07.896876Z"),
                    capture("fixtures.json", "2026-09-13T17:45:06.445472Z", "2026-09-13T17:45:06.593102Z"),
                    capture("event-3-live.json", "2026-09-13T17:45:25.840005Z", "2026-09-13T17:45:25.985506Z"), histories);

            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(decision.reasons()).contains(FixtureVerification.Reason.MISSING_HISTORY);
            assertThat(decision.observation().fixtureId()).isEqualTo(29);
            status.setRollbackOnly();
        });
    }

    @Test
    void pendingStatsCorrectionSerializesBeforeStaleVerificationAcrossConnections() throws Exception {
        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
        Inputs first = inputs(FIRST, true);
        publisher.publishParsed(lease(), SEASON, first.fixture, first.fixtureRead,
                first.live, first.histories);

        JdbcTemplate correctionJdbc = new JdbcTemplate(dataSource);
        TransactionTemplate correctionTx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        FixtureSyncRepository correctionRepository = new FixtureSyncRepository(correctionJdbc, TABLES);
        FixtureRow corrected = fixture(true, 2,
                "[{\"identifier\":\"minutes\",\"h\":[{\"element\":98201,\"value\":89}],"
                        + "\"a\":[{\"element\":98202,\"value\":90}]}]");
        CountDownLatch correctionWritten = new CountDownLatch(1);
        CountDownLatch releaseCorrection = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> correction = executor.submit(() -> correctionTx.executeWithoutResult(status -> {
                correctionRepository.lockAndValidatePlayingSeason(SEASON);
                assertThat(correctionRepository.invalidateCorrections(List.of(corrected), clock.instant()))
                        .containsExactly(98101);
                correctionJdbc.update("UPDATE fixture SET stats=?::jsonb WHERE season_id=? AND id=?",
                        corrected.fields().get("stats"), SEASON.id(), corrected.id());
                correctionWritten.countDown();
                await(releaseCorrection);
            }));
            assertThat(correctionWritten.await(5, TimeUnit.SECONDS)).isTrue();

            clock.set(FIRST.plusSeconds(620));
            Inputs stale = inputs(FIRST.plusSeconds(605), true);
            Future<FixtureVerification.Decision> verification = executor.submit(() ->
                    publisher.publishParsed(lease(), SEASON, stale.fixture, stale.fixtureRead,
                            stale.live, stale.histories));
            awaitSeasonLockWaiter();
            releaseCorrection.countDown();

            correction.get(5, TimeUnit.SECONDS);
            FixtureVerification.Decision decision = verification.get(10, TimeUnit.SECONDS);
            assertThat(decision.state()).isEqualTo(FixtureVerification.State.PENDING);
            assertThat(decision.reasons())
                    .contains(FixtureVerification.Reason.DATABASE_FIXTURE_MISMATCH)
                    .doesNotContain(FixtureVerification.Reason.CHANGED_FINGERPRINT);
            assertThat(jdbc.queryForObject("SELECT current_verified_generation IS NULL FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101", Boolean.class)).isTrue();
        } finally {
            releaseCorrection.countDown();
            TapasSeed.ensureOwnedSyncFixture(jdbc);
            jdbc.update("UPDATE season SET is_current=false");
            previousCurrent.forEach(id ->
                    jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        }
    }

    @Test
    void publicRawApiPublishesTwoSuccessfulObservationsAcrossRestart() throws Exception {
        Instant firstRead = Instant.parse("2026-09-13T17:45:00Z");
        RawInputs first = capturedInputs(firstRead);
        RawInputs second = capturedInputs(firstRead.plusSeconds(605));
        var snapshot = CatalogueScheduleParser.parse(CAPTURED_SEASON, first.bootstrap, first.fixtures);
        FixtureRow fixture = snapshot.fixtures().stream().filter(row -> row.id() == 29)
                .findFirst().orElseThrow();

        List<Integer> previousCurrent = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true", Integer.class);
        try {
            seedCapturedFixture(snapshot, fixture, first.histories.keySet());
            clock.set(firstRead.minusSeconds(60));
            Lease firstLease = capturedLease();
            clock.set(firstRead.plusSeconds(30));
            var pending = publisher.publish(firstLease, CAPTURED_SEASON, 29, first.bootstrap,
                    first.fixtures, first.live, first.histories);
            assertThat(pending.reasons())
                    .containsExactly(FixtureVerification.Reason.NEEDS_SECOND_OBSERVATION);

            clock.set(firstRead.plusSeconds(545));
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
            publisher = new FixtureSyncPublisher(jdbc, clock, store,
                    new FixtureSyncRepository(jdbc, TABLES), TABLES);
            Lease secondLease = capturedLease();
            clock.set(firstRead.plusSeconds(700));
            var verified = publisher.publish(secondLease, CAPTURED_SEASON, 29, second.bootstrap,
                    second.fixtures, second.live, second.histories);

            assertThat(verified.state()).isEqualTo(FixtureVerification.State.VERIFIED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=27 AND fixture_id=29", Integer.class))
                    .isEqualTo(first.histories.size());
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.fixtureSync()
                    + " WHERE season_id=27 AND fixture_id=29", String.class)).isEqualTo("VERIFIED");
        } finally {
            jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync()
                    + ", " + TABLES.payload() + ", " + TABLES.run() + ", " + TABLES.receipt()
                    + " CASCADE");
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=?",
                    CAPTURED_SEASON.id(), fixture.id());
            jdbc.update("DELETE FROM fixture WHERE season_id=?", CAPTURED_SEASON.id());
            jdbc.update("DELETE FROM player WHERE season_id=?", CAPTURED_SEASON.id());
            jdbc.update("DELETE FROM team WHERE season_id=?", CAPTURED_SEASON.id());
            jdbc.update("DELETE FROM season WHERE id=?", CAPTURED_SEASON.id());
            previousCurrent.forEach(id ->
                    jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        }
    }

    @Test
    void statsOnlyScheduleCorrectionRevokesVerificationWithoutDeletingLastGeneration() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=(id=?)", SEASON.id());
            Inputs first = inputs(FIRST, true);
            publisher.publishParsed(lease(), SEASON, first.fixture, first.fixtureRead, first.live, first.histories);
            clock.set(FIRST.plusSeconds(620));
            Inputs second = inputs(FIRST.plusSeconds(605), true);
            publisher.publishParsed(lease(), SEASON, second.fixture, second.fixtureRead, second.live, second.histories);

            int pfsBefore = jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=98101", Integer.class, SEASON.id());
            int evidenceBefore = jdbc.queryForObject("SELECT count(*) FROM "
                    + TABLES.playerFixtureEvidence() + " WHERE season_id=? AND fixture_id=98101",
                    Integer.class, SEASON.id());
            String verifiedHeader = jdbc.queryForObject("SELECT verified_header FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101", String.class);
            FixtureSyncRepository repository = new FixtureSyncRepository(jdbc, TABLES);
            FixtureRow reordered = fixture(true, 2,
                    "[ { \"a\": [ { \"value\": 90, \"element\": 98202 } ],"
                            + " \"h\": [ { \"value\": 90, \"element\": 98201 } ],"
                            + " \"identifier\": \"minutes\" } ]");
            assertThat(repository.invalidateCorrections(List.of(reordered), clock.instant())).isEmpty();

            FixtureRow corrected = fixture(true, 2,
                    "[{\"identifier\":\"minutes\",\"h\":[{\"element\":98201,\"value\":89}],"
                            + "\"a\":[{\"element\":98202,\"value\":90}]}]");
            Set<Integer> changed = repository.invalidateCorrections(List.of(corrected), clock.instant());
            Map<String, Object> row = jdbc.queryForMap("SELECT state, observation::text observation, observation_generation,"
                    + " current_verified_generation, last_verified_generation, verified_header,"
                    + " verified_stats_fingerprint, last_verified_at, next_due_at FROM "
                    + TABLES.fixtureSync() + " WHERE fixture_id=98101");
            assertThat(changed).containsExactly(98101);
            assertThat(row.get("state")).isEqualTo("VERIFYING");
            assertThat(FixtureObservationCodec.decode((String) row.get("observation")).coherent()).isFalse();
            assertThat(row.get("observation_generation")).isEqualTo(row.get("last_verified_generation"));
            assertThat(row.get("current_verified_generation")).isNull();
            assertThat(row.get("last_verified_generation")).isNotNull();
            assertThat(row.get("verified_header")).isEqualTo(verifiedHeader);
            assertThat(row.get("verified_stats_fingerprint")).asString().hasSize(64);
            assertThat(row.get("last_verified_at")).isNotNull();
            assertThat(((java.sql.Timestamp) row.get("next_due_at")).toInstant())
                    .isEqualTo(clock.instant());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=98101", Integer.class, SEASON.id()))
                    .isEqualTo(pfsBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id=98101", Integer.class, SEASON.id()))
                    .isEqualTo(evidenceBefore);
            status.setRollbackOnly();
        });
    }

    private void publishAt(Instant readAt, boolean allOptional, BigDecimal expectedAssists) {
        clock.set(readAt.plusSeconds(10));
        Inputs inputs = inputs(readAt, allOptional, expectedAssists);
        publisher.publishParsed(lease(), SEASON, inputs.fixture, inputs.fixtureRead,
                inputs.live, inputs.histories);
    }

    private void assertExpectedAssists(String expected) {
        assertThat(jdbc.queryForObject("SELECT expected_assists FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=98101 AND player_id=98201", BigDecimal.class,
                SEASON.id())).isEqualByComparingTo(expected);
    }

    private Lease lease() {
        return lease("verify-98101");
    }

    private Lease capturedLease() {
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, CAPTURED_SEASON.code(), "captured-29-" + clock.instant(),
                FixtureSyncPublisher.publicationKey(29), clock.instant());
        return store.acquire("fixture-test", Duration.ofMinutes(30)).orElseThrow();
    }

    private Lease lease(String coalescingKey) {
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, SEASON.code(), coalescingKey,
                FixtureSyncPublisher.publicationKey(98101), clock.instant());
        return store.acquire("fixture-test", Duration.ofMinutes(30)).orElseThrow();
    }

    private static SourceDocument capture(String file, String startedAt, String completedAt) {
        return capture(file, new ReadInterval(Instant.parse(startedAt), Instant.parse(completedAt)));
    }

    private static SourceDocument capture(String file, ReadInterval interval) {
        try (var input = FixtureSyncPublisherIntegrationTest.class.getResourceAsStream(CAPTURES + file)) {
            if (input == null) throw new IllegalArgumentException("missing " + file);
            return new SourceDocument(input.readAllBytes(), interval);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RawInputs capturedInputs(Instant start) throws Exception {
        SourceDocument bootstrap = capture("bootstrap-static.json", interval(start));
        SourceDocument fixtures = capture("fixtures.json", interval(start));
        SourceDocument live = capture("event-3-live.json", interval(start.plusSeconds(2)));
        JsonNode bootstrapJson = JSON.readTree(bootstrap.bytes());
        Map<Integer, Integer> teams = new java.util.HashMap<>();
        bootstrapJson.withArray("elements").forEach(player ->
                teams.put(player.required("id").intValue(), player.required("team").intValue()));
        Map<Integer, SourceDocument> histories = new LinkedHashMap<>();
        for (JsonNode player : JSON.readTree(live.bytes()).withArray("elements")) {
            int playerId = player.required("id").intValue();
            for (JsonNode explanation : player.withArray("explain")) {
                if (explanation.required("fixture").intValue() != 29) continue;
                int minutes = 0;
                for (JsonNode stat : explanation.withArray("stats")) {
                    if (stat.required("identifier").textValue().equals("minutes")) {
                        minutes = stat.required("value").intValue();
                    }
                }
                if (minutes <= 0) continue;
                int team = teams.get(playerId);
                ObjectNode row = JSON.createObjectNode();
                row.put("element", playerId).put("fixture", 29).put("round", 3)
                        .put("opponent_team", team == 1 ? 6 : 1).put("was_home", team == 1)
                        .put("kickoff_time", "2026-09-06T15:30:00Z").put("minutes", minutes)
                        .put("total_points", 2).put("bonus", 0).put("bps", 1)
                        .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0)
                        .put("goals_conceded", 1).put("own_goals", 0).put("penalties_saved", 0)
                        .put("penalties_missed", 0).put("saves", 0).put("yellow_cards", 0)
                        .put("red_cards", 0).put("starts", minutes >= 60 ? 1 : 0).put("value", 50)
                        .put("selected", 1).put("transfers_in", 0).put("transfers_out", 0);
                ObjectNode root = JSON.createObjectNode();
                root.putArray("history").add(row);
                histories.put(playerId, new SourceDocument(JSON.writeValueAsBytes(root),
                        interval(start.plusSeconds(4))));
            }
        }
        return new RawInputs(bootstrap, fixtures, live, Map.copyOf(histories));
    }

    private static void seedCapturedFixture(CatalogueScheduleParser.Snapshot snapshot,
                                            FixtureRow fixture, Set<Integer> players) {
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current) "
                        + "VALUES (?, ?, 'Captured season', ?, ?, true) ON CONFLICT (id) DO UPDATE SET "
                        + "code=EXCLUDED.code, start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date, "
                        + "is_current=true",
                CAPTURED_SEASON.id(), CAPTURED_SEASON.code(), CAPTURED_SEASON.startsOn(),
                CAPTURED_SEASON.endsOn());
        snapshot.teams().stream().filter(team -> team.id() == 1 || team.id() == 6).forEach(team ->
                jdbc.update("INSERT INTO team(id,season_id,code,name,short_name,strength) VALUES "
                                + "(?,?,?,?,?,3) ON CONFLICT (id,season_id) DO NOTHING",
                        team.id(), CAPTURED_SEASON.id(), team.code(), team.name(), team.shortName()));
        Map<Integer, CatalogueScheduleParser.PlayerRow> byId = snapshot.players().stream()
                .collect(java.util.stream.Collectors.toMap(CatalogueScheduleParser.PlayerRow::id, row -> row));
        players.forEach(id -> {
            var player = byId.get(id);
            jdbc.update("INSERT INTO player(id,season_id,team_id,web_name,element_type,now_cost,status) "
                            + "VALUES (?,?,?,?,?,?, 'a') ON CONFLICT (id,season_id) DO NOTHING",
                    id, CAPTURED_SEASON.id(), player.teamId(), player.webName(),
                    player.elementType(), player.nowCost());
        });
        jdbc.update("INSERT INTO fixture(id,season_id,gameweek,code,team_h,team_a,team_h_score,"
                        + "team_a_score,kickoff_time,started,finished,finished_provisional,minutes,stats) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (id,season_id) DO UPDATE SET "
                        + "stats=EXCLUDED.stats, team_h_score=EXCLUDED.team_h_score, "
                        + "team_a_score=EXCLUDED.team_a_score, finished=EXCLUDED.finished",
                fixture.id(), fixture.seasonId(), fixture.gameweek(), fixture.code(),
                fixture.homeTeamId(), fixture.awayTeamId(), fixture.homeScore(), fixture.awayScore(),
                java.sql.Timestamp.from(fixture.kickoff()), fixture.started(), fixture.finished(),
                fixture.fields().get("finished_provisional"), fixture.fields().get("minutes"),
                fixture.fields().get("stats"));
    }

    private static Inputs inputs(Instant readAt, boolean allOptional) {
        return inputs(readAt, allOptional, allOptional ? BigDecimal.ZERO : null);
    }

    private static Inputs inputs(Instant readAt, boolean allOptional, BigDecimal expectedAssists) {
        FixtureRow fixture = fixture(true, 2);
        ReadInterval fixtureRead = interval(readAt);
        SourceDocument live = new SourceDocument(("{\"elements\":["
                + live(98201, 90) + "," + live(98202, 90) + "]}").getBytes(StandardCharsets.UTF_8),
                interval(readAt.plusSeconds(2)));
        HistoryBatch home = new HistoryBatch(SEASON.id(), SEASON.code(), 98201,
                interval(readAt.plusSeconds(4)), List.of(history(98201, true, allOptional, expectedAssists)));
        HistoryBatch away = new HistoryBatch(SEASON.id(), SEASON.code(), 98202,
                interval(readAt.plusSeconds(4)), List.of(history(98202, false, true, BigDecimal.ZERO)));
        return new Inputs(fixture, fixtureRead, live, List.of(home, away));
    }

    private static FixtureRow fixture(boolean finished, int homeScore) {
        return fixture(finished, homeScore,
                "[{\"identifier\":\"minutes\",\"h\":[{\"element\":98201,\"value\":90}],"
                        + "\"a\":[{\"element\":98202,\"value\":90}]}]");
    }

    private static FixtureRow fixture(boolean finished, int homeScore, String stats) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("finished_provisional", true);
        fields.put("minutes", 90);
        fields.put("stats", stats);
        return new FixtureRow(SEASON.id(), SEASON.code(), 98101, 98101, 1, 9801, 9802,
                "Ashfield FC", "ASH", "Briar Town", "BRI", homeScore, 1, 2, 3,
                Instant.parse("2024-08-17T14:00:00Z"), true, finished, fields);
    }

    private static HistoryRow history(int player, boolean home, boolean allOptional,
                                      BigDecimal expectedAssists) {
        EnumSet<Component> components = allOptional ? EnumSet.allOf(Component.class)
                : EnumSet.of(Component.EXPECTED_GOALS);
        BigDecimal zero = BigDecimal.ZERO;
        return new HistoryRow(SEASON.id(), SEASON.code(), 98101, player, 1,
                home ? 9801 : 9802, home ? 9802 : 9801, home,
                Instant.parse("2024-08-17T14:00:00Z"), (short) 90, (short) 2, (short) 0,
                (short) 1, (short) 0, (short) 0, zero, allOptional ? expectedAssists : null,
                allOptional ? zero : null, (short) 0, (short) 1, (short) 0, (short) 0,
                (short) 0, (short) 0, allOptional ? zero : null, (short) 0, (short) 0,
                allOptional ? zero : null, allOptional ? zero : null, allOptional ? zero : null,
                allOptional ? zero : null, 75, 1, 0, 0, (short) 1,
                allOptional ? (short) 0 : null, allOptional ? (short) 0 : null,
                allOptional ? (short) 0 : null, allOptional ? (short) 0 : null, components);
    }

    private static String live(int player, int minutes) {
        return "{\"id\":" + player + ",\"explain\":[{\"fixture\":98101,\"stats\":["
                + "{\"identifier\":\"minutes\",\"value\":" + minutes
                + ",\"points\":2,\"points_modification\":0}]}]}";
    }

    private static ReadInterval interval(Instant instant) {
        return new ReadInterval(instant, instant.plusSeconds(1));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitSeasonLockWaiter() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND pid<>pg_backend_pid()
                      AND wait_event_type='Lock'
                      AND query LIKE '%FROM season WHERE is_current=true FOR UPDATE%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("verification did not wait for the season lock");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Inputs(FixtureRow fixture, ReadInterval fixtureRead, SourceDocument live,
                          List<HistoryBatch> histories) { }
    private record RawInputs(SourceDocument bootstrap, SourceDocument fixtures, SourceDocument live,
                             Map<Integer, SourceDocument> histories) { }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
