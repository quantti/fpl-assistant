package fi.karivanttinen.fplassistant.squad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.tools.ClubRates;
import fi.karivanttinen.fplassistant.tools.MinutesModel;
import fi.karivanttinen.fplassistant.tools.SeasonBlend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The squad builder scores exactly the fixture population {@link HorizonFixtures} derives — the
 * same derivation the pick tools rank on. This class used to exercise a second, private window
 * inside {@code HorizonScorer}; sharing one is the whole point of KAR-35.
 */
class HorizonScorerIntegrationTest {

    private static final int HORIZON = 3;
    private static final String SEASON = "T-HORIZON";
    private static final int SINGLE = 9601;
    private static final int DOUBLE = 9602;
    private static final int BLANK = 9603;
    private static final int ALREADY_PLAYED = 9604;
    private static final int RIVAL = 9605;
    private static final int UNRESOLVED = 9606;
    private static final int SPARE = 9607;
    private static final Instant CAPTURED = Instant.parse("2026-08-15T17:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-15T18:00:00Z");

    private static ScheduleSource.EventRow event(int id, String deadline) {
        return new ScheduleSource.EventRow(id, Instant.parse(deadline), CAPTURED);
    }

    private static ScheduleSource.FixtureRow fixture(int id, int gameweek, int home, int away,
                                                     int homeFdr, int awayFdr, String kickoff) {
        return new ScheduleSource.FixtureRow(id, gameweek, home, away,
                Instant.parse(kickoff), false, false, homeFdr, awayFdr, CAPTURED);
    }

    /**
     * Four rounds a week apart, deadlines at 17:30 and every kickoff the day after its own
     * deadline. {@code NOW} sits half an hour past round one's deadline, so the window opens at
     * round two and covers rounds two to four.
     */
    private static ScheduleSource.View view() {
        List<ScheduleSource.EventRow> events = List.of(
                event(1, "2026-08-15T17:30:00Z"), event(2, "2026-08-22T17:30:00Z"),
                event(3, "2026-08-29T17:30:00Z"), event(4, "2026-09-05T17:30:00Z"));
        List<ScheduleSource.FixtureRow> fixtures = List.of(
                fixture(1, 1, SINGLE, RIVAL, 3, 3, "2026-08-16T14:00:00Z"),
                fixture(2, 2, RIVAL, SINGLE, 3, 2, "2026-08-23T14:00:00Z"),
                fixture(3, 3, SINGLE, RIVAL, 4, 3, "2026-08-30T14:00:00Z"),
                fixture(4, 4, SINGLE, RIVAL, 1, 3, "2026-09-06T14:00:00Z"),
                fixture(5, 2, RIVAL, DOUBLE, 3, 2, "2026-08-23T12:00:00Z"),
                fixture(6, 2, DOUBLE, RIVAL, 5, 3, "2026-08-25T18:00:00Z"),
                fixture(7, 3, RIVAL, DOUBLE, 3, 1, "2026-08-30T16:00:00Z"),
                fixture(8, 4, DOUBLE, RIVAL, 3, 3, "2026-09-06T16:00:00Z"),
                fixture(9, 3, RIVAL, BLANK, 3, 4, "2026-08-30T18:00:00Z"),
                new ScheduleSource.FixtureRow(10, 2, ALREADY_PLAYED, RIVAL,
                        Instant.parse("2026-08-23T12:00:00Z"), true, true, 3, 3, CAPTURED),
                fixture(11, 3, ALREADY_PLAYED, RIVAL, 3, 3, "2026-08-30T12:00:00Z"),
                new ScheduleSource.FixtureRow(12, null, UNRESOLVED, SPARE, null,
                        false, false, 3, 3, CAPTURED));
        return new ScheduleSource.View(
                Optional.of(new ScheduleSource.Snapshot(SEASON, CAPTURED)), events, fixtures,
                new ScheduleSource.Completeness(true, ScheduleSource.CompletenessReason.COMPLETE));
    }

    private static HorizonFixtures.Result horizon(ScheduleSource.View view) {
        return HorizonFixtures.derive(view, NOW, SEASON, HORIZON);
    }

    /** One rankable player, moved from club to club so only the schedule differs between rows. */
    private static SquadCandidate playerAt(int teamId) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 4);
        metrics.put("xg90", BigDecimal.valueOf(0.5));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("defcon_avg", BigDecimal.ZERO);
        metrics.put("defcon_matches", 2);
        return new SquadCandidate(teamId, "P" + teamId, "Player " + teamId, "Club " + teamId,
                "C" + teamId, 4, 80, 5.0, "a", null, teamId, 90, metrics,
                SquadCandidate.Tier.RANKABLE, null,
                new SeasonBlend.Exposure(10, 10, 10, 900, 1, 1, 0, 90, 4),
                MinutesModel.Eligibility.ELIGIBLE);
    }

    private static HorizonScorer.Projection projectionFor(int teamId, ScheduleSource.View view) {
        return HorizonScorer.projection(playerAt(teamId), horizon(view).club(teamId),
                ClubRates.NONE);
    }

    @Test
    void theWindowOpensAtTheNextEditableRoundAndCoversTheGameweeksAsked() {
        HorizonFixtures.Result horizon = horizon(view());

        assertThat(horizon.status()).isEqualTo(HorizonFixtures.Status.KNOWN);
        assertThat(horizon.startGameweek()).isEqualTo(2);
        assertThat(horizon.endGameweek()).isEqualTo(4);
    }

    @Test
    void anOrdinaryClubIsScoredOverEveryGameweekOfTheWindow() {
        assertThat(projectionFor(SINGLE, view()).fixtures()).isEqualTo(3);
    }

    @Test
    void aDoubleGameweekAddsAFixtureRatherThanDisplacingALaterOne() {
        HorizonScorer.Projection doubled = projectionFor(DOUBLE, view());

        assertThat(doubled.fixtures()).isEqualTo(4);
        assertThat(doubled.optimizerPoints())
                .isGreaterThan(projectionFor(SINGLE, view()).optimizerPoints());
    }

    @Test
    void aBlankGameweekRemovesAFixtureRatherThanPullingALaterOneIn() {
        HorizonScorer.Projection blank = projectionFor(BLANK, view());

        assertThat(blank.available()).isTrue();
        assertThat(blank.fixtures()).isEqualTo(1);
    }

    @Test
    void aFixtureAlreadyPlayedInsideTheWindowIsNotScored() {
        assertThat(projectionFor(ALREADY_PLAYED, view()).fixtures()).isEqualTo(1);
    }

    @Test
    void anOutstandingFixtureWithNoGameweekLeavesItsClubsUnscoredRatherThanAtZero() {
        HorizonScorer.Projection projection = projectionFor(UNRESOLVED, view());

        assertThat(projection.available()).isFalse();
        assertThat(projection.horizonReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE);
    }

    @Test
    void anIncompleteScheduleLeavesEveryClubUnscoredWithTheStructuralReason() {
        ScheduleSource.View incomplete = new ScheduleSource.View(view().snapshot(), view().events(),
                view().fixtures(), new ScheduleSource.Completeness(false,
                        ScheduleSource.CompletenessReason.INVALID_FIXTURE_COUNT));

        HorizonScorer.Projection projection = projectionFor(SINGLE, incomplete);

        assertThat(projection.available()).isFalse();
        assertThat(projection.horizonReason())
                .isEqualTo(HorizonFixtures.UnknownReason.SCHEDULE_INCOMPLETE);
    }
}
