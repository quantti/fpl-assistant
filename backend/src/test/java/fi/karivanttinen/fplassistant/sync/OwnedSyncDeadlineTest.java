package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedSyncDeadlineTest {

    @Test
    void earliestComparesRemainingTimeWhenNanoTimeWraps() {
        long now = Long.MAX_VALUE - 5;
        OwnedSyncDeadline wrapsSoon = OwnedSyncDeadline.after(Duration.ofNanos(10), now);
        OwnedSyncDeadline beforeWrap = OwnedSyncDeadline.after(Duration.ofNanos(3), now);

        assertThat(wrapsSoon.remainingNanos(now)).isEqualTo(10);
        assertThat(beforeWrap.remainingNanos(now)).isEqualTo(3);
        assertThat(OwnedSyncDeadline.earliestAt(now, wrapsSoon, beforeWrap)).isSameAs(beforeWrap);
    }

    @Test
    void earliestRanksAnElapsedDeadlineCorrectlyAfterNanoTimeWraps() {
        long now = Long.MIN_VALUE + 2;
        OwnedSyncDeadline elapsed = OwnedSyncDeadline.after(Duration.ofNanos(1), Long.MAX_VALUE - 5);
        OwnedSyncDeadline upcoming = OwnedSyncDeadline.after(Duration.ofNanos(5), now);

        assertThat(elapsed.remainingNanos(now)).isZero();
        assertThat(upcoming.remainingNanos(now)).isEqualTo(5);
        assertThat(OwnedSyncDeadline.earliestAt(now, upcoming, elapsed)).isSameAs(elapsed);
    }
}
