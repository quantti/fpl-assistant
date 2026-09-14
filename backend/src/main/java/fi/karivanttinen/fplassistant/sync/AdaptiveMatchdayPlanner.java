package fi.karivanttinen.fplassistant.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Pure due-time calculation for the owned matchday sync. It performs no I/O or activation. */
public final class AdaptiveMatchdayPlanner {

    public static final ZoneId UK = ZoneId.of("Europe/London");
    private static final Duration SOURCE_FAST = Duration.ofMinutes(10);
    private static final Duration SOURCE_SLOW = Duration.ofMinutes(30);
    private static final Duration HISTORY = Duration.ofMinutes(30);
    private static final Duration BEFORE_FIRST = Duration.ofMinutes(60);
    private static final Duration AFTER_LAST = Duration.ofHours(4).plusMinutes(10);
    private static final Duration UNFINISHED_OVERDUE = Duration.ofHours(3);
    private static final Duration FOLLOW_UP_LIMIT = Duration.ofHours(12);
    private static final List<Duration> CORRECTIONS = List.of(
            Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(48));

    private final Clock clock;

    public AdaptiveMatchdayPlanner(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public Plan plan(Input input) {
        Objects.requireNonNull(input);
        Instant now = clock.instant();
        TreeMap<String, WorkSlot> due = new TreeMap<>();
        Next next = new Next();

        addDaily(input, now, due, next);
        addMatchdays(input, now, due, next);
        retainNewest(due, WorkKind.MATCHDAY_SOURCE);
        List<Alert> alerts = addFixtureWork(input, now, due, next);
        for (WorkSlot pending : input.persistedPending()) addCandidate(pending, now, due, next);

        List<WorkSlot> ordered = due.values().stream()
                .sorted(Comparator.comparing(WorkSlot::dueAt).thenComparing(WorkSlot::id)).toList();
        return new Plan(ordered, Optional.ofNullable(next.value), alerts);
    }

    private static void addDaily(Input input, Instant now, Map<String, WorkSlot> due, Next next) {
        LocalDate today = now.atZone(UK).toLocalDate();
        daily(input, WorkKind.DAILY_CATALOGUE, today, LocalTime.of(2, 0), 2, now, due, next);
        daily(input, WorkKind.DAILY_SCHEDULE, today, LocalTime.of(2, 10), 2, now, due, next);
        daily(input, WorkKind.FULL_PLAYER_RECONCILE, today, LocalTime.of(3, 0),
                input.activePlayerCount() + 2, now, due, next);
    }

    private static void daily(Input input, WorkKind kind, LocalDate today, LocalTime time,
                              int requests, Instant now, Map<String, WorkSlot> due, Next next) {
        Instant todayAt = uk(today, time);
        LocalDate selected = todayAt.isAfter(now) ? today.minusDays(1) : today;
        WorkSlot newest = slot(input.season(), kind, uk(selected, time), Set.of(), Set.of(),
                SlotReason.ROUTINE, requests, selected.toString());
        addCandidate(newest, now, due, next);
        Instant future = todayAt.isAfter(now) ? todayAt : uk(today.plusDays(1), time);
        next.consider(future);
    }

    private static void addMatchdays(Input input, Instant now, Map<String, WorkSlot> due, Next next) {
        Map<LocalDate, List<Fixture>> days = new HashMap<>();
        for (Fixture fixture : input.fixtures()) {
            if (fixture.kickoff() != null) {
                days.computeIfAbsent(fixture.kickoff().atZone(UK).toLocalDate(), ignored -> new ArrayList<>())
                        .add(fixture);
            }
        }
        for (Map.Entry<LocalDate, List<Fixture>> entry : days.entrySet()) {
            List<Fixture> fixtures = entry.getValue();
            Instant first = fixtures.stream().map(Fixture::kickoff).min(Instant::compareTo).orElseThrow();
            Instant last = fixtures.stream().map(Fixture::kickoff).max(Instant::compareTo).orElseThrow();
            Instant dayStart = uk(entry.getKey(), LocalTime.of(8, 0));
            Instant fastStart = first.minus(BEFORE_FIRST);
            Instant end = last.plus(AFTER_LAST);
            Instant currentUkDayStart = uk(now.atZone(UK).toLocalDate(), LocalTime.MIDNIGHT);
            if (end.isBefore(currentUkDayStart)) continue;
            Set<Integer> allFixtures = ids(fixtures);

            if (dayStart.isBefore(fastStart)) {
                addNewestCadence(input.season(), WorkKind.MATCHDAY_SOURCE, SlotReason.MATCHDAY,
                        dayStart, fastStart.minusMillis(1), SOURCE_SLOW, allFixtures, Set.of(), 2,
                        now, due, next);
            }
            Instant sourceStart = fastStart.isBefore(dayStart) ? dayStart : fastStart;
            addNewestCadence(input.season(), WorkKind.MATCHDAY_SOURCE, SlotReason.MATCHDAY_FAST,
                    sourceStart, end, SOURCE_FAST, allFixtures, Set.of(), 2, now, due, next);

            WorkSlot newestHistory = null;
            for (Instant at = sourceStart; !at.isAfter(end); at = at.plus(HISTORY)) {
                Set<Integer> affectedFixtures = new LinkedHashSet<>();
                Set<Integer> affectedPlayers = new LinkedHashSet<>();
                for (Fixture fixture : fixtures) {
                    if (!at.isBefore(fixture.kickoff().minus(BEFORE_FIRST))
                            && !at.isAfter(fixture.kickoff().plus(AFTER_LAST))) {
                        affectedFixtures.add(fixture.id());
                        affectedPlayers.addAll(fixture.affectedPlayerIds());
                    }
                }
                if (!affectedFixtures.isEmpty()) {
                    WorkSlot candidate = slot(input.season(), WorkKind.AFFECTED_HISTORY, at,
                            affectedFixtures, affectedPlayers, SlotReason.MATCHDAY_FAST,
                            affectedPlayers.size(), "cadence");
                    if (at.isAfter(now)) {
                        next.consider(at);
                        break;
                    }
                    newestHistory = candidate;
                }
            }
            if (newestHistory != null) addCandidate(newestHistory, now, due, next);
        }
    }

    private static void addNewestCadence(String season, WorkKind kind, SlotReason reason,
                                         Instant start, Instant end, Duration step,
                                         Set<Integer> fixtureIds, Set<Integer> playerIds, int requests,
                                         Instant now, Map<String, WorkSlot> due, Next next) {
        if (start.isAfter(end)) return;
        if (now.isBefore(start)) {
            next.consider(start);
            return;
        }
        Instant bound = now.isBefore(end) ? now : end;
        long elapsed = Duration.between(start, bound).toMillis();
        long steps = elapsed / step.toMillis();
        Instant newest = start.plus(step.multipliedBy(steps));
        addCandidate(slot(season, kind, newest, fixtureIds, playerIds, reason, requests, "cadence"),
                now, due, next);
        Instant future = newest.plus(step);
        if (!future.isAfter(end)) next.consider(future);
    }

    private static List<Alert> addFixtureWork(Input input, Instant now,
                                               Map<String, WorkSlot> due, Next next) {
        Map<Integer, FixtureProgress> progress = new HashMap<>();
        input.fixtureProgress().forEach(value -> progress.put(value.fixtureId(), value));
        List<Alert> alerts = new ArrayList<>();
        for (Fixture fixture : input.fixtures()) {
            FixtureProgress state = progress.getOrDefault(fixture.id(), FixtureProgress.empty(fixture.id()));
            if (!fixture.finished() && fixture.kickoff() != null) {
                Instant alertAt = fixture.kickoff().plus(UNFINISHED_OVERDUE);
                if (!now.isBefore(alertAt)) {
                    alerts.add(new Alert("fixture:" + fixture.id() + ":verification-overdue",
                            fixture.id(), alertAt, AlertStatus.VERIFICATION_OVERDUE));
                } else next.consider(alertAt);
            }
            addVerification(input.season(), fixture, state, now, due, next);
            addCorrections(input.season(), fixture, state, now, due, next);
        }
        alerts.sort(Comparator.comparing(Alert::dueAt).thenComparing(Alert::id));
        return List.copyOf(alerts);
    }

    private static void addVerification(String season, Fixture fixture, FixtureProgress state,
                                        Instant now, Map<String, WorkSlot> due, Next next) {
        if (state.verifiedAt() != null) return;
        Instant at = null;
        SlotReason reason = SlotReason.VERIFICATION_FOLLOW_UP;
        String identity = "follow-up";
        if (fixture.finished() && state.firstFinishedObservedAt() == null) {
            at = fixture.sourceObservedAt();
            reason = SlotReason.FINISH_OBSERVED;
            identity = "finish:" + at;
        } else if (state.firstFinishedObservedAt() != null) {
            Instant last = state.lastVerificationAt() == null
                    ? state.firstFinishedObservedAt() : state.lastVerificationAt();
            at = state.nextDueAt() != null ? state.nextDueAt()
                    : state.verificationAttempts() == 0 ? state.firstFinishedObservedAt()
                    : last.plus(followUpDelay(state.verificationAttempts()));
            if (!at.isBefore(state.firstFinishedObservedAt().plus(FOLLOW_UP_LIMIT))) at = null;
        } else if (state.pending() && fixture.kickoff() != null) {
            at = state.nextDueAt() != null ? state.nextDueAt()
                    : (state.lastVerificationAt() == null ? fixture.kickoff().plus(UNFINISHED_OVERDUE)
                    : state.lastVerificationAt().plus(Duration.ofHours(1)));
            reason = SlotReason.WAITING_FINISH;
            identity = "waiting-finish";
        }
        if (at != null) addCandidate(slot(season, WorkKind.FIXTURE_VERIFICATION, at,
                Set.of(fixture.id()), fixture.affectedPlayerIds(), reason,
                fixture.affectedPlayerIds().size() + 5, identity), now, due, next);

        if (state.pending() && (state.firstFinishedObservedAt() == null
                || !now.isBefore(state.firstFinishedObservedAt().plus(FOLLOW_UP_LIMIT)))) {
            LocalDate today = now.atZone(UK).toLocalDate();
            Instant recovery = uk(today, LocalTime.of(3, 0));
            if (recovery.isAfter(now)) recovery = uk(today.minusDays(1), LocalTime.of(3, 0));
            addCandidate(slot(season, WorkKind.FIXTURE_VERIFICATION, recovery,
                    Set.of(fixture.id()), fixture.affectedPlayerIds(), SlotReason.DAILY_RECOVERY,
                    fixture.affectedPlayerIds().size() + 5, "recovery:" + recovery.atZone(UK).toLocalDate()),
                    now, due, next);
            Instant nextRecovery = uk(today, LocalTime.of(3, 0));
            if (!nextRecovery.isAfter(now)) nextRecovery = uk(today.plusDays(1), LocalTime.of(3, 0));
            next.consider(nextRecovery);
        }
    }

    private static Duration followUpDelay(int attempts) {
        return switch (Math.max(1, attempts)) {
            case 1 -> Duration.ofMinutes(10);
            case 2 -> Duration.ofMinutes(20);
            case 3 -> Duration.ofMinutes(40);
            default -> Duration.ofHours(1);
        };
    }

    private static void addCorrections(String season, Fixture fixture, FixtureProgress state,
                                       Instant now, Map<String, WorkSlot> due, Next next) {
        if (state.verifiedAt() == null) return;
        WorkSlot newest = null;
        for (int index = 0; index < CORRECTIONS.size(); index++) {
            String key = switch (index) { case 0 -> "2h"; case 1 -> "24h"; default -> "48h"; };
            if (state.completedCorrections().contains(key)) continue;
            Instant at = state.verifiedAt().plus(CORRECTIONS.get(index));
            WorkSlot candidate = slot(season, WorkKind.FIXTURE_CORRECTION, at,
                    Set.of(fixture.id()), fixture.affectedPlayerIds(), SlotReason.CORRECTION,
                    fixture.affectedPlayerIds().size() + 5, key);
            if (at.isAfter(now)) next.consider(at);
            else if (newest == null || at.isAfter(newest.dueAt())) newest = candidate;
        }
        if (newest != null) addCandidate(newest, now, due, next);
        if (state.dataCheckedTransitionAt() != null
                && !state.completedCorrections().contains("data-checked")) {
            addCandidate(slot(season, WorkKind.FIXTURE_CORRECTION, state.dataCheckedTransitionAt(),
                    Set.of(fixture.id()), fixture.affectedPlayerIds(), SlotReason.DATA_CHECKED,
                    fixture.affectedPlayerIds().size() + 5, "data-checked:" + state.dataCheckedTransitionAt()),
                    now, due, next);
        }
    }

    private static void retainNewest(Map<String, WorkSlot> due, WorkKind kind) {
        Instant newest = due.values().stream().filter(slot -> slot.kind() == kind)
                .map(WorkSlot::dueAt).max(Instant::compareTo).orElse(null);
        if (newest != null) due.entrySet().removeIf(entry -> entry.getValue().kind() == kind
                && entry.getValue().dueAt().isBefore(newest));
    }

    private static void addCandidate(WorkSlot slot, Instant now, Map<String, WorkSlot> due, Next next) {
        if (slot.dueAt().isAfter(now)) next.consider(slot.dueAt());
        else due.putIfAbsent(slot.id(), slot);
    }

    private static WorkSlot slot(String season, WorkKind kind, Instant at, Collection<Integer> fixtures,
                                 Collection<Integer> players, SlotReason reason, int requests, String suffix) {
        String fixtureIdentity = fixtures.isEmpty() ? "" : ":fixtures=" + fixtures.stream()
                .sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String id = season + ":" + kind.name().toLowerCase() + ":" + suffix + fixtureIdentity + ":" + at;
        return new WorkSlot(id, kind, at, Set.copyOf(fixtures), Set.copyOf(players), reason, requests);
    }

    private static Set<Integer> ids(Collection<Fixture> fixtures) {
        Set<Integer> ids = new HashSet<>();
        fixtures.forEach(fixture -> ids.add(fixture.id()));
        return Set.copyOf(ids);
    }

    private static Instant uk(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), UK).toInstant();
    }

    private static final class Next {
        private Instant value;
        void consider(Instant candidate) {
            if (value == null || candidate.isBefore(value)) value = candidate;
        }
    }

    public enum WorkKind {
        DAILY_CATALOGUE, DAILY_SCHEDULE, FULL_PLAYER_RECONCILE,
        MATCHDAY_SOURCE, AFFECTED_HISTORY, FIXTURE_VERIFICATION, FIXTURE_CORRECTION
    }

    public enum SlotReason {
        ROUTINE, MATCHDAY, MATCHDAY_FAST, FINISH_OBSERVED, VERIFICATION_FOLLOW_UP,
        WAITING_FINISH, DAILY_RECOVERY, CORRECTION, DATA_CHECKED, PERSISTED_CATCH_UP
    }

    public enum AlertStatus { VERIFICATION_OVERDUE }

    public record Fixture(int id, Integer gameweek, Instant kickoff, boolean started, boolean finished,
                          boolean dataChecked, Instant sourceObservedAt, Set<Integer> affectedPlayerIds) {
        public Fixture {
            if (id <= 0) throw new IllegalArgumentException("fixture id must be positive");
            Objects.requireNonNull(sourceObservedAt);
            affectedPlayerIds = Set.copyOf(affectedPlayerIds);
        }
    }

    public record FixtureProgress(int fixtureId, boolean pending, Instant firstFinishedObservedAt,
                                  Instant lastVerificationAt, int verificationAttempts,
                                  Instant nextDueAt, Instant verifiedAt,
                                  Instant dataCheckedTransitionAt, Set<String> completedCorrections) {
        public FixtureProgress {
            if (fixtureId <= 0 || verificationAttempts < 0) throw new IllegalArgumentException("invalid fixture progress");
            completedCorrections = Set.copyOf(completedCorrections);
        }
        public static FixtureProgress empty(int fixtureId) {
            return new FixtureProgress(fixtureId, false, null, null, 0, null, null, null, Set.of());
        }
    }

    public record Input(String season, int activePlayerCount, List<Fixture> fixtures,
                        List<FixtureProgress> fixtureProgress, List<WorkSlot> persistedPending) {
        public Input {
            if (season == null || season.isBlank() || activePlayerCount < 0) throw new IllegalArgumentException("invalid planner input");
            fixtures = List.copyOf(fixtures);
            fixtureProgress = List.copyOf(fixtureProgress);
            persistedPending = List.copyOf(persistedPending);
        }
    }

    public record WorkSlot(String id, WorkKind kind, Instant dueAt, Set<Integer> fixtureIds,
                           Set<Integer> playerIds, SlotReason reason, int estimatedRequests) {
        public WorkSlot {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(dueAt);
            Objects.requireNonNull(reason);
            fixtureIds = Set.copyOf(fixtureIds); playerIds = Set.copyOf(playerIds);
            if (estimatedRequests < 0) throw new IllegalArgumentException("negative request estimate");
        }
    }

    public record Alert(String id, int fixtureId, Instant dueAt, AlertStatus status) { }

    public record Plan(List<WorkSlot> dueSlots, Optional<Instant> nextDueAt, List<Alert> alerts) {
        public Plan {
            dueSlots = List.copyOf(dueSlots); Objects.requireNonNull(nextDueAt); alerts = List.copyOf(alerts);
        }
        public int estimatedRequests() {
            return dueSlots.stream().mapToInt(WorkSlot::estimatedRequests).sum();
        }
    }
}
