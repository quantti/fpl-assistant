package fi.karivanttinen.fplassistant.price;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import fi.karivanttinen.fplassistant.price.PricePredictionStore.ForecastView;

public final class PricePredictionCadence {
    public enum Phase { FREQUENT, ROLLOVER_CONFIRMED, FALLBACK }

    private PricePredictionCadence() { }

    public static Instant cycleStart(Instant now) {
        Instant start = now.atOffset(ZoneOffset.UTC).toLocalDate().atTime(15, 0).toInstant(ZoneOffset.UTC);
        return start.isAfter(now) ? start.minus(1, ChronoUnit.DAYS) : start;
    }

    public static Phase phase(Instant now, ForecastView view) {
        Instant start = cycleStart(now);
        if (view != null && view.previousTarget() != null && !view.previousTarget().isBefore(start)
                && !view.snapshot().fetchedAt().isAfter(now)
                && PricePredictionEvidence.cycleRolledOver(view.previousTarget(), view.snapshot(),
                view.snapshot().fetchedAt())) return Phase.ROLLOVER_CONFIRMED;
        Instant fallback = start.atZone(ZoneId.of("Europe/London")).toLocalDate().plusDays(1)
                .atTime(2, 0).atZone(ZoneId.of("Europe/London")).toInstant();
        return now.isBefore(fallback) ? Phase.FREQUENT : Phase.FALLBACK;
    }

    public static boolean due(Instant now, Phase phase) {
        var utc = now.atOffset(ZoneOffset.UTC);
        return utc.getSecond() < 5 && utc.getMinute() % (phase == Phase.FREQUENT ? 10 : 30) == 0;
    }

    public static Instant slot(Instant now) {
        return now.truncatedTo(ChronoUnit.MINUTES);
    }
}
