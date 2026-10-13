package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** Pure candidate policy for bounded overdue fixture recovery; it performs no admission or I/O. */
public final class SharedFixtureRecoveryPolicy {

    public static final ZoneId UK = ZoneId.of("Europe/London");
    public static final Duration FOLLOW_UP_LIMIT = Duration.ofHours(12);
    private static final LocalTime RECOVERY_BOUNDARY = LocalTime.of(3, 0);

    private SharedFixtureRecoveryPolicy() { }

    /**
     * Describes recovery eligibility only. A reserve decision is not an HTTP permit: callers still
     * apply request budgets, unresolved-exchange fences and other admission gates before reserving.
     */
    public static Decision decide(Input input) {
        Objects.requireNonNull(input);
        if (input.currentlyVerified() || input.firstFinishedObservedAt() == null
                || input.now().isBefore(input.firstFinishedObservedAt().plus(FOLLOW_UP_LIMIT))) {
            return Decision.normal();
        }
        if (input.sourceStatus() == SourceStatus.UNKNOWN) return Decision.waitForParent();
        if (input.sourceStatus() == SourceStatus.UNFINISHED) return Decision.waitForSource();

        boolean publishedDuringEpisode = input.recoveryDay() != null
                && input.recoveryPublishedAt() != null
                && !input.recoveryPublishedAt().isBefore(input.recoveryStartedAt())
                && !input.recoveryPublishedAt().isAfter(input.now());
        boolean episodePending = input.recoveryDay() != null && !publishedDuringEpisode;
        boolean activeEpisode = episodePending && !input.now().isBefore(input.recoveryStartedAt())
                && input.now().isBefore(input.recoveryUntil());
        boolean futureEpisode = episodePending && input.now().isBefore(input.recoveryStartedAt());

        Instant blockedUntil = laterFuture(input.now(), input.transportRetryAt(), input.cooldownUntil());
        blockedUntil = laterFuture(input.now(), blockedUntil, input.dependencyRetryAt());
        // Only a calendar-policy date is bypassable before a reserved episode.
        // Independent-batch/source-time waits are DEPENDENCY dates and must always be honored.
        if (input.dueReason() != DueReason.POLICY || activeEpisode || futureEpisode) {
            blockedUntil = laterFuture(input.now(), blockedUntil, input.nextDueAt());
        }

        if (activeEpisode || futureEpisode) {
            Instant eligibleAt = futureEpisode ? input.recoveryStartedAt() : input.now();
            Instant nextAttempt = laterFuture(input.now(), eligibleAt, blockedUntil);
            if (nextAttempt == null || nextAttempt.isBefore(input.recoveryUntil())) {
                if (activeEpisode && nextAttempt == null) {
                    return new Decision(Action.CONTINUE_EPISODE, null, input.recoveryDay(),
                            input.recoveryStartedAt(), input.recoveryUntil());
                }
                return waiting(input, nextAttempt);
            }
            return waiting(input, nextGrantAfterConsumedEpisode(input, nextAttempt));
        }

        Instant nextAttempt = blockedUntil == null ? input.now() : blockedUntil;
        if (input.recoveryDay() != null
                && !input.recoveryDay().isBefore(recoveryDayAt(nextAttempt))) {
            nextAttempt = nextRecoveryBoundary(nextAttempt);
        }
        if (nextAttempt.isAfter(input.now())) return waiting(input, nextAttempt);

        LocalDate today = recoveryDayAt(input.now());
        return new Decision(Action.RESERVE_EPISODE, null, today, input.now(),
                input.now().plus(FOLLOW_UP_LIMIT));
    }

