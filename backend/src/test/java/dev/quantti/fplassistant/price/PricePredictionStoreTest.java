package dev.quantti.fplassistant.price;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricePredictionStoreTest {

    @Test
    void identifiersAreAnExactAllowlistAndCannotMixEnvironments() {
        assertThat(PricePredictionSchema.Tables.test().forecastPlayer()).endsWith("_test");
        assertThat(PricePredictionSchema.Tables.production().forecastPlayer()).isEqualTo("fpl_price_forecast_player");
        assertThatThrownBy(() -> new PricePredictionSchema.Tables("fpl_price_forecast_capture; DROP TABLE player",
                "fpl_price_forecast_player", "fpl_price_change_capture", "fpl_price_change_event"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricePredictionSchema.Tables("fpl_price_forecast_capture_test",
                "fpl_price_forecast_player", "fpl_price_change_capture", "fpl_price_change_event"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricePredictionSchema.Tables("player",
                "fpl_price_forecast_player", "fpl_price_change_capture", "fpl_price_change_event"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructionIsInertAndLeaseTtlIsBoundedBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        var store = new PricePredictionStore(jdbc, transactions, PricePredictionSchema.Tables.test());
        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(-1),
                Duration.ofMinutes(5).plusMillis(1)}) {
            assertThatThrownBy(() -> store.acquireLease(ttl)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc, transactions);
    }

    @Test
    void malformedFingerprintIsRefusedBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        var store = new PricePredictionStore(jdbc, transactions, PricePredictionSchema.Tables.test());
        assertThatThrownBy(() -> store.publishForecast(null, "arbitrary text", null, () -> true))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, transactions);
    }
}
