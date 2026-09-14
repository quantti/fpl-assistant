package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

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
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        runtimeRepository = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
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
        assertThat(runtimeRepository.loadSlots("2097-98")).hasSize(3);
        assertThat(runtimeRepository.loadRuntime("instance-a")).isPresent();
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
