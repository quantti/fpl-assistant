package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImporterBudgetTimelineTest {
    @Test
    void rollingReservesBoundLongLiveFlagsAndPreservePostMatchAndFinalPasses() {
        // Exercise production ceilings with an explicit attempt trace, not calendar-day counters.
        for (int players : List.of(670, 900)) {
            var window = new ArrayDeque<Instant>();
            List<Instant> admissions = new ArrayList<>();
            int liveDeferred = 0;
            int mandatoryAccepted = 0;
            Instant start = Instant.parse("2026-11-01T23:30:00Z");
            for (int hour = 0; hour < 48; hour++) {
                Instant now = start.plus(Duration.ofHours(hour));
                while (!window.isEmpty() && !window.getFirst().isAfter(now.minus(Duration.ofHours(24)))) {
                    window.removeFirst();
                }
                int phase = hour % 24;
                var purpose = phase == 12 || phase == 16 ? OwnedSyncRequestBudget.Purpose.POST_MATCH
                        : phase == 20 ? OwnedSyncRequestBudget.Purpose.FINAL : OwnedSyncRequestBudget.Purpose.LIVE;
                for (int request = 0; request < players + 3; request++) {
                    var demand = request < 2 ? OwnedSyncRequestBudget.Demand.DISCOVERY
                            : new OwnedSyncRequestBudget.Demand(purpose, players);
                    if (window.size() >= demand.ceiling(6000)) {
                        if (purpose == OwnedSyncRequestBudget.Purpose.LIVE) liveDeferred++;
                        else throw new AssertionError("Reserved mandatory pass was starved");
                    } else {
                        window.addLast(now);
                        admissions.add(now);
                        if (request >= 2 && purpose != OwnedSyncRequestBudget.Purpose.LIVE) mandatoryAccepted++;
                    }
                }
            }
            assertThat(liveDeferred).isPositive();
            assertThat(mandatoryAccepted).isEqualTo(6 * (players + 1));
            for (Instant at : admissions.stream().distinct().toList()) {
                assertThat(admissions.stream().filter(t -> t.isAfter(at.minus(Duration.ofHours(24)))
                        && !t.isAfter(at)).count()).isLessThanOrEqualTo(6000);
            }
        }
    }
}
