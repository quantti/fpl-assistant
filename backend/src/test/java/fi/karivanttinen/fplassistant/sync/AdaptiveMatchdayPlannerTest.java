package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.AlertStatus;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Fixture;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.FixtureProgress;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Input;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Plan;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.RecoveryMetadata;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.SlotReason;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkKind;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkSlot;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.DueReason;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus;

class AdaptiveMatchdayPlannerTest {

    @Test
    void dailySlotsUseUkTimeAcrossSpringAndFallDst() {
        Plan spring = plan("2026-03-29T02:30:00Z", List.of(), List.of());
        Plan fall = plan("2026-10-25T03:30:00Z", List.of(), List.of());

        assertDue(spring, WorkKind.DAILY_CATALOGUE, "2026-03-29T01:00:00Z");
        assertDue(spring, WorkKind.DAILY_SCHEDULE, "2026-03-29T01:10:00Z");
        assertDue(spring, WorkKind.FULL_PLAYER_RECONCILE, "2026-03-29T02:00:00Z");
        assertDue(fall, WorkKind.DAILY_CATALOGUE, "2026-10-25T02:00:00Z");
        assertDue(fall, WorkKind.DAILY_SCHEDULE, "2026-10-25T02:10:00Z");
        assertDue(fall, WorkKind.FULL_PLAYER_RECONCILE, "2026-10-25T03:00:00Z");
    }

    @Test
    void saturdaySundayAndMidweekComeOnlyFromActualKickoffs() {
        Fixture saturday = fixture(1, 1, "2026-09-19T14:00:00Z", false, ids(1, 2));
        Fixture sunday = fixture(2, 1, "2026-09-20T15:30:00Z", false, ids(3, 4));
        Fixture wednesday = fixture(3, 2, "2026-09-23T19:00:00Z", false, ids(5, 6));

        assertThat(plan("2026-09-19T12:07:00Z", List.of(saturday, sunday, wednesday), List.of()).dueSlots())
                .anySatisfy(slot -> {
                    assertThat(slot.kind()).isEqualTo(WorkKind.MATCHDAY_SOURCE);
                    assertThat(slot.dueAt()).isEqualTo(Instant.parse("2026-09-19T12:00:00Z"));
                });
        assertThat(plan("2026-09-20T14:37:00Z", List.of(saturday, sunday, wednesday), List.of()).dueSlots())
                .anySatisfy(slot -> assertThat(slot.fixtureIds()).containsExactly(2));
        assertThat(plan("2026-09-23T18:13:00Z", List.of(saturday, sunday, wednesday), List.of()).dueSlots())
                .anySatisfy(slot -> assertThat(slot.fixtureIds()).containsExactly(3));
    }

    @Test
    void sourceSwitchesFromHalfHourlyToTenMinutesAndIdentityDoesNotMoveEachTick() {
        Fixture match = fixture(10, 4, "2026-09-19T14:00:00Z", false, ids(1, 2));
        Plan first = plan("2026-09-19T13:04:01Z", List.of(match), List.of());
        Plan second = plan("2026-09-19T13:09:59Z", List.of(match), List.of());
        WorkSlot a = latest(first, WorkKind.MATCHDAY_SOURCE);
        WorkSlot b = latest(second, WorkKind.MATCHDAY_SOURCE);

        assertThat(a.dueAt()).isEqualTo(Instant.parse("2026-09-19T13:00:00Z"));
        assertThat(b).isEqualTo(a);
        assertThat(plan("2026-09-19T12:47:00Z", List.of(match), List.of()).dueSlots())
                .filteredOn(slot -> slot.kind() == WorkKind.MATCHDAY_SOURCE)
                .extracting(WorkSlot::dueAt).contains(Instant.parse("2026-09-19T12:30:00Z"));
    }

    @Test
    void midnightMatchdayWindowEndsAtLastKickoffPlusFourHoursTen() {
        Fixture late = fixture(11, 5, "2026-09-19T20:00:00Z", false, ids(1, 2)); // 21:00 UK
        Plan afterMidnight = plan("2026-09-20T00:08:00Z", List.of(late), List.of());

        assertThat(latest(afterMidnight, WorkKind.MATCHDAY_SOURCE).dueAt())
                .isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(afterMidnight.nextDueAt()).contains(Instant.parse("2026-09-20T00:10:00Z"));
        assertThat(plan("2026-09-20T00:11:00Z", List.of(late), List.of()).dueSlots())
                .filteredOn(slot -> slot.kind() == WorkKind.MATCHDAY_SOURCE)
                .extracting(WorkSlot::dueAt).contains(Instant.parse("2026-09-20T00:10:00Z"));
    }

