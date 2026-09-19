package fi.karivanttinen.fplassistant.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Resolves the window a message allowance is counted in.
 *
 * <p>The deadlines of every captured season are read as one timeline. Reading only the newest
 * season would leave the preseason of a new season with no lower bound at all, and it would then
 * count the whole of the previous season against a user.
 *
 * <p><strong>Freshness is deliberately not checked.</strong> The schedule sync legitimately
 * refuses to write across the summer — a finished season, then the July guard — so requiring a
 * fresh capture would mean the off-season window, the reason this class exists, could never occur:
 * every off-season request would fall back to the rolling seven days instead. Deadlines also do
 * not change retroactively. A stale capture that missed a rescheduled deadline moves a quota
 * boundary by hours, where a stale fixture list makes a ranking horizon meaningless, which is why
 * {@code HorizonFixtures} judges freshness and this does not. Do not "fix" this onto
 * {@code ScheduleFreshnessCheck}.
 */
@Component
public class QuotaWindows {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindows.class);

    private final ScheduleSource schedule;
    private final int gameweekLimit;
    private final int offSeasonLimit;

    public QuotaWindows(ScheduleSource schedule,
                        @Value("${fpl.quota.free-messages-per-gameweek}") int gameweekLimit,
                        @Value("${fpl.quota.free-messages-off-season}") int offSeasonLimit) {
        this.schedule = schedule;
        this.gameweekLimit = gameweekLimit;
        this.offSeasonLimit = offSeasonLimit;
    }

    public QuotaWindow current(Instant asOf) {
        return derive(usableDeadlines(), asOf, gameweekLimit, offSeasonLimit);
    }

    /**
     * The deadlines a window may be cut from, or empty when they cannot be trusted — which
     * {@link #derive} turns into the fallback.
     *
     * <p>Only the newest season is validated. A request cannot fall inside a completed season, and
     * a missing old deadline can only start an off-season window earlier, lengthening a window
     * without changing its limit.
     *
     * <p>Read every time rather than cached. Two indexed reads of under a hundred rows are not
     * worth a cache that a deadline passing, or a capture landing, would have to be told about.
     */
    private List<ScheduleSource.Deadline> usableDeadlines() {
        try {
            List<ScheduleSource.Deadline> all = schedule.deadlines();
            if (all.isEmpty()) {
                return List.of();
            }
            Optional<ScheduleSource.Snapshot> snapshot = schedule.latest();
            if (snapshot.isEmpty()) {
                return List.of();
            }
            ScheduleSource.Snapshot selected = snapshot.get();
            // assessEvents takes EventRows. The capture instant is the snapshot's by construction
            // here, so the check reduces to the one thing a window depends on: a clean 1..38.
            List<ScheduleSource.EventRow> events = all.stream()
                    .filter(deadline -> deadline.season().equals(selected.season()))
                    .map(deadline -> new ScheduleSource.EventRow(deadline.gameweek(),
                            deadline.deadline(), selected.capturedAt()))
                    .toList();
            ScheduleSource.Completeness completeness =
                    ScheduleSource.assessEvents(snapshot, events);
            if (!completeness.complete()) {
                log.warn("quota window: {} events are unusable ({}); counting the rolling window",
                        selected.season(), completeness.reason());
                return List.of();
            }
            return all;
        } catch (DataAccessException e) {
            log.warn("quota window: schedule unreadable; counting the rolling window", e);
            return List.of();
        }
    }

    /** Pure, so the rule is tested without a database. */
    static QuotaWindow derive(List<ScheduleSource.Deadline> deadlines, Instant asOf,
                              int gameweekLimit, int offSeasonLimit) {
        if (deadlines.isEmpty()) {
            return new QuotaWindow(QuotaWindow.Kind.FALLBACK, null, null, gameweekLimit,
                    null, null);
        }

        ScheduleSource.Deadline previous = null;
        ScheduleSource.Deadline next = null;
        for (ScheduleSource.Deadline candidate : deadlines) {
            // Strictly after: at the deadline instant the round is already locked, so that instant
            // opens the new window rather than closing it late.
            if (candidate.deadline().isAfter(asOf)) {
                if (next == null || candidate.deadline().isBefore(next.deadline())) {
                    next = candidate;
                }
            } else if (previous == null || candidate.deadline().isAfter(previous.deadline())) {
                previous = candidate;
            }
        }

        if (previous != null && next != null && previous.season().equals(next.season())) {
            return new QuotaWindow(QuotaWindow.Kind.GAMEWEEK, previous.deadline(), next.deadline(),
                    gameweekLimit, next.gameweek(), next.season());
        }
        return new QuotaWindow(QuotaWindow.Kind.OFF_SEASON,
                previous == null ? null : previous.deadline(),
                next == null ? null : next.deadline(),
                offSeasonLimit, null, next != null ? next.season() : previous.season());
    }
}
