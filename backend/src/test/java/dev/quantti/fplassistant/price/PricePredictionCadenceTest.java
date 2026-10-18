package dev.quantti.fplassistant.price;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import dev.quantti.fplassistant.price.PricePredictionStore.*;
import static dev.quantti.fplassistant.price.PricePredictionCadence.*;
import static dev.quantti.fplassistant.price.PricePredictionFixtures.*;
import static org.assertj.core.api.Assertions.*;

class PricePredictionCadenceTest {
    @ParameterizedTest
    @CsvSource({
            "2026-10-18T14:50:00Z,FALLBACK,false", "2026-10-18T14:30:00Z,FALLBACK,true",
            "2026-10-18T15:00:00Z,FREQUENT,true", "2026-10-18T15:10:00Z,FREQUENT,true",
            "2026-10-18T15:10:05Z,FREQUENT,false", "2026-10-18T23:50:00Z,FREQUENT,true",
            "2026-10-19T00:50:00Z,FREQUENT,true", "2026-10-19T01:00:00Z,FALLBACK,true",
            "2026-10-19T01:10:00Z,FALLBACK,false", "2026-10-25T01:50:00Z,FREQUENT,true",
            "2026-10-25T02:00:00Z,FALLBACK,true", "2026-03-29T00:50:00Z,FREQUENT,true",
            "2026-03-29T01:00:00Z,FALLBACK,true"})
    void slotsAndLondonDstFallback(String date, Phase expected, boolean due) {
        Instant now = Instant.parse(date);
        assertThat(phase(now, null)).isEqualTo(expected);
        assertThat(due(now, expected)).isEqualTo(due);
    }

    @Test
    void persistedRolloverSurvivesRestartAndAgeWithoutAnyPriceChange() {
        var initial = parse(bootstrap());
        Instant read = TARGET.plusSeconds(600);
        var next = new PricePredictionSnapshot(SEASON, read, read.minusSeconds(60), TARGET.plusSeconds(86400),
                initial.teams(), initial.players());
        var view = new ForecastView(next, new Publication(2, read, read, "a".repeat(64)), TARGET, 2);
        assertThat(phase(read.plusSeconds(3600), view)).isEqualTo(Phase.ROLLOVER_CONFIRMED);
        assertThat(due(read, phase(read, view))).isFalse();
        assertThat(phase(Instant.parse("2026-10-19T15:00:00Z"), view)).isEqualTo(Phase.FREQUENT);
    }

    @Test
    void expiredOrStaleSourceDoesNotConfirmRollover() {
        var snapshot = parse(bootstrap());
        var view = new ForecastView(snapshot, new Publication(1, READ, READ, "a".repeat(64)), TARGET, 2);
        assertThat(phase(TARGET.plusSeconds(600), view)).isEqualTo(Phase.FREQUENT);
    }

    @Test
    void checkedTimeRenewsPriceButNeverPredictorAge() {
        var snapshot = parse(bootstrap());
        Instant now = READ.plusSeconds(3600);
        var result = PricePredictionEvidence.assess(snapshot, now, 1, now, true);
        assertThat(result.currentPriceUsable()).isTrue();
        assertThat(result.forecastUsable()).isFalse();
        assertThat(result.refusal()).isEqualTo(PricePredictionEvidence.Refusal.SOURCE_AGE);
        assertThat(result.delayed()).isTrue();
    }
}