    /** The UK date naming the 03:00-to-03:00 recovery day containing {@code instant}. */
    public static LocalDate recoveryDayAt(Instant instant) {
        var local = Objects.requireNonNull(instant).atZone(UK);
        return local.toLocalTime().isBefore(RECOVERY_BOUNDARY)
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    /** The first UK 03:00 boundary strictly after {@code instant}. */
    public static Instant nextRecoveryBoundary(Instant instant) {
        var local = Objects.requireNonNull(instant).atZone(UK);
        LocalDate date = local.toLocalDate();
        Instant todayAt = date.atTime(RECOVERY_BOUNDARY).atZone(UK).toInstant();
        return todayAt.isAfter(instant) ? todayAt
                : date.plusDays(1).atTime(RECOVERY_BOUNDARY).atZone(UK).toInstant();
    }

    private static Instant laterFuture(Instant now, Instant first, Instant second) {
        Instant latest = null;
        if (first != null && first.isAfter(now)) latest = first;
        if (second != null && second.isAfter(now) && (latest == null || second.isAfter(latest))) {
            latest = second;
        }
        return latest;
    }

    private static Instant nextGrantAfterConsumedEpisode(Input input, Instant notBefore) {
        Instant candidate = notBefore.isBefore(input.recoveryUntil()) ? input.recoveryUntil() : notBefore;
        return input.recoveryDay().isBefore(recoveryDayAt(candidate))
                ? candidate : nextRecoveryBoundary(candidate);
    }

    private static Decision waiting(Input input, Instant notBefore) {
        return new Decision(Action.WAIT, notBefore, input.recoveryDay(),
                input.recoveryStartedAt(), input.recoveryUntil());
    }

    public enum SourceStatus { FINISHED, UNFINISHED, UNKNOWN }

    /**
     * POLICY is a calendar-only recovery deferral. DEPENDENCY includes missing histories and
     * independent-batch/source-time follow-ups. Null means a legacy unclassified retry cause.
     */
    public enum DueReason { POLICY, DEPENDENCY, TRANSPORT }

    public enum Action { NORMAL, WAIT, WAIT_FOR_PARENT, WAIT_FOR_SOURCE, RESERVE_EPISODE, CONTINUE_EPISODE }

    /**
     * {@code recoveryPublishedAt}, when present, must come from a successfully published ACTIVE
     * run and receipt for this exact season/fixture; it may remain available after invalidation.
     * STAGING, queued work and a verification decision alone are not episode completion evidence.
     * {@code dependencyRetryAt} remains an independent gate even when {@code nextDueAt} is POLICY.
     */
    public record Input(Instant now, SourceStatus sourceStatus, boolean currentlyVerified,
                        Instant firstFinishedObservedAt, Instant nextDueAt, DueReason dueReason,
                        Instant transportRetryAt, Instant cooldownUntil, LocalDate recoveryDay,
                        Instant recoveryStartedAt, Instant recoveryUntil, Instant recoveryPublishedAt,
                        Instant dependencyRetryAt) {
        public Input {
            Objects.requireNonNull(now);
            Objects.requireNonNull(sourceStatus);
            boolean noEpisode = recoveryDay == null && recoveryStartedAt == null && recoveryUntil == null;
            boolean completeEpisode = recoveryDay != null && recoveryStartedAt != null && recoveryUntil != null;
            if (!noEpisode && !completeEpisode) {
                throw new IllegalArgumentException("Recovery day and episode interval must be stored together");
            }
            if (completeEpisode && !recoveryStartedAt.isBefore(recoveryUntil)) {
                throw new IllegalArgumentException("Recovery episode must have a positive interval");
            }
            if (completeEpisode && Duration.between(recoveryStartedAt, recoveryUntil)
                    .compareTo(FOLLOW_UP_LIMIT) > 0) {
                throw new IllegalArgumentException("Recovery episode cannot exceed twelve hours");
            }
            if (completeEpisode && !recoveryDay.equals(recoveryDayAt(recoveryStartedAt))) {
                throw new IllegalArgumentException("Recovery day must name the episode start's UK recovery day");
            }
        }
    }

    /**
     * A NORMAL decision has null recovery metadata: callers must use their existing eligibility
     * rules and preserve stored recovery metadata. It is neither an HTTP grant nor a request to
     * clear a consumed-day marker.
     */
    public record Decision(Action action, Instant notBefore, LocalDate recoveryDay,
                           Instant episodeStartedAt, Instant episodeUntil) {
        public Decision {
            Objects.requireNonNull(action);
            boolean completeEpisode = recoveryDay != null && episodeStartedAt != null && episodeUntil != null;
            if ((action == Action.RESERVE_EPISODE || action == Action.CONTINUE_EPISODE) && !completeEpisode) {
                throw new IllegalArgumentException("Episode decisions require complete metadata");
            }
            if (action == Action.WAIT && notBefore == null) {
                throw new IllegalArgumentException("A wait decision requires its next due instant");
            }
            if ((action == Action.WAIT_FOR_PARENT || action == Action.WAIT_FOR_SOURCE)
                    && (notBefore != null || completeEpisode)) {
                throw new IllegalArgumentException("A source dependency wait has no recovery metadata or due time");
            }
        }

        private static Decision normal() {
            return new Decision(Action.NORMAL, null, null, null, null);
        }

        private static Decision waitForParent() {
            return new Decision(Action.WAIT_FOR_PARENT, null, null, null, null);
        }

        private static Decision waitForSource() {
            return new Decision(Action.WAIT_FOR_SOURCE, null, null, null, null);
        }
    }
}
