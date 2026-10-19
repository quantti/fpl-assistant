package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.Action;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.DueReason;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.Input;
import fi.karivanttinen.fplassistant.sync.SharedFixtureRecoveryPolicy.SourceStatus;

class SharedFixtureRecoveryPolicyTest {

    private static final Instant FINISHED = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    void legacyGw5NextMorningDateIsHonoredOnceAndDueInstantStartsEpisode() {
        Instant gw5Due = Instant.parse("2026-09-20T02:00:00Z"); // 03:00 UK
        var beforeDue = with(overdue("2026-09-19T18:00:00Z"), input -> new Input(input.now(),
                input.sourceStatus(), input.currentlyVerified(), input.firstFinishedObservedAt(), gw5Due,
                null, null, null, null, null, null, null, null));

        var waiting = SharedFixtureRecoveryPolicy.decide(beforeDue);
        var due = SharedFixtureRecoveryPolicy.decide(withNow(beforeDue, gw5Due));

        assertThat(waiting.action()).isEqualTo(Action.WAIT);
        assertThat(waiting.notBefore()).isEqualTo(gw5Due);
        assertThat(due.action()).isEqualTo(Action.RESERVE_EPISODE);
        assertThat(due.recoveryDay()).isEqualTo(LocalDate.parse("2026-09-20"));
        assertThat(due.episodeStartedAt()).isEqualTo(gw5Due);
        assertThat(due.episodeUntil()).isEqualTo(gw5Due.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT));
    }

    @Test
    void newTypedPolicyOnlyRowCanStartImmediatelyDespiteItsOldPolicyDate() {
        Instant policyDate = Instant.parse("2026-09-20T02:00:00Z");
        Input input = new Input(Instant.parse("2026-09-19T18:00:00Z"), SourceStatus.FINISHED,
                false, FINISHED, policyDate, DueReason.POLICY, null, null, null, null, null, null, null);

        var decision = SharedFixtureRecoveryPolicy.decide(input);

        assertThat(decision.action()).isEqualTo(Action.RESERVE_EPISODE);
        assertThat(decision.episodeStartedAt()).isEqualTo(input.now());
        assertThat(decision.recoveryDay()).isEqualTo(LocalDate.parse("2026-09-19"));
    }

    @Test
    void dependencyAndTransportDatesRemainGatesAndTheLatestTransportDeadlineWins() {
        Instant now = Instant.parse("2026-09-19T18:00:00Z");
        Instant dependencyDue = now.plusSeconds(600);
        var dependency = new Input(now, SourceStatus.FINISHED, false, FINISHED, dependencyDue,
                DueReason.DEPENDENCY, null, null, null, null, null, null, dependencyDue);
        Instant retry = now.plusSeconds(900);
        Instant cooldown = now.plusSeconds(1800);
        var transport = new Input(now, SourceStatus.FINISHED, false, FINISHED, dependencyDue,
                DueReason.POLICY, retry, cooldown, null, null, null, null, null);

        assertThat(SharedFixtureRecoveryPolicy.decide(dependency).notBefore()).isEqualTo(dependencyDue);
        assertThat(SharedFixtureRecoveryPolicy.decide(transport).action()).isEqualTo(Action.WAIT);
        assertThat(SharedFixtureRecoveryPolicy.decide(transport).notBefore()).isEqualTo(cooldown);
        assertThat(SharedFixtureRecoveryPolicy.decide(withNow(transport, cooldown)).action())
                .isEqualTo(Action.RESERVE_EPISODE);
    }

    @Test
    void policyDeadlineCannotHideAnIndependentDependencyDeadline() {
        Instant now = Instant.parse("2026-09-19T18:00:00Z");
        Instant dependencyDue = now.plusSeconds(600);
        Instant policyDue = Instant.parse("2026-09-20T02:00:00Z");
        Input input = new Input(now, SourceStatus.FINISHED, false, FINISHED, policyDue,
                DueReason.POLICY, null, null, null, null, null, null, dependencyDue);

        var waiting = SharedFixtureRecoveryPolicy.decide(input);
        var due = SharedFixtureRecoveryPolicy.decide(withNow(input, dependencyDue));

        assertThat(waiting.action()).isEqualTo(Action.WAIT);
        assertThat(waiting.notBefore()).isEqualTo(dependencyDue);
        assertThat(due.action()).isEqualTo(Action.RESERVE_EPISODE);
    }

    @Test
    void activeEpisodeResumesWithoutExtensionAcrossMidnightAndTheThreeOclockBoundary() {
        Instant start = Instant.parse("2026-10-04T22:00:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate day = LocalDate.parse("2026-10-04");

        var midnight = SharedFixtureRecoveryPolicy.decide(episode("2026-10-04T23:10:00Z", day, start, until));
        var threeAm = SharedFixtureRecoveryPolicy.decide(episode("2026-10-05T02:00:00Z", day, start, until));

        assertThat(midnight.action()).isEqualTo(Action.CONTINUE_EPISODE);
        assertThat(threeAm.action()).isEqualTo(Action.CONTINUE_EPISODE);
        assertThat(threeAm.recoveryDay()).isEqualTo(day);
        assertThat(threeAm.episodeStartedAt()).isEqualTo(start);
        assertThat(threeAm.episodeUntil()).isEqualTo(until);
    }

    @Test
    void expiredConsumedEpisodeWaitsForNextRecoveryDayAndInvalidationCannotRegrantToday() {
        Instant start = Instant.parse("2026-09-20T02:30:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate consumed = LocalDate.parse("2026-09-20");
        Input invalidated = episode("2026-09-20T14:30:00Z", consumed, start, until);

        var decision = SharedFixtureRecoveryPolicy.decide(invalidated);
        var published = SharedFixtureRecoveryPolicy.decide(withVerified(invalidated));

        assertThat(decision.action()).isEqualTo(Action.WAIT);
        assertThat(decision.notBefore()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
        assertThat(decision.recoveryDay()).isEqualTo(consumed);
        assertThat(decision.episodeStartedAt()).isEqualTo(start);
        assertThat(decision.episodeUntil()).isEqualTo(until);
        assertThat(published.action()).isEqualTo(Action.NORMAL);
    }

    @Test
    void consumedDayWaitComposesTheNextGrantBoundaryWithLegacyDependencyAndTransportDates() {
        Instant now = Instant.parse("2026-09-20T18:00:00Z");
        Instant boundary = Instant.parse("2026-09-21T02:00:00Z");
        Instant beforeBoundary = now.plusSeconds(3600);
        Instant start = Instant.parse("2026-09-20T02:30:00Z");
        Input consumed = episode(now, LocalDate.parse("2026-09-20"), start,
                start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT));

        Input transport = withGates(consumed, beforeBoundary, DueReason.TRANSPORT,
                now.plusSeconds(1800), now.plusSeconds(2700));
        Input dependency = withGates(consumed, beforeBoundary, DueReason.DEPENDENCY, null, null);
        Input legacy = withGates(consumed, beforeBoundary, null, null, null);
        Input retryAfterBoundary = withGates(consumed, boundary.plusSeconds(1800), DueReason.TRANSPORT,
                boundary.plusSeconds(1800), null);

        assertThat(SharedFixtureRecoveryPolicy.decide(transport).notBefore()).isEqualTo(boundary);
        assertThat(SharedFixtureRecoveryPolicy.decide(dependency).notBefore()).isEqualTo(boundary);
        assertThat(SharedFixtureRecoveryPolicy.decide(legacy).notBefore()).isEqualTo(boundary);
        assertThat(SharedFixtureRecoveryPolicy.decide(retryAfterBoundary).notBefore())
                .isEqualTo(boundary.plusSeconds(1800));
    }

    @Test
    void activeEpisodeKeepsItsExpiryAndMovesAnUnusableRetryToTheNextGrant() {
        Instant start = Instant.parse("2026-09-20T10:00:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate day = LocalDate.parse("2026-09-20");
        Input active = episode("2026-09-20T11:00:00Z", day, start, until);
        Instant retryInside = Instant.parse("2026-09-20T12:00:00Z");
        Instant retryAfterExpiry = Instant.parse("2026-09-20T23:00:00Z");

        var inside = SharedFixtureRecoveryPolicy.decide(withGates(active, retryInside, DueReason.POLICY,
                null, null));
        var afterExpiry = SharedFixtureRecoveryPolicy.decide(withGates(active, retryAfterExpiry,
                DueReason.POLICY, null, null));

        assertThat(inside.action()).isEqualTo(Action.WAIT);
        assertThat(inside.notBefore()).isEqualTo(retryInside);
        assertThat(inside.recoveryDay()).isEqualTo(day);
        assertThat(inside.episodeStartedAt()).isEqualTo(start);
        assertThat(inside.episodeUntil()).isEqualTo(until);
        assertThat(afterExpiry.action()).isEqualTo(Action.WAIT);
        assertThat(afterExpiry.notBefore()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
        assertThat(afterExpiry.recoveryDay()).isEqualTo(day);
        assertThat(afterExpiry.episodeStartedAt()).isEqualTo(start);
        assertThat(afterExpiry.episodeUntil()).isEqualTo(until);
    }

    @Test
    void futureEpisodeWaitsForItsStartAndForTheNextGrantWhenRetryMissesItsWindow() {
        Instant start = Instant.parse("2026-09-20T10:00:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate day = LocalDate.parse("2026-09-20");
        Input future = episode("2026-09-20T09:00:00Z", day, start, until);

        var beforeStart = SharedFixtureRecoveryPolicy.decide(withGates(future,
                Instant.parse("2026-09-20T09:30:00Z"), DueReason.TRANSPORT, null, null));
        var afterExpiry = SharedFixtureRecoveryPolicy.decide(withGates(future,
                Instant.parse("2026-09-20T23:00:00Z"), DueReason.TRANSPORT, null, null));

        assertThat(beforeStart.action()).isEqualTo(Action.WAIT);
        assertThat(beforeStart.notBefore()).isEqualTo(start);
        assertThat(beforeStart.recoveryDay()).isEqualTo(day);
        assertThat(beforeStart.episodeStartedAt()).isEqualTo(start);
        assertThat(beforeStart.episodeUntil()).isEqualTo(until);
        assertThat(afterExpiry.action()).isEqualTo(Action.WAIT);
        assertThat(afterExpiry.notBefore()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
        assertThat(afterExpiry.recoveryDay()).isEqualTo(day);
        assertThat(afterExpiry.episodeStartedAt()).isEqualTo(start);
        assertThat(afterExpiry.episodeUntil()).isEqualTo(until);
    }

    @Test
    void activeEpisodeStillHonorsTheNormalVerificationFollowUpDelay() {
        Instant start = Instant.parse("2026-09-20T02:30:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        Instant nextFollowUp = Instant.parse("2026-09-20T02:45:00Z");
        Input input = new Input(Instant.parse("2026-09-20T02:35:00Z"), SourceStatus.FINISHED,
                false, FINISHED, nextFollowUp, DueReason.DEPENDENCY, null, null,
                LocalDate.parse("2026-09-20"), start, until, null, nextFollowUp);

        var waiting = SharedFixtureRecoveryPolicy.decide(input);
        var due = SharedFixtureRecoveryPolicy.decide(withNow(input, nextFollowUp));

        assertThat(waiting.action()).isEqualTo(Action.WAIT);
        assertThat(waiting.notBefore()).isEqualTo(nextFollowUp);
        assertThat(due.action()).isEqualTo(Action.CONTINUE_EPISODE);
        assertThat(due.episodeUntil()).isEqualTo(until);
    }

    @Test
    void independentBatchFollowUpCannotBeBypassedBeforeAnEpisode() {
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        Instant nextFollowUp = now.plusSeconds(600);
        Input input = new Input(now, SourceStatus.FINISHED, false, FINISHED,
                nextFollowUp, DueReason.DEPENDENCY, null, null, null, null, null, null, nextFollowUp);

        var waiting = SharedFixtureRecoveryPolicy.decide(input);
        var due = SharedFixtureRecoveryPolicy.decide(withNow(input, nextFollowUp));

        assertThat(waiting.action()).isEqualTo(Action.WAIT);
        assertThat(waiting.notBefore()).isEqualTo(nextFollowUp);
        assertThat(due.action()).isEqualTo(Action.RESERVE_EPISODE);
    }

    @Test
    void activePublicationClosesEpisodeAndSameDayInvalidationCannotResumeIt() {
        Instant start = Instant.parse("2026-09-20T02:30:00Z");
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        Instant publishedAt = start.plusSeconds(60);
        LocalDate consumed = LocalDate.parse("2026-09-20");
        Input invalidated = new Input(Instant.parse("2026-09-20T04:00:00Z"), SourceStatus.FINISHED,
                false, FINISHED, null, null, null, null, consumed, start, until, publishedAt, null);

        var decision = SharedFixtureRecoveryPolicy.decide(invalidated);

        assertThat(decision.action()).isEqualTo(Action.WAIT);
        assertThat(decision.notBefore()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
        assertThat(decision.recoveryDay()).isEqualTo(consumed);
        assertThat(decision.episodeStartedAt()).isEqualTo(start);
        assertThat(decision.episodeUntil()).isEqualTo(until);
    }

    @Test
    void episodeCrossingThreeAmKeepsItsOriginalExpiryThenMayUseTheNewDayAfterExpiry() {
        Instant start = Instant.parse("2026-09-21T01:50:00Z"); // 02:50 UK, previous recovery day
        Instant until = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate priorDay = LocalDate.parse("2026-09-20");

        var beforeExpiry = SharedFixtureRecoveryPolicy.decide(episode("2026-09-21T02:00:00Z",
                priorDay, start, until));
        var atExpiry = SharedFixtureRecoveryPolicy.decide(episode(until, priorDay, start, until));

        assertThat(beforeExpiry.action()).isEqualTo(Action.CONTINUE_EPISODE);
        assertThat(beforeExpiry.episodeUntil()).isEqualTo(until);
        assertThat(atExpiry.action()).isEqualTo(Action.RESERVE_EPISODE);
        assertThat(atExpiry.recoveryDay()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(atExpiry.episodeStartedAt()).isEqualTo(until);
    }

    @Test
    void exactTwelveHourBoundaryIsOverdueButAnEarlierNormalAttemptStaysOnExistingPath() {
        Instant cutoff = FINISHED.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        Input early = overdue(cutoff.minusMillis(1));
        Input atLimit = overdue(cutoff);

        assertThat(SharedFixtureRecoveryPolicy.decide(early).action()).isEqualTo(Action.NORMAL);
        assertThat(SharedFixtureRecoveryPolicy.decide(atLimit).action()).isEqualTo(Action.RESERVE_EPISODE);
    }

    @Test
    void episodeDurationIsBoundedToTwelveElapsedHoursAcrossTheUkDstChange() {
        Instant start = Instant.parse("2026-10-24T22:30:00Z"); // 23:30 BST before the clock change
        Instant exactLimit = start.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT);
        LocalDate day = LocalDate.parse("2026-10-24");

        assertThatCode(() -> episode(start, day, start, exactLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> episode(start, day, start, exactLimit.plusNanos(1_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed twelve hours");
    }

    @Test
    void overdueUnknownAndUnfinishedSourcesWaitWithoutGrantingRecovery() {
        Input overdue = overdue("2026-09-19T18:00:00Z");

        assertThat(SharedFixtureRecoveryPolicy.decide(withSource(overdue, SourceStatus.UNFINISHED)).action())
                .isEqualTo(Action.WAIT_FOR_SOURCE);
        assertThat(SharedFixtureRecoveryPolicy.decide(withSource(overdue, SourceStatus.UNKNOWN)).action())
                .isEqualTo(Action.WAIT_FOR_PARENT);
        assertThat(SharedFixtureRecoveryPolicy.decide(withVerified(overdue)).action()).isEqualTo(Action.NORMAL);
        assertThat(SharedFixtureRecoveryPolicy.decide(withSource(
                withNow(overdue, FINISHED.plus(SharedFixtureRecoveryPolicy.FOLLOW_UP_LIMIT).minusMillis(1)),
                SourceStatus.UNKNOWN)).action()).isEqualTo(Action.NORMAL);
    }

    @Test
    void recoveryDayAndNextBoundaryUseUkCivilTimeAcrossDstAndMidnight() {
        assertThat(SharedFixtureRecoveryPolicy.recoveryDayAt(Instant.parse("2026-09-20T01:59:59Z")))
                .isEqualTo(LocalDate.parse("2026-09-19"));
        assertThat(SharedFixtureRecoveryPolicy.recoveryDayAt(Instant.parse("2026-09-20T02:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-09-20"));
        assertThat(SharedFixtureRecoveryPolicy.recoveryDayAt(Instant.parse("2026-10-04T23:10:00Z")))
                .isEqualTo(LocalDate.parse("2026-10-04"));

        assertThat(SharedFixtureRecoveryPolicy.nextRecoveryBoundary(Instant.parse("2026-03-29T00:59:59Z")))
                .isEqualTo(Instant.parse("2026-03-29T02:00:00Z"));
        assertThat(SharedFixtureRecoveryPolicy.recoveryDayAt(Instant.parse("2026-03-29T02:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-03-29"));
        assertThat(SharedFixtureRecoveryPolicy.nextRecoveryBoundary(Instant.parse("2026-10-25T02:59:59Z")))
                .isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        assertThat(SharedFixtureRecoveryPolicy.recoveryDayAt(Instant.parse("2026-10-25T03:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(SharedFixtureRecoveryPolicy.nextRecoveryBoundary(Instant.parse("2026-10-04T23:10:00Z")))
                .isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
    }

    @Test
    void rejectsPartialOrNonPositiveEpisodeMetadata() {
        assertThatThrownBy(() -> new Input(Instant.EPOCH, SourceStatus.FINISHED, false, null,
                null, null, null, null, LocalDate.of(2026, 9, 20), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> episode("2026-09-20T02:00:00Z", LocalDate.of(2026, 9, 20),
                Instant.parse("2026-09-20T03:00:00Z"), Instant.parse("2026-09-20T03:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Input overdue(String now) {
        return overdue(Instant.parse(now));
    }

    private static Input overdue(Instant now) {
        return new Input(now, SourceStatus.FINISHED, false, FINISHED,
                null, null, null, null, null, null, null, null, null);
    }

    private static Input episode(String now, LocalDate day, Instant start, Instant until) {
        return episode(Instant.parse(now), day, start, until);
    }

    private static Input episode(Instant now, LocalDate day, Instant start, Instant until) {
        return new Input(now, SourceStatus.FINISHED, false, FINISHED,
                null, null, null, null, day, start, until, null, null);
    }

    private static Input withNow(Input input, Instant now) {
        return with(input, value -> new Input(now, value.sourceStatus(), value.currentlyVerified(),
                value.firstFinishedObservedAt(), value.nextDueAt(), value.dueReason(), value.transportRetryAt(),
                value.cooldownUntil(), value.recoveryDay(), value.recoveryStartedAt(), value.recoveryUntil(),
                value.recoveryPublishedAt(), value.dependencyRetryAt()));
    }

    private static Input withGates(Input input, Instant nextDueAt, DueReason dueReason,
                                   Instant transportRetryAt, Instant cooldownUntil) {
        return new Input(input.now(), input.sourceStatus(), input.currentlyVerified(),
                input.firstFinishedObservedAt(), nextDueAt, dueReason, transportRetryAt, cooldownUntil,
                input.recoveryDay(), input.recoveryStartedAt(), input.recoveryUntil(), input.recoveryPublishedAt(),
                input.dependencyRetryAt());
    }

    private static Input withVerified(Input input) {
        return with(input, value -> new Input(value.now(), value.sourceStatus(), true,
                value.firstFinishedObservedAt(), value.nextDueAt(), value.dueReason(), value.transportRetryAt(),
                value.cooldownUntil(), value.recoveryDay(), value.recoveryStartedAt(), value.recoveryUntil(),
                value.recoveryPublishedAt(), value.dependencyRetryAt()));
    }

    private static Input withSource(Input input, SourceStatus status) {
        return with(input, value -> new Input(value.now(), status, value.currentlyVerified(),
                value.firstFinishedObservedAt(), value.nextDueAt(), value.dueReason(), value.transportRetryAt(),
                value.cooldownUntil(), value.recoveryDay(), value.recoveryStartedAt(), value.recoveryUntil(),
                value.recoveryPublishedAt(), value.dependencyRetryAt()));
    }

    private static Input with(Input input, UnaryOperator<Input> change) {
        return change.apply(input);
    }
}