    @Test
    void simultaneousMatchesAndDoubleGameweekPlayersCoalesceInOneHistorySlot() {
        Fixture first = fixture(20, 7, "2026-09-19T14:00:00Z", false, ids(1, 2, 3));
        Fixture simultaneous = fixture(21, 7, "2026-09-19T14:00:00Z", false, ids(3, 4));
        Fixture doubleLeg = fixture(22, 7, "2026-09-19T16:00:00Z", false, ids(1, 5));

        WorkSlot history = latest(plan("2026-09-19T15:03:00Z",
                List.of(first, simultaneous, doubleLeg), List.of()), WorkKind.AFFECTED_HISTORY);
        assertThat(history.fixtureIds()).containsExactlyInAnyOrder(20, 21, 22);
        assertThat(history.playerIds()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(history.estimatedRequests()).isEqualTo(5);
    }

    @Test
    void postponementAndScheduleChangeRecomputeWithoutReplayingOldCadence() {
        Fixture old = fixture(30, 8, "2026-09-19T14:00:00Z", false, ids(1));
        Fixture moved = fixture(30, 8, "2026-09-19T17:00:00Z", false, ids(1));
        Fixture postponed = new Fixture(31, null, null, false, false, false,
                Instant.parse("2026-09-19T10:00:00Z"), Set.of());

        WorkSlot oldSlot = latest(plan("2026-09-19T13:01:00Z", List.of(old), List.of()), WorkKind.MATCHDAY_SOURCE);
        Plan changed = plan("2026-09-19T13:01:00Z", List.of(moved, postponed), List.of());
        WorkSlot changedSlot = latest(changed, WorkKind.MATCHDAY_SOURCE);
        assertThat(changedSlot.dueAt()).isEqualTo(Instant.parse("2026-09-19T13:00:00Z"));
        assertThat(changedSlot.reason()).isEqualTo(SlotReason.MATCHDAY);
        assertThat(changedSlot.id()).isEqualTo(oldSlot.id());
        assertThat(changedSlot.fixtureIds()).containsExactly(30);
        assertThat(changed.dueSlots()).noneSatisfy(slot -> assertThat(slot.fixtureIds()).contains(31));
    }

    @Test
    void missedTicksReturnOnlyNewestOccurrenceAndExplicitPersistedCatchup() {
        Fixture match = fixture(40, 9, "2026-09-19T14:00:00Z", false, ids(1, 2));
        WorkSlot catchup = new WorkSlot("persisted-old-history", WorkKind.AFFECTED_HISTORY,
                Instant.parse("2026-09-19T13:00:00Z"), Set.of(40), Set.of(1, 2),
                SlotReason.PERSISTED_CATCH_UP, 2);
        Input input = new Input(SEASON, 650, List.of(match), List.of(), List.of(catchup));
        Plan result = planner("2026-09-19T17:07:00Z").plan(input);

        assertThat(result.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.MATCHDAY_SOURCE).hasSize(1);
        assertThat(result.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.AFFECTED_HISTORY).hasSize(2);
        assertThat(result.dueSlots()).extracting(WorkSlot::id).contains("persisted-old-history");
    }

    @Test
    void finishObservationIsImmediateThenRetriesTenTwentyFortyAndHourlyButStopsAtTwelveHours() {
        Fixture finished = fixture(50, 10, "2026-09-19T14:00:00Z", true, ids(1, 2));
        WorkSlot immediate = latest(plan("2026-09-19T16:01:00Z", List.of(finished), List.of()),
                WorkKind.FIXTURE_VERIFICATION);
        assertThat(immediate.dueAt()).isEqualTo(finished.sourceObservedAt());
        assertThat(immediate.reason()).isEqualTo(SlotReason.FINISH_OBSERVED);

        Instant observed = Instant.parse("2026-09-19T16:00:00Z");
        FixtureProgress persistedFinish = progress(50, observed, null, 0,
                null, null, null, Set.of());
        assertThat(latest(plan("2026-09-19T16:01:00Z", List.of(finished),
                List.of(persistedFinish)), WorkKind.FIXTURE_VERIFICATION).dueAt()).isEqualTo(observed);
        assertFollowUp(finished, observed, 1, "2026-09-19T16:10:00Z");
        assertFollowUp(finished, observed, 2, "2026-09-19T16:20:00Z");
        assertFollowUp(finished, observed, 3, "2026-09-19T16:40:00Z");
        assertFollowUp(finished, observed, 4, "2026-09-19T17:00:00Z");

        FixtureProgress capped = progress(50, observed, Instant.parse("2026-09-20T04:00:00Z"), 9,
                null, null, null, Set.of());
        Plan afterCap = plan("2026-09-20T05:00:00Z", List.of(finished), List.of(capped));
        assertThat(afterCap.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION)
                .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo(SlotReason.DAILY_RECOVERY));
    }

