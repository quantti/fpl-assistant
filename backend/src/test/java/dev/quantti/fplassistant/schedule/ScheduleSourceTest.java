package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.quantti.fplassistant.testsupport.RankingSeed;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-08-29T02:10:00Z");
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-14T17:30:00Z");

    @Test
    void namesTheSeasonAndTheCaptureAge() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-29T01:10:00Z"), NOW);

        assertThat(line).startsWith("Data source: FPL's own gameweek and fixture list for season 2026-27");
        assertThat(line).contains("captured today");
    }

    @Test
    void anOldCaptureSaysHowOldItIs() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-26T01:10:00Z"), NOW);

        assertThat(line).contains("captured 3 days ago");
        assertThat(line).contains("scores and postponements may have changed since");
    }

    @Test
    void aCompleteScheduleAllowsGameweekAndKickoffToBeIndependentlyNull() {
        var snapshot = Optional.of(new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, CAPTURED));
        var fixtures = new ArrayList<>(RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));
        var unassigned = fixtures.getFirst();
        fixtures.set(0, new ScheduleSource.FixtureRow(unassigned.id(), null,
                unassigned.homeTeamId(), unassigned.awayTeamId(), unassigned.kickoff(), false, false,
                unassigned.homeDifficulty(), unassigned.awayDifficulty(), CAPTURED));
        var undated = fixtures.get(1);
        fixtures.set(1, new ScheduleSource.FixtureRow(undated.id(), undated.gameweek(),
                undated.homeTeamId(), undated.awayTeamId(), null, false, false,
                undated.homeDifficulty(), undated.awayDifficulty(), CAPTURED));

        assertThat(ScheduleSource.assess(snapshot,
                RankingSeed.events(CAPTURED, FIRST_DEADLINE), fixtures))
                .isEqualTo(new ScheduleSource.Completeness(
                        true, ScheduleSource.CompletenessReason.COMPLETE));
    }

    @Test
    void missingCaptureEventsOrFixturesAreIncomplete() {
        assertThat(ScheduleSource.assess(Optional.empty(), List.of(), List.of()).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.NO_CAPTURE);

        var snapshot = Optional.of(new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, CAPTURED));
        assertThat(ScheduleSource.assess(snapshot,
                RankingSeed.events(CAPTURED, FIRST_DEADLINE).subList(0, 37),
                RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE)).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_EVENT_SET);
        assertThat(ScheduleSource.assess(snapshot,
                RankingSeed.events(CAPTURED, FIRST_DEADLINE),
                RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE).subList(0, 379)).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_FIXTURE_COUNT);
    }

    @Test
    void mixedTimestampsAndConflictingIdsAreIncomplete() {
        var snapshot = Optional.of(new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, CAPTURED));
        var mixed = new ArrayList<>(RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));
        var first = mixed.getFirst();
        mixed.set(0, new ScheduleSource.FixtureRow(first.id(), first.gameweek(),
                first.homeTeamId(), first.awayTeamId(), first.kickoff(), false, false,
                first.homeDifficulty(), first.awayDifficulty(), CAPTURED.plusSeconds(1)));
        assertThat(ScheduleSource.assess(snapshot,
                RankingSeed.events(CAPTURED, FIRST_DEADLINE), mixed).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.MIXED_CAPTURE_TIMESTAMPS);

        var duplicateId = new ArrayList<>(RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));
        var second = duplicateId.get(1);
        duplicateId.set(0, new ScheduleSource.FixtureRow(second.id(),
                duplicateId.getFirst().gameweek(), duplicateId.getFirst().homeTeamId(),
                duplicateId.getFirst().awayTeamId(), duplicateId.getFirst().kickoff(), false,
                false, 3, 3, CAPTURED));
        assertThat(ScheduleSource.assess(snapshot,
                RankingSeed.events(CAPTURED, FIRST_DEADLINE), duplicateId).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.DUPLICATE_FIXTURE_ID);
    }

    @Test
    void invalidSidesGameweeksClubsAndPairingsAreIncomplete() {
        var snapshot = Optional.of(new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, CAPTURED));
        var events = RankingSeed.events(CAPTURED, FIRST_DEADLINE);
        var original = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);

        var self = replaceFirst(original, row -> new ScheduleSource.FixtureRow(row.id(), row.gameweek(),
                row.homeTeamId(), row.homeTeamId(), row.kickoff(), false, false, 3, 3, CAPTURED));
        assertThat(ScheduleSource.assess(snapshot, events, self).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.SELF_FIXTURE);

        var badGameweek = replaceFirst(original, row -> new ScheduleSource.FixtureRow(row.id(), 39,
                row.homeTeamId(), row.awayTeamId(), row.kickoff(), false, false, 3, 3, CAPTURED));
        assertThat(ScheduleSource.assess(snapshot, events, badGameweek).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_ASSIGNED_GAMEWEEK);

        var missingClub = original.stream().map(row -> new ScheduleSource.FixtureRow(row.id(),
                row.gameweek(), row.homeTeamId() == RankingSeed.team(20) ? RankingSeed.team(19)
                        : row.homeTeamId(),
                row.awayTeamId() == RankingSeed.team(20) ? RankingSeed.team(19) : row.awayTeamId(),
                row.kickoff(), false, false, 3, 3, CAPTURED)).toList();
        assertThat(ScheduleSource.assess(snapshot, events, missingClub).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_CLUB_SET);

        var duplicatePair = new ArrayList<>(original);
        var first = duplicatePair.getFirst();
        var second = duplicatePair.get(1);
        duplicatePair.set(0, new ScheduleSource.FixtureRow(first.id(), first.gameweek(),
                second.homeTeamId(), second.awayTeamId(), first.kickoff(), false, false,
                3, 3, CAPTURED));
        assertThat(ScheduleSource.assess(snapshot, events, duplicatePair).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.DUPLICATE_PAIRING);
    }

    private static List<ScheduleSource.FixtureRow> replaceFirst(
            List<ScheduleSource.FixtureRow> source,
            java.util.function.Function<ScheduleSource.FixtureRow, ScheduleSource.FixtureRow> replacement) {
        var rows = new ArrayList<>(source);
        rows.set(0, replacement.apply(rows.getFirst()));
        return rows;
    }
}
