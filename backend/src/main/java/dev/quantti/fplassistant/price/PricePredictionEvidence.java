package dev.quantti.fplassistant.price;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import dev.quantti.fplassistant.price.PricePredictionSnapshot.Player;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.State;

public final class PricePredictionEvidence {

    private static final Duration MAX_AGE = Duration.ofMinutes(45);
    private static final Duration WARNING_AGE = Duration.ofMinutes(20);

    private PricePredictionEvidence() { }

    public enum Refusal { NONE, CAPTURE_AGE, SOURCE_AGE, TARGET, PLAYER_STATE }

    public record Assessment(boolean currentPriceUsable, boolean forecastUsable,
                             boolean delayed, Refusal refusal) { }

    public static Assessment assess(PricePredictionSnapshot snapshot, int elementId,
                                    Instant asOf, boolean frequentPhase) {
        return assess(snapshot, snapshot.fetchedAt(), elementId, asOf, frequentPhase);
    }

    public static Assessment assess(PricePredictionSnapshot snapshot, Instant checkedAt, int elementId,
                                    Instant asOf, boolean frequentPhase) {
        Player player = snapshot.players().get(elementId);
        boolean captureUsable = checkedAt != null && !checkedAt.isBefore(snapshot.fetchedAt())
                && withinAge(checkedAt, asOf, MAX_AGE);
        boolean priceUsable = captureUsable && player != null;
        LocalTime utc = asOf.atOffset(ZoneOffset.UTC).toLocalTime();
        boolean firstEveningSlot = !utc.isBefore(LocalTime.of(15, 0))
                && utc.isBefore(LocalTime.of(15, 10));
        boolean delayed = frequentPhase && !firstEveningSlot
                && (!withinAge(checkedAt, asOf, WARNING_AGE)
                || !withinAge(snapshot.sourceUpdatedAt(), asOf, WARNING_AGE));
        Refusal refusal;
        if (!captureUsable) refusal = Refusal.CAPTURE_AGE;
        else if (!withinAge(snapshot.sourceUpdatedAt(), asOf, MAX_AGE)
                || snapshot.sourceUpdatedAt().isAfter(snapshot.fetchedAt())) refusal = Refusal.SOURCE_AGE;
        else if (snapshot.target() == null || !snapshot.target().isAfter(asOf)
                || !snapshot.target().isAfter(snapshot.sourceUpdatedAt())) refusal = Refusal.TARGET;
        else if (player == null || player.forecast().state() != State.READY) refusal = Refusal.PLAYER_STATE;
        else refusal = Refusal.NONE;
        return new Assessment(priceUsable, refusal == Refusal.NONE, delayed, refusal);
    }

    public static boolean cycleRolledOver(Instant previousTarget, PricePredictionSnapshot next,
                                          Instant asOf) {
        return previousTarget != null && !previousTarget.isAfter(asOf)
                && next.target() != null && next.target().isAfter(previousTarget)
                && next.target().isAfter(asOf)
                && withinAge(next.fetchedAt(), asOf, MAX_AGE)
                && withinAge(next.sourceUpdatedAt(), asOf, MAX_AGE)
                && !next.sourceUpdatedAt().isBefore(previousTarget)
                && !next.sourceUpdatedAt().isAfter(next.fetchedAt());
    }

    private static boolean withinAge(Instant timestamp, Instant asOf, Duration maximum) {
        if (timestamp == null || timestamp.isAfter(asOf)) return false;
        return Duration.between(timestamp, asOf).compareTo(maximum) <= 0;
    }
}
