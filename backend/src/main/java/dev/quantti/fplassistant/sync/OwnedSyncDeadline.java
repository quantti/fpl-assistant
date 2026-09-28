package dev.quantti.fplassistant.sync;

import java.time.Duration;
import java.util.Objects;

/** A reusable monotonic deadline; create it once at the enclosing method's entry. */
final class OwnedSyncDeadline {

    private final long deadlineNanos;

    private OwnedSyncDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static OwnedSyncDeadline after(Duration duration) {
        return after(duration, System.nanoTime());
    }

    static OwnedSyncDeadline after(Duration duration, long startedAtNanos) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        return new OwnedSyncDeadline(startedAtNanos + duration.toNanos());
    }

    long remainingNanos() {
        return remainingNanos(System.nanoTime());
    }

    long remainingNanos(long nowNanos) {
        return Math.max(0, deadlineNanos - nowNanos);
    }

    OwnedSyncDeadline cappedBy(Duration maximum) {
        Objects.requireNonNull(maximum);
        if (maximum.isNegative()) throw new IllegalArgumentException("maximum must not be negative");
        long now = System.nanoTime();
        long remaining = deadlineNanos - now;
        long cap = maximum.toNanos();
        return new OwnedSyncDeadline(now + Math.min(Math.max(0, remaining), cap));
    }

    OwnedSyncDeadline cappedBy(Duration maximum, long startedAtNanos) {
        Objects.requireNonNull(maximum);
        if (maximum.isNegative()) throw new IllegalArgumentException("maximum must not be negative");
        long remaining = deadlineNanos - startedAtNanos;
        long cap = maximum.toNanos();
        return new OwnedSyncDeadline(startedAtNanos + Math.min(Math.max(0, remaining), cap));
    }

    OwnedSyncDeadline minus(Duration reserve) {
        Objects.requireNonNull(reserve);
        if (reserve.isNegative()) throw new IllegalArgumentException("reserve must not be negative");
        return new OwnedSyncDeadline(deadlineNanos - reserve.toNanos());
    }

    static OwnedSyncDeadline earliest(OwnedSyncDeadline... deadlines) {
        return earliestAt(System.nanoTime(), deadlines);
    }

    static OwnedSyncDeadline earliestAt(long nowNanos, OwnedSyncDeadline... deadlines) {
        if (deadlines.length == 0) throw new IllegalArgumentException("at least one deadline is required");
        OwnedSyncDeadline earliest = Objects.requireNonNull(deadlines[0]);
        long shortestRemaining = earliest.deadlineNanos - nowNanos;
        for (int i = 1; i < deadlines.length; i++) {
            OwnedSyncDeadline candidate = Objects.requireNonNull(deadlines[i]);
            long remaining = candidate.deadlineNanos - nowNanos;
            if (remaining < shortestRemaining) {
                earliest = candidate;
                shortestRemaining = remaining;
            }
        }
        return earliest;
    }
}
