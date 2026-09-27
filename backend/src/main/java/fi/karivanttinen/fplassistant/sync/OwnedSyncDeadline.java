package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.util.Objects;

/** A reusable monotonic deadline; create it once at the enclosing method's entry. */
final class OwnedSyncDeadline {

    private final long deadlineNanos;

    private OwnedSyncDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static OwnedSyncDeadline after(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        return new OwnedSyncDeadline(System.nanoTime() + duration.toNanos());
    }

    long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    OwnedSyncDeadline cappedBy(Duration maximum) {
        Objects.requireNonNull(maximum);
        if (maximum.isNegative()) throw new IllegalArgumentException("maximum must not be negative");
        long now = System.nanoTime();
        long remaining = deadlineNanos - now;
        long cap = maximum.toNanos();
        return new OwnedSyncDeadline(now + Math.min(Math.max(0, remaining), cap));
    }

    OwnedSyncDeadline minus(Duration reserve) {
        Objects.requireNonNull(reserve);
        if (reserve.isNegative()) throw new IllegalArgumentException("reserve must not be negative");
        return new OwnedSyncDeadline(deadlineNanos - reserve.toNanos());
    }

    static OwnedSyncDeadline earliest(OwnedSyncDeadline... deadlines) {
        if (deadlines.length == 0) throw new IllegalArgumentException("at least one deadline is required");
        long earliest = Objects.requireNonNull(deadlines[0]).deadlineNanos;
        for (int i = 1; i < deadlines.length; i++) {
            earliest = Math.min(earliest, Objects.requireNonNull(deadlines[i]).deadlineNanos);
        }
        return new OwnedSyncDeadline(earliest);
    }
}
