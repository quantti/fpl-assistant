package dev.quantti.fplassistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SharedStatsSeedSafetyTest {

    @Test
    void localAndDocumentedGithubPostgresEndpointsAreTheOnlyAcceptedTestTargets() {
        assertThat(allowed("jdbc:postgresql://localhost:55432/postgres", null, null, null, null)).isTrue();
        assertThat(allowed("jdbc:postgresql://127.0.0.1:55432/postgres", null, null, null, null)).isTrue();
        assertThat(allowed("jdbc:postgresql://localhost:5432/postgres", "true", "true",
                "test and deploy", "test")).isTrue();

        assertThat(allowed("jdbc:postgresql://localhost:5432/postgres", null, null, null, null)).isFalse();
        assertThat(allowed("jdbc:postgresql://localhost:5433/postgres", null, null, null, null)).isFalse();
        assertThat(allowed("jdbc:postgresql://localhost:55433/postgres", null, null, null, null)).isFalse();
        assertThat(allowed("jdbc:postgresql://db.example.com:6543/postgres", null, null, null, null)).isFalse();
        assertThat(allowed("jdbc:postgresql://localhost:5432/postgres", "true", "true",
                "unknown workflow", "test")).isFalse();
        assertThat(SharedStatsSeed.isDisposableTestDatabase("jdbc:postgresql://localhost:55432/postgres",
                "postgres", "not-postgres", null, null, null, null)).isFalse();
    }

    @Test
    void preflightRejectsSharedDatabaseTargetsBeforeDatasourceConstruction() {
        assertThatThrownBy(() -> SharedStatsSeed.requireDisposableTestDatabase(
                "jdbc:postgresql://db.example.com:6543/postgres", "postgres", "postgres",
                null, null, null, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disposable test database");
    }

    private static boolean allowed(String url, String ci, String actions, String workflow, String job) {
        return SharedStatsSeed.isDisposableTestDatabase(url, "postgres", "postgres", ci,
                actions, workflow, job);
    }
}
