package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.testsupport.TapasSeed;

class OwnedSyncFixtureLifecycleSafetyTest {

    private static final String OUTSIDE_DATABASE = "jdbc:postgresql://db.example.com:6543/postgres";
    private static final String LOCAL_DATABASE = "jdbc:postgresql://localhost:55432/postgres";
    private static final Class<?>[] FIXTURES = {
            OwnedSyncImporterContextIntegrationTest.class,
            IncrementalPlayerFixturePublisherIntegrationTest.class,
            CoalescedPlayerHistoryCollectorIntegrationTest.class,
            CompatibilityCatalogueWriterIntegrationTest.class
    };

    @Test
    void rejectedTargetsAndFailedBaselineCaptureLeaveActualTeardownsWithoutJdbcOperations() throws Exception {
        JdbcTemplate fakeJdbc = mock(JdbcTemplate.class);
        when(fakeJdbc.getDataSource()).thenReturn(mock(DataSource.class));
        Map<Field, Object> previous = new LinkedHashMap<>();
        AtomicInteger supplierCalls = new AtomicInteger();
        try {
            resetFixtureState(fakeJdbc, previous);
            for (Class<?> fixture : FIXTURES) {
                assertThatThrownBy(() -> initializeWithOutsideDatabase(fixture, supplierCalls))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Refusing to use outside the disposable test database");
            }
            assertThat(supplierCalls.get()).isZero();
            invokeActualTeardowns();
            verifyNoInteractions(fakeJdbc);

            resetFixtureState(fakeJdbc, previous);
            doThrow(new IllegalStateException("baseline capture failure"))
                    .when(fakeJdbc).queryForList(
                            "SELECT id FROM season WHERE is_current=true ORDER BY id", Integer.class);
            assertThatThrownBy(() -> IncrementalPlayerFixturePublisherIntegrationTest.initialize(
                    LOCAL_DATABASE, "postgres", "postgres", () -> fakeJdbc))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("baseline capture failure");
            clearInvocations(fakeJdbc);

            invokeActualTeardowns();
            verifyNoInteractions(fakeJdbc);
        } finally {
            for (Map.Entry<Field, Object> entry : previous.entrySet()) {
                entry.getKey().set(null, entry.getValue());
            }
        }
    }

    private static void initializeWithOutsideDatabase(Class<?> fixture, AtomicInteger supplierCalls)
            throws Exception {
        if (fixture == OwnedSyncImporterContextIntegrationTest.class) {
            OwnedSyncImporterContextIntegrationTest.initialize(OUTSIDE_DATABASE, "postgres", "postgres",
                    () -> { supplierCalls.incrementAndGet(); return mock(JdbcTemplate.class); });
        } else if (fixture == IncrementalPlayerFixturePublisherIntegrationTest.class) {
            IncrementalPlayerFixturePublisherIntegrationTest.initialize(OUTSIDE_DATABASE, "postgres", "postgres",
                    () -> { supplierCalls.incrementAndGet(); return mock(JdbcTemplate.class); });
        } else if (fixture == CoalescedPlayerHistoryCollectorIntegrationTest.class) {
            CoalescedPlayerHistoryCollectorIntegrationTest.initialize(OUTSIDE_DATABASE, "postgres", "postgres",
                    () -> { supplierCalls.incrementAndGet(); return mock(JdbcTemplate.class); });
        } else if (fixture == CompatibilityCatalogueWriterIntegrationTest.class) {
            CompatibilityCatalogueWriterIntegrationTest.initialize(OUTSIDE_DATABASE, "postgres", "postgres",
                    () -> { supplierCalls.incrementAndGet(); return mock(JdbcTemplate.class); });
        } else {
            throw new IllegalArgumentException("Unknown test fixture " + fixture);
        }
    }

    private static void resetFixtureState(JdbcTemplate fakeJdbc, Map<Field, Object> previous) throws Exception {
        for (Class<?> fixture : FIXTURES) {
            setStatic(fixture, "jdbc", fakeJdbc, previous);
            setStaticIfPresent(fixture, "disposableDatabaseValidated", false, previous);
            setStaticIfPresent(fixture, "seasonBaselineCaptured", false, previous);
            setStaticIfPresent(fixture, "seasonFlagsChanged", false, previous);
            setStaticIfPresent(fixture, "cleanupAuthorized", false, previous);
        }
    }

    private static void invokeActualTeardowns() {
        OwnedSyncImporterContextIntegrationTest.restoreSeedSeason();
        new OwnedSyncImporterContextIntegrationTest().restoreCurrentSeasons();
        IncrementalPlayerFixturePublisherIntegrationTest.cleanUp();
        CoalescedPlayerHistoryCollectorIntegrationTest.restoreCurrentSeason();
        new CoalescedPlayerHistoryCollectorIntegrationTest().cleanUp();
        CompatibilityCatalogueWriterIntegrationTest.restoreSeedSeason();
        new CompatibilityCatalogueWriterIntegrationTest().tearDown();
    }

    private static void setStaticIfPresent(Class<?> fixture, String name, Object value,
                                           Map<Field, Object> previous) throws Exception {
        try {
            setStatic(fixture, name, value, previous);
        } catch (NoSuchFieldException ignored) {
            // Some fixture classes do not have this lifecycle flag.
        }
    }

    private static void setStatic(Class<?> fixture, String name, Object value,
                                  Map<Field, Object> previous) throws Exception {
        Field field = fixture.getDeclaredField(name);
        field.setAccessible(true);
        previous.putIfAbsent(field, field.get(null));
        field.set(null, value);
    }
}
