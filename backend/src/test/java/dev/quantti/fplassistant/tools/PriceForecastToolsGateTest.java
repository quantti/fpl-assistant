package dev.quantti.fplassistant.tools;

import java.net.URI;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.price.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceForecastToolsGateTest {
    private final PricePredictionStore store = mock(PricePredictionStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PriceForecastTools.class)
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(PlayerNameSearch.class, () -> mock(PlayerNameSearch.class))
            .withBean(PricePredictionStore.class, () -> store)
            .withBean(PricePredictionPermission.class, () -> mock(PricePredictionPermission.class))
            .withBean(PricePredictionConfiguration.Settings.class, () -> new PricePredictionConfiguration.Settings(
                    "2026-27", PricePredictionSchema.Tables.test(), URI.create("http://localhost:9/"),
                    URI.create("http://localhost:9/")));

    @Test
    void disabledDoesNotRegisterToolsOrReadStore() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PriceForecastTools.class);
            assertThat(context.getBeansOfType(FplTool.class)).isEmpty();
            verifyNoInteractions(store);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAGING", "ACTIVE"})
    void ownedModeDoesNotRegisterTools(String mode) {
        runner.withPropertyValues("fpl.price-prediction.enabled=true", "fpl.owned-sync.mode=" + mode)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PriceForecastTools.class);
                    assertThat(context.getBeansOfType(FplTool.class)).isEmpty();
                    verifyNoInteractions(store);
                });
    }

    @Test
    void confirmedImporterRegistersToolsWithoutReadingStore() {
        runner.withPropertyValues("fpl.price-prediction.enabled=true",
                "fpl.price-prediction.playing-season=2026-27", "fpl.owned-sync.mode=ACTIVE",
                "fpl.owned-sync.importer.enabled=true", "fpl.owned-sync.cutover-confirmed=true",
                "fpl.owned-sync.playing-season=2026-27").run(context -> {
            assertThat(context).hasSingleBean(PriceForecastTools.class);
            assertThat(context.getBeansOfType(FplTool.class)).hasSize(1);
            verifyNoInteractions(store);
        });
    }

    @Test
    void enabledOffRegistersOnlyWithDependenciesAndNoExternalIo() {
        runner.withPropertyValues("fpl.price-prediction.enabled=true", "fpl.owned-sync.mode=OFF")
                .run(context -> {
                    assertThat(context).hasSingleBean(PriceForecastTools.class);
                    assertThat(context.getBeansOfType(FplTool.class)).hasSize(1);
                    verifyNoInteractions(store);
                });
    }

    @Test
    void toolSchemaHasNoAccountOrTeamIdentifiers() {
        var methods = Arrays.stream(PriceForecastTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class)).toList();
        assertThat(methods).hasSize(3);
        assertThat(methods.stream().flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getName()).toList())
                .containsExactlyInAnyOrder("playerName", "direction", "limit", "playerName", "lastN");
    }
}
