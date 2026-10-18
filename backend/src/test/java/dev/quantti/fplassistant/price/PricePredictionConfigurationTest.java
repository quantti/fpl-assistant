package dev.quantti.fplassistant.price;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricePredictionConfigurationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PricePredictionConfiguration.class)
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void defaultDisabledHasNoSchemaWorkerHttpOrStore() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PricePredictionStore.class)
                    .doesNotHaveBean(PricePredictionWorker.class).doesNotHaveBean(PricePredictionHttp.class);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAGING", "ACTIVE", "invalid"})
    void ownedModeDeniesEverythingEvenWithFlag(String mode) {
        runner.withPropertyValues("fpl.price-prediction.enabled=true", "fpl.owned-sync.mode=" + mode)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PricePredictionStore.class)
                            .doesNotHaveBean(PricePredictionWorker.class).doesNotHaveBean(PricePredictionHttp.class);
                    verify(jdbc).afterPropertiesSet();
                    verifyNoMoreInteractions(jdbc);
                });
    }

    @Test
    void confirmedImporterRegistersIndependentForecastRuntime() {
        runner.withPropertyValues("spring.profiles.active=test", "fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=2026-27", "fpl.owned-sync.mode=ACTIVE",
                "fpl.owned-sync.importer.enabled=true", "fpl.owned-sync.cutover-confirmed=true",
                "fpl.owned-sync.playing-season=2026-27").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PricePredictionStore.class)
                    .hasSingleBean(PricePredictionWorker.class).hasSingleBean(PricePredictionHttp.class);
            assertThat(context.getBean(PricePredictionPermission.class).permitted()).isTrue();
        });
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,false,true,2026-27", "ACTIVE,true,false,2026-27",
            "ACTIVE,true,true,2025-26", "ACTIVE,true,true,''", "STAGING,true,true,2026-27",
            "invalid,true,true,2026-27"})
    void incompleteOrMismatchedImporterDoesNotCreateForecastRuntime(
            String mode, boolean importer, boolean cutover, String season) {
        runner.withPropertyValues("fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=2026-27", "fpl.owned-sync.mode=" + mode,
                "fpl.owned-sync.importer.enabled=" + importer, "fpl.owned-sync.cutover-confirmed=" + cutover,
                "fpl.owned-sync.playing-season=" + season).run(context -> {
            assertThat(context).doesNotHaveBean(PricePredictionStore.class)
                    .doesNotHaveBean(PricePredictionWorker.class).doesNotHaveBean(PricePredictionHttp.class);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }

    @Test
    void enabledRequiresExplicitPlayingSeasonBeforeSchema() {
        runner.withPropertyValues("fpl.price-prediction.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }

    @Test
    void enabledTestProfileForcesTestTablesAndStubEvenWithoutTestProperties() {
        runner.withPropertyValues("spring.profiles.active=test", "fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=2026-27").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PricePredictionStore.class);
            var settings = context.getBean(PricePredictionConfiguration.Settings.class);
            assertThat(settings.tables()).isEqualTo(PricePredictionSchema.Tables.test());
            assertThat(settings.bootstrap().toString()).isEqualTo("http://localhost:9/bootstrap-static/");
            assertThat(settings.history().toString()).isEqualTo("http://localhost:9/stats/price-changes/");
            assertThat(context.getBean(PricePredictionPermission.class).permitted()).isTrue();
        });
    }

    @Test
    void testProfileRejectsRemoteOverrideBeforeCreatingSchema() {
        runner.withPropertyValues("spring.profiles.active=test", "fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=2026-27",
                "fpl.price-prediction.test-base-url=https://fantasy.premierleague.com/api/")
                .run(context -> {
                    assertThat(context).hasFailed();
                    verify(jdbc).afterPropertiesSet();
                    verifyNoMoreInteractions(jdbc);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-26", "2026/27", "9999-00", "1999-00"})
    void invalidPlayingSeasonFailsBeforeSchema(String season) {
        runner.withPropertyValues("fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=" + season).run(context -> {
            assertThat(context).hasFailed();
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }
}
