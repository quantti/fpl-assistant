package dev.quantti.fplassistant.squad;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.quantti.fplassistant.schedule.ScheduleSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HorizonScorerIntegrationTest {

    private static final int HORIZON = 3;
    private static final int SINGLE = 9601;
    private static final int DOUBLE = 9602;
    private static final int BLANK = 9603;
    private static final int ALREADY_PLAYED = 9604;
    private static final int RIVAL = 9605;
    private static final Instant CAPTURED = Instant.parse("2026-08-15T17:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-15T18:00:00Z");

    private final HorizonScorer scorer = new HorizonScorer();

    private static ScheduleSource.EventRow event(int id, String deadline) {
        return new ScheduleSource.EventRow(id, Instant.parse(deadline), CAPTURED);
    }

    private static ScheduleSource.FixtureRow fixture(int id, int gameweek, int home, int away,
                                                      int homeFdr, int awayFdr, String kickoff) {
        return new ScheduleSource.FixtureRow(id, gameweek, home, away,
                Instant.parse(kickoff), false, false, homeFdr, awayFdr, CAPTURED);
    }

    private static ScheduleSource.View view() {
        List<ScheduleSource.EventRow> events = List.of(
                event(1, "2026-08-15T17:30:00Z"), event(2, "2026-08-22T17:30:00Z"),
                event(3, "2026-08-29T17:30:00Z"), event(4, "2026-09-05T17:30:00Z"));
        List<ScheduleSource.FixtureRow> fixtures = List.of(
                fixture(1, 1, SINGLE, RIVAL, 3, 3, "2026-08-15T20:00:00Z"),
                fixture(2, 2, RIVAL, SINGLE, 3, 2, "2026-08-22T14:00:00Z"),
                fixture(3, 3, SINGLE, RIVAL, 4, 3, "2026-08-29T14:00:00Z"),
                fixture(4, 4, SINGLE, RIVAL, 1, 3, "2026-09-05T14:00:00Z"),
                fixture(5, 1, DOUBLE, RIVAL, 3, 3, "2026-08-15T20:30:00Z"),
                fixture(6, 2, RIVAL, DOUBLE, 3, 2, "2026-08-22T12:00:00Z"),
                fixture(7, 2, DOUBLE, RIVAL, 5, 3, "2026-08-24T18:00:00Z"),
                fixture(8, 3, RIVAL, DOUBLE, 3, 1, "2026-08-29T16:00:00Z"),
                fixture(9, 1, BLANK, RIVAL, 3, 3, "2026-08-15T21:00:00Z"),
                fixture(10, 3, RIVAL, BLANK, 3, 4, "2026-08-29T18:00:00Z"),
                new ScheduleSource.FixtureRow(11, 1, ALREADY_PLAYED, RIVAL,
                        Instant.parse("2026-08-15T12:00:00Z"), true, true, 3, 3, CAPTURED),
                fixture(12, 2, RIVAL, ALREADY_PLAYED, 3, 2, "2026-08-22T12:00:00Z"),
                fixture(13, 3, ALREADY_PLAYED, RIVAL, 4, 3, "2026-08-29T12:00:00Z"));
        return new ScheduleSource.View(
                Optional.of(new ScheduleSource.Snapshot("T-HORIZON", CAPTURED)), events, fixtures,
                new ScheduleSource.Completeness(true, ScheduleSource.CompletenessReason.COMPLETE));
    }

    @Test
    void anOrdinaryClubGetsOneEntryPerGameweekAndNothingBeyondTheHorizon() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(view(), NOW, HORIZON);
        assertThat(byTeam.get(SINGLE)).containsExactly(3, 2, 4);
    }

    @Test
    void aDoubleGameweekAddsAFixtureRatherThanDisplacingALaterOne() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(view(), NOW, HORIZON);
        assertThat(byTeam.get(DOUBLE)).containsExactly(3, 2, 5, 1);
    }

    @Test
    void aBlankGameweekContributesNothingRatherThanAPhantomEntry() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(view(), NOW, HORIZON);
        assertThat(byTeam.get(BLANK)).containsExactly(3, 4);
    }

    @Test
    void aFixtureAlreadyPlayedInTheCurrentGameweekIsExcluded() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(view(), NOW, HORIZON);
        assertThat(byTeam.get(ALREADY_PLAYED)).containsExactly(2, 4);
    }

    @Test
    void anIncompleteScheduleHasNoUsableProjection() {
        ScheduleSource.View incomplete = new ScheduleSource.View(view().snapshot(), view().events(),
                view().fixtures(), new ScheduleSource.Completeness(false,
                        ScheduleSource.CompletenessReason.INVALID_FIXTURE_COUNT));

        assertThat(scorer.fixtureDifficulties(incomplete, NOW, HORIZON)).isEmpty();
    }
}