    @Test
    void unfinishedOverdueIsAlertedSeparatelyAndContinuesHourlyWithoutBeingMarkedFinished() {
        Fixture playing = fixture(60, 11, "2026-09-19T14:00:00Z", false, ids(1, 2));
        FixtureProgress pending = new FixtureProgress(60, true, null,
                Instant.parse("2026-09-19T17:00:00Z"), 3, null, null, null, Set.of());
        Plan result = plan("2026-09-19T18:05:00Z", List.of(playing), List.of(pending));

        assertThat(result.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.status()).isEqualTo(AlertStatus.VERIFICATION_OVERDUE);
            assertThat(alert.dueAt()).isEqualTo(Instant.parse("2026-09-19T17:00:00Z"));
        });
        assertThat(result.dueSlots()).filteredOn(slot -> slot.reason() == SlotReason.WAITING_FINISH)
                .singleElement().satisfies(slot -> assertThat(slot.dueAt())
                        .isEqualTo(Instant.parse("2026-09-19T18:00:00Z")));
        assertThat(result.dueSlots()).noneMatch(slot -> slot.reason() == SlotReason.DAILY_RECOVERY);
    }

    @Test
    void verifiedCorrectionsUseNewestMissedSlotAndIndependentDataCheckedTransition() {
        Fixture fixture = fixture(70, 12, "2026-09-19T14:00:00Z", true, ids(1));
        Instant verified = Instant.parse("2026-09-19T16:00:00Z");
        FixtureProgress state = progress(70, verified, verified, 2, null, verified,
                Instant.parse("2026-09-20T18:00:00Z"), Set.of());
        Plan result = plan("2026-09-20T19:00:00Z", List.of(fixture), List.of(state));

        assertThat(result.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_CORRECTION)
                .hasSize(2)
                .extracting(WorkSlot::reason).containsExactlyInAnyOrder(SlotReason.CORRECTION,
                        SlotReason.DATA_CHECKED);
        assertThat(result.dueSlots()).filteredOn(slot -> slot.reason() == SlotReason.CORRECTION)
                .extracting(WorkSlot::dueAt).containsExactly(Instant.parse("2026-09-20T16:00:00Z"));
    }

    @Test
    void overdueRecoveryUsesSharedPolicyWithoutMovingLegacyWaitOrEnqueueingCalendarOnlyWaits() {
        Instant now = Instant.parse("2026-09-20T08:00:00Z");
        Instant finished = now.minus(Duration.ofHours(13));
        Fixture fixture = fixture(72, 12, "2026-09-19T14:00:00Z", true, ids(1));
        Instant legacyDue = Instant.parse("2026-09-21T02:00:00Z");
        FixtureProgress legacy = progress(72, finished, null, 3, legacyDue, null, null, Set.of());

        Plan beforeLegacyDue = planner(now.toString()).plan(new Input(SEASON, 650, List.of(fixture),
                List.of(legacy), List.of()));
        assertThat(beforeLegacyDue.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION)
                .isEmpty();
        assertThat(beforeLegacyDue.nextDueAt()).isPresent();

        WorkSlot admittedAtLegacyDue = latest(planner(legacyDue.toString()).plan(new Input(SEASON, 650,
                List.of(fixture), List.of(legacy), List.of())), WorkKind.FIXTURE_VERIFICATION);
        WorkSlot admittedAfterMissedTick = latest(planner(legacyDue.plus(Duration.ofMinutes(30)).toString())
                .plan(new Input(SEASON, 650, List.of(fixture), List.of(legacy), List.of())),
                WorkKind.FIXTURE_VERIFICATION);
        assertThat(admittedAtLegacyDue.dueAt()).isEqualTo(legacyDue);
        assertThat(admittedAfterMissedTick.id()).isEqualTo(admittedAtLegacyDue.id());
        assertThat(admittedAfterMissedTick.dueAt()).isEqualTo(legacyDue);
        assertThat(admittedAtLegacyDue.reason()).isEqualTo(SlotReason.DAILY_RECOVERY);

        FixtureProgress calendarOnly = progressWithRecovery(72, finished, null, 3,
                now.plus(Duration.ofHours(20)), null, new RecoveryMetadata(
                        DueReason.POLICY, null, null, null, null, null, null, null));
        Plan immediatePolicy = planner(now.toString()).plan(new Input(SEASON, 650,
                List.of(fixture), List.of(calendarOnly), List.of()));
        assertThat(immediatePolicy.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION)
                .singleElement().satisfies(slot -> assertThat(slot.dueAt()).isBefore(now));

        Instant dependency = now.plus(Duration.ofMinutes(10));
        Instant transport = now.plus(Duration.ofMinutes(20));
        Instant cooldown = now.plus(Duration.ofMinutes(30));
        FixtureProgress gated = progressWithRecovery(72, finished, null, 3, now.plusSeconds(1), null,
                new RecoveryMetadata(DueReason.POLICY, transport, dependency, cooldown,
                        null, null, null, null));
        Plan waiting = planner(now.toString()).plan(new Input(SEASON, 650, List.of(fixture),
                List.of(gated), List.of()));
        assertThat(waiting.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION).isEmpty();
        assertThat(waiting.nextDueAt()).contains(cooldown);
    }

    @Test
    void overdueRecoveryWaitsForExactParentAndResumesTenMinuteDependencyInsideFixedEpisode() {
        Instant now = Instant.parse("2026-09-20T08:00:00Z");
        Instant finished = now.minus(Duration.ofHours(13));
        Fixture unknown = new Fixture(73, 12, Instant.parse("2026-09-19T14:00:00Z"), true, true,
                false, now, ids(1), SourceStatus.UNKNOWN);
        FixtureProgress noParent = progress(73, finished, null, 1, null, null, null, Set.of());
        Plan noRecovery = planner(now.toString()).plan(new Input(SEASON, 650, List.of(unknown),
                List.of(noParent), List.of()));
        assertThat(noRecovery.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION).isEmpty();

        LocalDate recoveryDay = LocalDate.of(2026, 9, 20);
        Instant episodeStart = now.minus(Duration.ofHours(1));
        Instant episodeUntil = episodeStart.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        Instant nextBatch = now.plus(Duration.ofMinutes(10));
        RecoveryMetadata active = new RecoveryMetadata(DueReason.DEPENDENCY, null, nextBatch, null,
                recoveryDay, episodeStart, episodeUntil, null);
        FixtureProgress waitingForBatch = progressWithRecovery(73, finished, null, 1,
                nextBatch, null, active);
        Plan beforeBatch = planner(now.toString()).plan(new Input(SEASON, 650, List.of(fixture(73, 12,
                "2026-09-19T14:00:00Z", true, ids(1))), List.of(waitingForBatch), List.of()));
        assertThat(beforeBatch.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION).isEmpty();
        assertThat(beforeBatch.nextDueAt()).contains(nextBatch);

        Plan matureBatch = planner(nextBatch.toString()).plan(new Input(SEASON, 650, List.of(fixture(73, 12,
                "2026-09-19T14:00:00Z", true, ids(1))), List.of(waitingForBatch), List.of()));
        assertThat(latest(matureBatch, WorkKind.FIXTURE_VERIFICATION).dueAt()).isEqualTo(nextBatch);
        assertThat(latest(matureBatch, WorkKind.FIXTURE_VERIFICATION).reason()).isEqualTo(SlotReason.DAILY_RECOVERY);
    }

    @Test
    void publishedRecoveryStaysConsumedAfterInvalidationUntilTheNextUkBoundary() {
        Instant now = Instant.parse("2026-09-20T08:00:00Z");
        Instant episodeStart = now.minus(Duration.ofHours(2));
        Instant published = episodeStart.plus(Duration.ofMinutes(30));
        Instant episodeUntil = episodeStart.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        RecoveryMetadata consumed = new RecoveryMetadata(null, null, null, null,
                LocalDate.of(2026, 9, 20), episodeStart, episodeUntil, published);
        Fixture fixture = fixture(74, 12, "2026-09-19T14:00:00Z", true, ids(1));
        FixtureProgress invalidated = progressWithRecovery(74, now.minus(Duration.ofHours(14)), null, 4,
                null, null, consumed);

        Plan held = planner(now.toString()).plan(new Input(SEASON, 650, List.of(fixture),
                List.of(invalidated), List.of()));
        assertThat(held.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION).isEmpty();
        assertThat(held.nextDueAt()).isPresent();

        Plan nextDay = planner("2026-09-21T02:00:00Z").plan(new Input(SEASON, 650, List.of(fixture),
                List.of(invalidated), List.of()));
        assertThat(nextDay.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.FIXTURE_VERIFICATION)
                .singleElement().satisfies(slot -> assertThat(slot.dueAt())
                        .isEqualTo(Instant.parse("2026-09-21T02:00:00Z")));
    }

    @Test
    void fixture38AtSeasonEndDoesNotInventFurtherMatchdays() {
        Fixture last = fixture(380, 38, "2027-05-23T15:00:00Z", false, ids(1, 2));
        Plan result = plan("2027-05-24T04:00:00Z", List.of(last), List.of());

        assertThat(result.dueSlots()).filteredOn(slot -> slot.kind() == WorkKind.MATCHDAY_SOURCE)
                .noneSatisfy(slot -> assertThat(slot.dueAt()).isAfter("2027-05-23T19:10:00Z"));
        assertThat(result.nextDueAt()).contains(Instant.parse("2027-05-25T01:00:00Z"));
    }

    @Test
    void tenMatchRollingCadenceStaysUnderCoordinatorDailyBudget() {
        List<Fixture> fixtures = new ArrayList<>();
        int player = 1;
        for (int match = 0; match < 10; match++) {
            Set<Integer> players = new HashSet<>();
            for (int count = 0; count < 36; count++) players.add(player++);
            int hour = 11 + match;
            fixtures.add(fixture(100 + match, 1,
                    "2026-09-19T%02d:00:00Z".formatted(hour), false, players));
        }
        Map<String, WorkSlot> unique = new HashMap<>();
        Instant start = Instant.parse("2026-09-19T03:00:00Z");
        for (int minute = 0; minute < 24 * 60; minute++) {
            Plan plan = planner(start.plusSeconds(minute * 60L).toString()).plan(
                    new Input(SEASON, 650, fixtures, List.of(), List.of()));
            plan.dueSlots().stream().filter(slot -> !slot.dueAt().isBefore(start))
                    .forEach(slot -> unique.put(slot.id(), slot));
        }
        int requests = unique.values().stream().mapToInt(WorkSlot::estimatedRequests).sum();
        assertThat(requests).isLessThanOrEqualTo(5_000);
        assertThat(unique.values()).filteredOn(slot -> slot.kind() == WorkKind.MATCHDAY_SOURCE).hasSize(92);
    }

    private static void assertFollowUp(Fixture fixture, Instant observed, int attempts, String expected) {
        FixtureProgress state = progress(fixture.id(), observed, observed, attempts, null, null, null, Set.of());
        WorkSlot slot = latest(plan(Instant.parse(expected).plusSeconds(1).toString(), List.of(fixture), List.of(state)),
                WorkKind.FIXTURE_VERIFICATION);
        assertThat(slot.dueAt()).isEqualTo(Instant.parse(expected));
    }

    private static Fixture fixture(int id, int gameweek, String kickoff, boolean finished, Set<Integer> players) {
        Instant observed = Instant.parse(kickoff).plusSeconds(finished ? 2 * 3600L : 0);
        return new Fixture(id, gameweek, Instant.parse(kickoff), finished, finished, false, observed, players);
    }

    private static FixtureProgress progress(int id, Instant first, Instant last, int attempts,
                                            Instant next, Instant verified, Instant checked, Set<String> completed) {
        return new FixtureProgress(id, true, first, last, attempts, next, verified, checked, completed);
    }

    private static FixtureProgress progressWithRecovery(int id, Instant first, Instant last, int attempts,
                                                        Instant next, Instant verified, RecoveryMetadata recovery) {
        return new FixtureProgress(id, true, first, last, attempts, next, verified, null, Set.of(), recovery);
    }

    private static Set<Integer> ids(int... values) {
        Set<Integer> result = new HashSet<>();
        for (int value : values) result.add(value);
        return result;
    }

    private static final String SEASON = "2026-27";

    private static Plan plan(String now, List<Fixture> fixtures, List<FixtureProgress> progress) {
        return planner(now).plan(new Input(SEASON, 650, fixtures, progress, List.of()));
    }

    private static AdaptiveMatchdayPlanner planner(String now) {
        return new AdaptiveMatchdayPlanner(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static WorkSlot latest(Plan plan, WorkKind kind) {
        return plan.dueSlots().stream().filter(slot -> slot.kind() == kind)
                .max(java.util.Comparator.comparing(WorkSlot::dueAt)).orElseThrow();
    }

    private static void assertDue(Plan plan, WorkKind kind, String due) {
        assertThat(plan.dueSlots()).filteredOn(slot -> slot.kind() == kind)
                .extracting(WorkSlot::dueAt).contains(Instant.parse(due));
    }
}
