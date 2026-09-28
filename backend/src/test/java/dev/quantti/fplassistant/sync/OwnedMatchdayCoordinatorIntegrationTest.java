package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RunState;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;

class OwnedMatchdayCoordinatorIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Instant NOW = Instant.parse("2097-09-19T03:01:00Z");
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private OwnedSyncStore store;
    private PlannerRuntimeRepository runtimeRepository;
    private PlannerInputRepository inputs;
    private CatalogueScheduleDispatcher catalogue;
    private HistoryFixtureDispatcher histories;
    private OwnedMatchdayCoordinator coordinator;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"), required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        if (jdbc.queryForObject("SELECT to_regclass('public.manager')::text", String.class) != null) {
            throw new IllegalStateException("Refusing coordinator tests against shared DB");
        }
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var activation = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        var fence = activation.activate("2097-98", OwnedSyncMode.STAGING);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        runtimeRepository = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
        inputs = mock(PlannerInputRepository.class);
        catalogue = mock(CatalogueScheduleDispatcher.class);
        histories = mock(HistoryFixtureDispatcher.class);
        when(inputs.load(any())).thenReturn(new PlannerInputRepository.PlannerSnapshot(
                Set.of(1, 2, 3), List.of(), List.of(), true));
        coordinator = new OwnedMatchdayCoordinator(new Season(1, "2097-98",
                LocalDate.of(2097, 8, 1), LocalDate.of(2098, 5, 31)),
                new AdaptiveMatchdayPlanner(clock), inputs, runtimeRepository, store,
                catalogue, histories, "instance-a");
    }

    @Test
    void stableDailySlotsEnqueueCorrectClassesOnceWithoutDemandBumps() {
        coordinator.tick();
        coordinator.tick();

        verify(catalogue, times(1)).enqueueCatalogue(any(), any());
        verify(catalogue, times(1)).enqueueSchedule(any(), any());
        verify(histories, times(1)).enqueuePlayerHistory(any(), org.mockito.ArgumentMatchers.eq(1), any());
        verify(histories, times(1)).enqueuePlayerHistory(any(), org.mockito.ArgumentMatchers.eq(2), any());
        verify(histories, times(1)).enqueuePlayerHistory(any(), org.mockito.ArgumentMatchers.eq(3), any());
        assertThat(runtimeRepository.loadSlots("2097-98")).hasSize(3)
                .noneSatisfy(row -> assertThat(row.slot().id()).contains(":player-range="));
        assertThat(runtimeRepository.loadRuntime("instance-a")).isPresent();
    }

    @Test
    void largeDailyPlayerSweepUsesBoundedDeterministicSlotsAndRestartDoesNotDuplicate() {
        Set<Integer> players = IntStream.rangeClosed(1, 601).boxed().collect(Collectors.toUnmodifiableSet());
        when(inputs.load(any())).thenReturn(new PlannerInputRepository.PlannerSnapshot(
                players, List.of(), List.of(), true));

        OwnedMatchdayCoordinator.TickResult first = coordinator.tick();
        List<PlannerRuntimeRepository.DurableSlot> playerSlots = runtimeRepository.loadSlots("2097-98")
                .stream().filter(row -> row.slot().kind()
                        == AdaptiveMatchdayPlanner.WorkKind.FULL_PLAYER_RECONCILE).toList();

        assertThat(first.materializedSlots()).isEqualTo(21);
        assertThat(playerSlots).hasSize(19);
        assertThat(playerSlots).allSatisfy(row -> {
            assertThat(row.slot().id()).contains(":player-range=");
            assertThat(row.slot().playerIds()).hasSizeLessThanOrEqualTo(32);
            assertThat(row.slot().estimatedRequests()).isEqualTo(row.slot().playerIds().size());
        });
        assertThat(playerSlots.stream().flatMap(row -> row.slot().playerIds().stream()).distinct())
                .hasSize(601);
        verify(histories, times(601)).enqueuePlayerHistory(any(), any(Integer.class), any());

        assertThat(coordinator.tick().materializedSlots()).isZero();
        verifyNoMoreInteractions(histories);
        assertThat(runtimeRepository.loadSlots("2097-98")).hasSize(21);
    }

    @Test
    void partialPlayerMaterializationRollsBackOnlyItsChunkAndResumesAfterRestart() {
        Set<Integer> players = IntStream.rangeClosed(1, 65).boxed().collect(Collectors.toUnmodifiableSet());
        when(inputs.load(any())).thenReturn(new PlannerInputRepository.PlannerSnapshot(
                players, List.of(), List.of(), true));
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(histories.enqueuePlayerHistory(any(), any(Integer.class), any())).thenAnswer(call -> {
            int player = call.getArgument(1);
            var run = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:" + player,
                    "player-history:" + player, NOW);
            if (player == 33 && failOnce.getAndSet(false)) throw new IllegalStateException("interrupted chunk");
            return run;
        });
        assertThatThrownBy(() -> coordinator.tick()).hasMessageContaining("interrupted chunk");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.plannerSlot()
                + " WHERE kind='FULL_PLAYER_RECONCILE'", Integer.class)).isOne();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var restarted = new OwnedMatchdayCoordinator(new Season(1, "2097-98",
                LocalDate.of(2097, 8, 1), LocalDate.of(2098, 5, 31)), new AdaptiveMatchdayPlanner(clock),
                inputs, new PlannerRuntimeRepository(jdbc, tx, clock, TABLES),
                new OwnedSyncStore(jdbc, tx, clock, TABLES), catalogue, histories, "restarted");
        assertThat(restarted.tick().materializedSlots()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isEqualTo(65);
        assertThat(restarted.tick().materializedSlots()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isEqualTo(65);
    }

    @Test
    void matchdayPlayerRefreshIsBoundedLikeTheDailySweep() {
        Set<Integer> players = IntStream.rangeClosed(1, 601).boxed().collect(Collectors.toUnmodifiableSet());
        Instant kickoff = Instant.parse("2097-09-19T14:00:00Z");
        when(inputs.load(any())).thenReturn(new PlannerInputRepository.PlannerSnapshot(players,
                List.of(new AdaptiveMatchdayPlanner.Fixture(9, 1, kickoff, true, false, false,
                        kickoff, players)), List.of(), true));
        Clock clock = Clock.fixed(kickoff, ZoneOffset.UTC);
        var matchday = new OwnedMatchdayCoordinator(new Season(1, "2097-98",
                LocalDate.of(2097, 8, 1), LocalDate.of(2098, 5, 31)), new AdaptiveMatchdayPlanner(clock),
                inputs, new PlannerRuntimeRepository(jdbc, tx, clock, TABLES),
                new OwnedSyncStore(jdbc, tx, clock, TABLES), catalogue, histories, "matchday");
        matchday.tick();
        var affected = runtimeRepository.loadSlots("2097-98").stream().filter(row ->
                row.slot().kind() == AdaptiveMatchdayPlanner.WorkKind.AFFECTED_HISTORY).toList();
        assertThat(affected).hasSize(19).allSatisfy(row ->
                assertThat(row.slot().playerIds()).hasSizeLessThanOrEqualTo(32));
        assertThat(affected.stream().mapToInt(row -> row.slot().estimatedRequests()).sum()).isEqualTo(601);
    }

    @Test
    void workerRoutesEverySupportedPersistedKindToItsDispatcher() {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue-price", "catalogue-price", NOW);
        assertThat(coordinator.dispatchOne("one")).isTrue();
        verify(catalogue).process(any(), any());

        store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:1", "player-history:1", NOW);
        assertThat(coordinator.dispatchOne("two")).isTrue();
        verify(histories).process(any(), any());

        jdbc.update("UPDATE " + TABLES.run() + " SET state='COMPLETED', lease_owner=NULL, lease_token=NULL,"
                + " lease_expires_at=NULL WHERE state='LEASED'");
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:9", "fixture:9", NOW);
        assertThat(coordinator.dispatchOne("three")).isTrue();
        verify(histories, times(2)).process(any(), any());
    }

    @Test
    void unsupportedSourceStatusIsFailedNotReportedComplete() {
        var run = store.enqueue(SyncKind.SOURCE_STATUS, "2097-98", "unsupported", "unsupported", NOW);
        coordinator.dispatchOne("one");
        String state = jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, run.generation());
        assertThat(state).isEqualTo(RunState.FAILED.name());
    }

    @Test
    void dispatcherFailureCannotFalselyCompleteTheLease() {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue-price", "catalogue-price", NOW);
        when(catalogue.process(any(), any())).thenThrow(new IllegalStateException("partial fetch"));

        assertThatThrownBy(() -> coordinator.dispatchOne("one")).isInstanceOf(IllegalStateException.class);
        String state = jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, run.generation());
        assertThat(state).isEqualTo(RunState.LEASED.name());
        assertThat(store.acquireForSeason("2097-98", "other", Duration.ofMinutes(1))).isEmpty();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
