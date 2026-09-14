package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;

class OwnedSyncMonitoringDatabaseIntegrationTest {

    @Test
    void saturatedWorkPoolLeavesMonitoringResponsive() throws Exception {
        MockEnvironment environment = environment();
        try (OwnedSyncMonitoringDatabase monitor = new OwnedSyncMonitoringDatabase(environment);
             OwnedSyncMonitoringDatabase work = OwnedSyncMonitoringDatabase.work(environment);
             Connection first = work.dataSource().getConnection();
             Connection second = work.dataSource().getConnection();
             Connection third = work.dataSource().getConnection();
             Connection fourth = work.dataSource().getConnection()) {
            assertThat(work.dataSource().getMaximumPoolSize()).isEqualTo(4);
            assertThat(work.jdbc().getQueryTimeout()).isEqualTo(10);
            assertThat(work.transaction().getTimeout()).isEqualTo(30);
            assertThat(work.dataSource().getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("10");
            assertThat(monitor.dataSource().getMaximumPoolSize()).isEqualTo(2);
            assertThat(monitor.jdbc().getQueryTimeout()).isEqualTo(5);
            assertThat(monitor.transaction().getTimeout()).isEqualTo(5);
            assertThat(monitor.jdbc().queryForObject("SELECT 1", Integer.class)).isOne();
        }
    }

    @Test
    void lockWaitAndPoolAcquisitionAreActuallyBounded() throws Exception {
        MockEnvironment environment = environment();
        try (OwnedSyncMonitoringDatabase database = new OwnedSyncMonitoringDatabase(environment)) {
            database.jdbc().execute("CREATE TABLE IF NOT EXISTS fpl_sync_timeout_probe_test "
                    + "(id integer primary key, value integer not null)");
            database.jdbc().execute("ALTER TABLE fpl_sync_timeout_probe_test ENABLE ROW LEVEL SECURITY");
            database.jdbc().update("INSERT INTO fpl_sync_timeout_probe_test(id,value) VALUES (1,0) "
                    + "ON CONFLICT (id) DO UPDATE SET value=0");
            try (Connection blocker = database.dataSource().getConnection()) {
                blocker.setAutoCommit(false);
                blocker.createStatement().executeUpdate(
                        "UPDATE fpl_sync_timeout_probe_test SET value=1 WHERE id=1");
                Instant started = Instant.now();
                assertThatThrownBy(() -> database.jdbc().update(
                        "UPDATE fpl_sync_timeout_probe_test SET value=2 WHERE id=1"))
                        .isInstanceOf(DataAccessException.class);
                assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(6));
                blocker.rollback();
            }

            try (Connection first = database.dataSource().getConnection();
                 Connection second = database.dataSource().getConnection()) {
                Instant started = Instant.now();
                assertThatThrownBy(() -> database.dataSource().getConnection())
                        .isInstanceOf(java.sql.SQLException.class);
                assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(4));
            }
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", required("DATABASE_URL"))
                .withProperty("spring.datasource.username", required("DB_USER"))
                .withProperty("spring.datasource.password", required("DB_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
