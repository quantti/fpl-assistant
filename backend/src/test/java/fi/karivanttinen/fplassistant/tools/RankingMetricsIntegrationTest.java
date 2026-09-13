package fi.karivanttinen.fplassistant.tools;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RankingMetricsIntegrationTest {

    private static final int PREVIOUS_SEASON_ID = 9930;
    private static final String PREVIOUS_SEASON = "K31-PREV";
    private static final int SEASON_ID = 9931;
    private static final String SEASON = "K31-CUR";
    private static final LocalDate CAPTURE_DATE = LocalDate.of(2026, 9, 9);
    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-09-09T01:10:00Z");
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-09-18T17:30:00Z");
    private static final int PLAYER = 9811;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock rankingClock() {
            return Clock.fixed(AS_OF, ZoneOffset.UTC);
        }
    }

    @Autowired RankingMetrics metrics;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.daily-price.table-name}") String dailyTable;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @BeforeEach
    void setUp() {
        TapasSeed.ensure(jdbc);
        cleanUp();
        jdbc.update("""
                INSERT INTO season (id, code, name, start_date, is_current) VALUES
                    (?, ?, 'KAR-31 previous test season', DATE '2025-08-01', false),
                    (?, ?, 'KAR-31 current test season', DATE '2026-08-01', false)
                """, PREVIOUS_SEASON_ID, PREVIOUS_SEASON, SEASON_ID, SEASON);
        seedSchedule(SEASON, CAPTURED_AT);
    }

    @AfterEach
    void cleanUp() {
        TapasSeed.ensure(jdbc);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id IN (?, ?)",
                SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("DELETE FROM player WHERE season_id IN (?, ?)", SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id IN (?, ?)", SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("DELETE FROM team WHERE season_id IN (?, ?)", SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?", SEASON);
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season IN (?, ?)", SEASON, "K31-OTHER");
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season IN (?, ?)", SEASON, "K31-OTHER");
        jdbc.update("DELETE FROM season WHERE id IN (?, ?)", SEASON_ID, PREVIOUS_SEASON_ID);
    }

    @Test
    void shortCompleteWindowsUseCanonicalSidesAndKeepEveryCaptureRow() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Reliable", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        seedDaily(PLAYER + 1, "Other", RankingSeed.team(2), 3, "i", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = finishFixtures(club, 2);
        seedPfs(PLAYER, fixtures.get(0), 90, club, 99_999, null);
        seedPfs(PLAYER, fixtures.get(1), 90, club, 99_999, null);

        RankingMetrics.Snapshot snapshot = load(CAPTURE_DATE);
        RankingMetrics.Candidate reliable = candidate(snapshot, PLAYER);

        assertThat(snapshot.asOf()).isEqualTo(AS_OF);
        assertThat(snapshot.schedule().completeness().complete()).isTrue();
        assertThat(snapshot.candidates()).hasSize(2);
        assertThat(reliable.current().window().complete()).isTrue();
        assertThat(reliable.current().window().expected()).isEqualTo(2);
        assertThat(reliable.current().window().observed()).isEqualTo(2);
        assertThat(reliable.current().window().totalMinutes()).isEqualTo(180);
        assertThat(reliable.current().window().sixtyPlus()).isEqualTo(2);
        assertThat(reliable.current().playerId()).isEqualTo(PLAYER);
        assertThat(reliable.current().ability()).containsEntry("mins", 180);
    }

    @Test
    void twoRowsCannotShrinkATenFixtureWindowAndSameClubDailyHistoryIsNotAFence() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Gap", club, 2, "a", CAPTURE_DATE.minusDays(20),
                CAPTURED_AT.minus(20, ChronoUnit.DAYS));
        seedDaily(PLAYER, "Gap", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = finishFixtures(club, 10);
        seedPfs(PLAYER, fixtures.get(8), 90, club, club, null);
        seedPfs(PLAYER, fixtures.get(9), 90, club, club, null);

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.complete()).isFalse();
        assertThat(window.reason()).isEqualTo(RankingMetrics.WindowReason.MISSING_OBSERVATION);
        assertThat(window.expected()).isEqualTo(10);
        assertThat(window.observed()).isEqualTo(2);
        assertThat(window.latestTwo()).allSatisfy(outcome -> assertThat(outcome.minutes()).isEqualTo(90));
    }

    @Test
    void aMissedDoubleGameweekLegRemainsAnExpectedMissingObservation() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "DoubleGap", club, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = fixtureIdsFor(club).subList(0, 2);
        finishFixture(fixtures.get(0), Instant.parse("2026-08-01T12:00:00Z"));
        finishFixture(fixtures.get(1), Instant.parse("2026-08-02T12:00:00Z"));
        jdbc.update("UPDATE " + fixtureTable + " SET gameweek = 1"
                + " WHERE season = ? AND id IN (?, ?)", SEASON, fixtures.get(0), fixtures.get(1));
        seedPfs(PLAYER, fixtures.get(0), 90, club, club, null);

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.expected()).isEqualTo(2);
        assertThat(window.observed()).isEqualTo(1);
        assertThat(window.reason()).isEqualTo(RankingMetrics.WindowReason.MISSING_OBSERVATION);
        assertThat(window.latestTwo().getFirst().fixtureId()).isEqualTo(fixtures.get(1));
        assertThat(window.latestTwo().getFirst().minutes()).isNull();
    }

    @Test
    void fixtureDatesRatherThanGameweekNumbersOrderTheWindow() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Chronology", club, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = fixtureIdsFor(club).subList(0, 3);
        List<Instant> kickoffs = List.of(
                Instant.parse("2026-08-03T12:00:00Z"),
                Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-08-02T12:00:00Z"));
        int[] gameweeks = {1, 30, 20};
        for (int index = 0; index < fixtures.size(); index++) {
            finishFixture(fixtures.get(index), kickoffs.get(index));
            jdbc.update("UPDATE " + fixtureTable + " SET gameweek = ? WHERE season = ? AND id = ?",
                    gameweeks[index], SEASON, fixtures.get(index));
            seedPfs(PLAYER, fixtures.get(index), 60, club, club, null);
        }

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.latestTwo()).extracting(RankingMetrics.FixtureOutcome::fixtureId)
                .containsExactly(fixtures.get(0), fixtures.get(2));
    }

    @Test
    void anUnfencedNewcomerBecomesJudgeableOnlyWhenTheLatestTenAreComplete() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "TenComplete", club, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = finishFixtures(club, 10);
        fixtures.forEach(fixture -> seedPfs(PLAYER, fixture, 60, club, club, null));

        RankingMetrics.Candidate candidate = candidate(load(CAPTURE_DATE), PLAYER);

        assertThat(candidate.current().window().expected()).isEqualTo(10);
        assertThat(candidate.current().window().observed()).isEqualTo(10);
        assertThat(candidate.current().window().complete()).isTrue();
        assertThat(candidate.eligibility()).isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
    }

    @Test
    void aVerifiedPfsDifferentClubStartsANewSpellButAMissingPostFenceRowStillBlocks() {
        int oldClub = RankingSeed.team(1);
        int newClub = RankingSeed.team(2);
        seedDaily(PLAYER, "Mover", newClub, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        int oldFixture = fixtureIdsFor(oldClub).stream()
                .filter(id -> !fixtureInvolves(id, newClub)).findFirst().orElseThrow();
        finishFixture(oldFixture, Instant.parse("2026-08-01T12:00:00Z"));
        seedPfs(PLAYER, oldFixture, 90, oldClub, newClub, null); // deliberately mis-stamped team
        List<Integer> newClubSchedule = fixtureIdsFor(newClub);
        finishFixture(newClubSchedule.get(0), Instant.parse("2026-07-20T12:00:00Z"));
        finishFixture(newClubSchedule.get(1), Instant.parse("2026-07-27T12:00:00Z"));
        List<Integer> newFixtures = newClubSchedule.subList(2, 5);
        for (int index = 0; index < newFixtures.size(); index++) {
            finishFixture(newFixtures.get(index),
                    Instant.parse("2026-08-20T12:00:00Z").plus(index, ChronoUnit.DAYS));
        }
        seedPfs(PLAYER, newFixtures.get(0), 60, newClub, oldClub, null);
        seedPfs(PLAYER, newFixtures.get(2), 90, newClub, oldClub, null);

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.clubId()).isEqualTo(newClub);
        assertThat(window.expected()).isEqualTo(3);
        assertThat(window.observed()).isEqualTo(2);
        assertThat(window.reason()).isEqualTo(RankingMetrics.WindowReason.MISSING_OBSERVATION);
    }

    @Test
    void datedDifferentClubMembershipCanFenceTheSpell() {
        int oldClub = RankingSeed.team(1);
        int newClub = RankingSeed.team(2);
        seedDaily(PLAYER, "DailyMover", oldClub, 3, "a", CAPTURE_DATE.minusDays(20),
                Instant.parse("2026-08-10T08:00:00Z"));
        seedDaily(PLAYER, "DailyMover", 99_999, 3, "a", CAPTURE_DATE.minusDays(1),
                Instant.parse("2026-09-08T08:00:00Z"));
        seedDaily(PLAYER, "DailyMover", newClub, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> newClubSchedule = fixtureIdsFor(newClub);
        finishFixture(newClubSchedule.get(0), Instant.parse("2026-08-01T12:00:00Z"));
        finishFixture(newClubSchedule.get(1), Instant.parse("2026-08-05T12:00:00Z"));
        List<Integer> newFixtures = newClubSchedule.subList(2, 5);
        for (int index = 0; index < newFixtures.size(); index++) {
            finishFixture(newFixtures.get(index),
                    Instant.parse("2026-08-20T12:00:00Z").plus(index, ChronoUnit.DAYS));
        }
        for (int fixture : newFixtures) {
            seedPfs(PLAYER, fixture, 60, newClub, 123, null);
        }

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.complete()).isTrue();
        assertThat(window.expected()).isEqualTo(3);
        assertThat(window.totalMinutes()).isEqualTo(180);
    }

    @Test
    void unresolvedOrContradictoryHistoryMakesCurrentEvidenceUnknown() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Unknown", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        int fixture = finishFixtures(club, 1).getFirst();
        seedPfs(PLAYER, fixture, 90, club, club, club);

        RankingMetrics.MinutesWindow contradictory = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();
        assertThat(contradictory.reason())
                .isEqualTo(RankingMetrics.WindowReason.CONTRADICTORY_OPPONENT);

        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ?", SEASON_ID);
        seedUnmappablePfs(PLAYER, 999_999, club);
        RankingMetrics.MinutesWindow unmappable = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();
        assertThat(unmappable.reason()).isEqualTo(RankingMetrics.WindowReason.UNMAPPABLE_HISTORY);
    }

    @Test
    void aPassedUnfinishedFixtureBlocksInsteadOfBecomingAZero() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Pending", club, 4, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> finished = finishFixtures(club, 3);
        for (int fixture : finished) {
            seedPfs(PLAYER, fixture, 90, club, club, null);
        }
        int live = fixtureIdsFor(club).get(5);
        jdbc.update("UPDATE " + fixtureTable
                        + " SET kickoff_time = ?, started = true, finished = false WHERE season = ? AND id = ?",
                Timestamp.from(AS_OF.minus(30, ChronoUnit.MINUTES)), SEASON, live);

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.reason()).isEqualTo(RankingMetrics.WindowReason.RESULT_PENDING);
        assertThat(window.observed()).isEqualTo(3);
    }

    @Test
    void stalePartialMissingAndWrongSeasonSchedulesNeverCreateCurrentCoverage() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Source", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);

        RankingMetrics.MinutesWindow fresh = candidate(load(CAPTURE_DATE), PLAYER).current().window();
        assertThat(fresh.complete()).isTrue();
        assertThat(fresh.expected()).isZero();

        seedSchedule(SEASON, CAPTURED_AT.minus(1, ChronoUnit.DAYS));
        assertThat(candidate(load(CAPTURE_DATE), PLAYER).current().window().reason())
                .isEqualTo(RankingMetrics.WindowReason.SCHEDULE_CAPTURE_STALE);

        seedSchedule(SEASON, CAPTURED_AT);
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ? AND id = ?", SEASON,
                fixtureIdsFor(club).getFirst());
        assertThat(candidate(load(CAPTURE_DATE), PLAYER).current().window().reason())
                .isEqualTo(RankingMetrics.WindowReason.SCHEDULE_INCOMPLETE);

        RankingSeed.cleanSchedule(jdbc, gameweekTable, fixtureTable, SEASON);
        assertThat(candidate(load(CAPTURE_DATE), PLAYER).current().window().reason())
                .isEqualTo(RankingMetrics.WindowReason.SCHEDULE_CAPTURE_MISSING);

        seedSchedule("K31-OTHER", CAPTURED_AT.plusSeconds(1));
        assertThat(candidate(load(CAPTURE_DATE), PLAYER).current().window().reason())
                .isEqualTo(RankingMetrics.WindowReason.SOURCE_SEASON_MISMATCH);
    }

    @Test
    void bothPrimaryListsRejectCameosDoubtfulPlayersAndKnownZerosBeforeRanking() {
        int reliable = PLAYER;
        int cameo = PLAYER + 1;
        int doubtful = PLAYER + 2;
        int zero = PLAYER + 3;
        int reliableClub = RankingSeed.team(1);
        int cameoClub = RankingSeed.team(2);
        int doubtfulClub = RankingSeed.team(3);
        int zeroClub = RankingSeed.team(4);
        seedDaily(reliable, "Reliable", reliableClub, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        seedDaily(cameo, "Cameo", cameoClub, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        seedDaily(doubtful, "Doubtful", doubtfulClub, 4, "d", CAPTURE_DATE, CAPTURED_AT);
        seedDaily(zero, "LostRole", zeroClub, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        Set<Integer> candidateClubs = Set.of(reliableClub, cameoClub, doubtfulClub, zeroClub);

        List<Integer> reliableFixtures = finishFixturesAvoiding(reliableClub, 3, candidateClubs, 0);
        reliableFixtures.forEach(id -> seedPfsStats(reliable, id, 60, reliableClub, 4, 0.3));
        List<Integer> cameoFixtures = finishFixturesAvoiding(cameoClub, 9, candidateClubs, 20);
        cameoFixtures.forEach(id -> seedPfsStats(cameo, id, 20, cameoClub, 20, 2.0));
        List<Integer> doubtfulFixtures = finishFixturesAvoiding(doubtfulClub, 3, candidateClubs, 40);
        doubtfulFixtures.forEach(id -> seedPfsStats(doubtful, id, 90, doubtfulClub, 20, 2.0));
        List<Integer> zeroFixtures = finishFixturesAvoiding(zeroClub, 3, candidateClubs, 60);
        zeroFixtures.forEach(id -> seedPfsStats(zero, id, 0, zeroClub, 20, 2.0));
        seedPreviousRegular(zero, "LostRole", 3);

        SeasonContext context = context();
        SeasonContext previous = new SeasonContext(PREVIOUS_SEASON_ID, PREVIOUS_SEASON, false,
                SEASON, LocalDate.of(2026, 8, 1));
        MetricSeasons metricSeasons = new MetricSeasons(
                context, java.util.Optional.of(previous), 19, 19);
        SeasonResolver resolver = Mockito.mock(SeasonResolver.class);
        Mockito.when(resolver.resolve()).thenReturn(context);
        Mockito.when(resolver.metricSeasons(context)).thenReturn(metricSeasons);
        DailyPriceSource selectedCapture = Mockito.mock(DailyPriceSource.class);
        Mockito.when(selectedCapture.latestCapture())
                .thenReturn(java.util.Optional.of(new DailyPriceSource.Capture(SEASON, CAPTURE_DATE)));
        PickTools picks = new PickTools(resolver, selectedCapture, metrics);

        String differentials = picks.getDifferentialPicks("any", 100, 3);
        String captains = picks.getCaptainCandidates(3);

        for (String result : List.of(differentials, captains)) {
            assertThat(result).contains(" Reliable (")
                    .contains("Score/fixture")
                    .contains("observed current-club 60+ 3/3, 180 mins")
                    .contains("short window: 3 fixtures")
                    .contains("complete FPL schedule capture at " + CAPTURED_AT)
                    .contains("availability risk 1")
                    .contains("observed minutes risk 2")
                    .doesNotContain(" Cameo (")
                    .doesNotContain(" Doubtful (")
                    .doesNotContain(" LostRole (")
                    .doesNotContain("Pts/90")
                    .doesNotContainIgnoringCase("guaranteed starter");
        }
    }

    @Test
    void futureFinishedRowsCannotAdvanceTheCurrentSeasonBlendRamp() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "FutureRows", club, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = fixtureIdsFor(club).subList(0, 8);
        for (int index = 0; index < fixtures.size(); index++) {
            Instant kickoff = index < 2
                    ? Instant.parse("2026-08-01T12:00:00Z").plus(index, ChronoUnit.DAYS)
                    : AS_OF.plus(index, ChronoUnit.DAYS);
            finishFixture(fixtures.get(index), kickoff);
            seedPfs(PLAYER, fixtures.get(index), 90, club, club, null);
        }

        RankingMetrics.Candidate candidate = candidate(load(CAPTURE_DATE), PLAYER);

        assertThat(candidate.current().window().expected()).isEqualTo(2);
        assertThat(candidate.blended().matchesThisSeason()).isEqualTo(2);
        assertThat(candidate.blended().weight()).isEqualTo(0.30);
    }

    @Test
    void aPreviousRosterRowWithoutEvidenceIsNotReportedAsAPreviousRecord() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "RosterOnly", club, 3, "a", CAPTURE_DATE, CAPTURED_AT);
        for (int fixture : finishFixtures(club, 2)) {
            seedPfs(PLAYER, fixture, 90, club, club, null);
        }
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength)
                VALUES (9851, ?, 9851, 'Prior Roster Club', 'PRC', 3)
                """, PREVIOUS_SEASON_ID);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, web_name, element_type, now_cost)
                VALUES (?, ?, 9851, 'RosterOnly', 3, 50)
                """, PLAYER, PREVIOUS_SEASON_ID);

        RankingMetrics.Candidate candidate = candidate(loadWithPrevious(), PLAYER);

        assertThat(candidate.previous()).isNotNull();
        assertThat(candidate.previous().ability()).isEmpty();
        assertThat(candidate.blended().hasPrevious()).isFalse();
    }

    @Test
    void changedOrUnknownPreviousPositionsCannotSupplyDefconMeansOrCounts() {
        int currentClubA = RankingSeed.team(1);
        int currentClubB = RankingSeed.team(3);
        seedDaily(PLAYER, "ChangedPosition", currentClubA, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        seedDaily(PLAYER + 1, "UnknownPosition", currentClubB, 2, "a",
                CAPTURE_DATE, CAPTURED_AT);
        for (int fixture : finishFixtures(currentClubA, 2)) {
            seedPfs(PLAYER, fixture, 90, currentClubA, currentClubA, null);
        }
        for (int fixture : finishFixtures(currentClubB, 2)) {
            seedPfs(PLAYER + 1, fixture, 90, currentClubB, currentClubB, null);
        }
        seedPreviousDefconPlayers();

        RankingMetrics.Snapshot snapshot = loadWithPrevious();
        for (int id : List.of(PLAYER, PLAYER + 1)) {
            RankingMetrics.Candidate candidate = candidate(snapshot, id);
            assertThat(candidate.blended().metrics())
                    .containsEntry("defcon_long_matches", 0.0)
                    .containsEntry("defcon_matches", 0.0)
                    .doesNotContainKeys("defcon_long_avg", "defcon_avg");
            Map<String, Object> row = new java.util.HashMap<>(candidate.blended().metrics());
            row.put("element_type", 2);
            assertThat(ExpectedPoints.perFixture(row, candidate.blended().exposure(),
                    OptionalDouble.empty()).orElseThrow().partialDefcon()).isTrue();
        }
    }

    @Test
    void invalidMinutesAndZerosRemainObservedRatherThanBeingDiscarded() {
        int club = RankingSeed.team(1);
        seedDaily(PLAYER, "Boundary", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        List<Integer> fixtures = finishFixtures(club, 3);
        seedPfs(PLAYER, fixtures.get(0), 0, club, club, null);
        seedPfs(PLAYER, fixtures.get(1), 90, club, club, null);
        seedPfs(PLAYER, fixtures.get(2), 91, club, club, null);

        RankingMetrics.MinutesWindow window = candidate(load(CAPTURE_DATE), PLAYER)
                .current().window();

        assertThat(window.reason()).isEqualTo(RankingMetrics.WindowReason.INVALID_MINUTES);
        assertThat(window.expected()).isEqualTo(3);
        assertThat(window.observed()).isEqualTo(3);
        assertThat(window.totalMinutes()).isEqualTo(90);
        assertThat(window.appearances()).isEqualTo(1);
        assertThat(window.sixtyPlus()).isEqualTo(1);
    }

    /** Ghost element ids for one row per club side; never captured, so never candidates. */
    private static final int GHOST_BASE = 9_900_000;

    /**
     * Finishes {@code gameweeks} rounds of the seeded schedule and gives every club one row per
     * side at the same rates, so the league floor is met and every club is average.
     */
    private void seedAverageLeague(int gameweeks, double xg, double xgc) {
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() == null || row.gameweek() > gameweeks) {
                continue;
            }
            Instant kickoff = Instant.parse("2026-08-01T12:00:00Z").plus(row.gameweek(), ChronoUnit.DAYS);
            finishFixture(row.id(), kickoff);
            seedSide(row.id(), row.homeTeamId(), xg, xgc);
            seedSide(row.id(), row.awayTeamId(), xg, xgc);
        }
    }

    private void seedSide(int fixtureId, int club, double xg, double xgc) {
        int ghost = GHOST_BASE + club * 1000 + fixtureId % 1000;
        seedPfs(ghost, fixtureId, 90, club, club, null);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals = ?, expected_goals_conceded = ?"
                        + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                xg, xgc, SEASON_ID, ghost, fixtureId);
    }

    /**
     * The club's fixture in a named round. {@code fixtureIdsFor} lists rounds interleaved
     * (GW1, GW20, GW2, GW21, ...), so its first two entries are not the two rounds
     * {@code seedAverageLeague} finished.
     */
    private static int fixtureFor(int club, int gameweek) {
        return RankingSeed.fixtureFor(RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE),
                club, gameweek);
    }

    @Test
    void clubSidesSumTheirPlayersAttackAndReadTheTeamConcessionOnce() {
        int club = RankingSeed.team(1);
        seedAverageLeague(2, 1.40, 1.40);
        List<Integer> fixtures = List.of(fixtureFor(club, 1), fixtureFor(club, 2));
        // A second player of the same club in the same two fixtures: xG adds, xGC does not.
        seedDaily(PLAYER, "Second", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        for (int fixture : fixtures) {
            seedPfs(PLAYER, fixture, 90, club, club, null);
            jdbc.update("UPDATE player_fixture_stats SET expected_goals = 0.60,"
                            + " expected_goals_conceded = 1.40"
                            + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                    SEASON_ID, PLAYER, fixture);
        }

        ClubRates.Rates rates = load(CAPTURE_DATE).clubRates();
        ClubRates.ClubRate rate = rates.clubs().get(club);

        assertThat(rate.fixturesThisSeason()).isEqualTo(2);
        assertThat(rate.attack()).isCloseTo(2.00, within(1e-9));        // 1.40 ghost + 0.60
        assertThat(rate.defence()).isCloseTo(1.40, within(1e-9));       // MAX, not 2.80
        assertThat(rates.league()).isPresent();
        assertThat(rates.league().orElseThrow().sides()).isEqualTo(40);
    }

    @Test
    void clubSidesStopAtTheSnapshotInstantAndAtUnpopulatedConcession() {
        int club = RankingSeed.team(1);
        seedAverageLeague(3, 1.40, 1.40);
        List<Integer> fixtures = List.of(fixtureFor(club, 1), fixtureFor(club, 2),
                fixtureFor(club, 3));
        // Third fixture finished but dated after asOf: not this snapshot's evidence.
        finishFixture(fixtures.get(2), AS_OF.plus(1, ChronoUnit.DAYS));
        // Second fixture's team concession never written: the side is unpopulated, not perfect.
        jdbc.update("UPDATE player_fixture_stats SET expected_goals_conceded = 0"
                        + " WHERE season_id = ? AND fixture_id = ? AND was_home = ?",
                SEASON_ID, fixtures.get(1), fixtureInvolvesAsHome(fixtures.get(1), club));

        ClubRates.ClubRate rate = load(CAPTURE_DATE).clubRates().clubs().get(club);

        assertThat(rate).isNull();   // one usable side is under the two-side floor
    }

    private boolean fixtureInvolvesAsHome(int fixtureId, int club) {
        Integer home = jdbc.queryForObject("SELECT team_h FROM " + fixtureTable
                + " WHERE season = ? AND id = ?", Integer.class, SEASON, fixtureId);
        return home != null && home == club;
    }

    @Test
    void previousSeasonClubSidesBridgeByShortNameAndRampOnTheClubsOwnFixtures() {
        int club = RankingSeed.team(1);
        seedAverageLeague(2, 1.40, 1.40);
        seedDaily(PLAYER, "Bridged", club, 2, "a", CAPTURE_DATE, CAPTURED_AT);
        // The capture stamps every club 'K31'; give last season one club with that short name
        // and 38 sides, plus a spare with another name that must not attach.
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9861, ?, 9861, 'Prior Same Name', 'K31', 3),
                    (9862, ?, 9862, 'Prior Other', 'PRO', 3)
                """, PREVIOUS_SEASON_ID, PREVIOUS_SEASON_ID);
        for (int index = 0; index < 38; index++) {
            int fixture = 986_100 + index;
            boolean home = index % 2 == 0;
            Instant kickoff = Instant.parse("2025-08-01T12:00:00Z").plus(index, ChronoUnit.DAYS);
            jdbc.update("""
                    INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                         kickoff_time, started, finished)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, true)
                    """, fixture, PREVIOUS_SEASON_ID, index + 1, fixture,
                    home ? 9861 : 9862, home ? 9862 : 9861, Timestamp.from(kickoff));
            jdbc.update("""
                    INSERT INTO player_fixture_stats (
                        fixture_id, player_id, season_id, gameweek, player_team_id,
                        opponent_team_id, was_home, kickoff_time, minutes, total_points,
                        expected_goals, expected_assists, expected_goals_conceded, value, starts)
                    VALUES (?, ?, ?, ?, 9861, 9862, ?, ?, 90, 4, 1.00, 0.10, 2.00, 50, 1)
                    """, fixture, GHOST_BASE + 1, PREVIOUS_SEASON_ID, index + 1, home,
                    Timestamp.from(kickoff));
        }

        ClubRates.ClubRate rate = loadWithPrevious().clubRates().clubs().get(club);

        assertThat(rate.hasPrevious()).isTrue();
        assertThat(rate.fixturesThisSeason()).isEqualTo(2);
        assertThat(rate.weight()).isEqualTo(SeasonBlend.weight(2));
        assertThat(rate.attack()).isCloseTo(0.30 * 1.40 + 0.70 * 1.00, within(1e-9));
        assertThat(rate.defence()).isCloseTo(0.30 * 1.40 + 0.70 * 2.00, within(1e-9));
        // No other club has a captured player in this test, so nothing else bridges: club 2
        // stays on this season alone, which is what an unbridged club must do.
        assertThat(loadWithPrevious().clubRates().clubs().get(RankingSeed.team(2)).hasPrevious())
                .isFalse();
    }

    private RankingMetrics.Snapshot load(LocalDate date) {
        SeasonContext context = context();
        return metrics.load(new DailyPriceSource.Capture(SEASON, date),
                new MetricSeasons(context, java.util.Optional.empty(), 3, 3));
    }

    private RankingMetrics.Snapshot loadWithPrevious() {
        SeasonContext previous = new SeasonContext(PREVIOUS_SEASON_ID, PREVIOUS_SEASON,
                false, SEASON, LocalDate.of(2026, 8, 1));
        return metrics.load(new DailyPriceSource.Capture(SEASON, CAPTURE_DATE),
                new MetricSeasons(context(), java.util.Optional.of(previous), 3, 3));
    }

    private void seedPreviousDefconPlayers() {
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9851, ?, 9851, 'Prior Club', 'PRC', 3),
                    (9852, ?, 9852, 'Prior Opponent', 'PRO', 3)
                """, PREVIOUS_SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, web_name, element_type, now_cost) VALUES
                    (?, ?, 9851, 'ChangedPosition', 3, 50),
                    (?, ?, 9851, 'UnknownPosition', 0, 50)
                """, PLAYER, PREVIOUS_SEASON_ID, PLAYER + 1, PREVIOUS_SEASON_ID);
        for (int index = 0; index < 2; index++) {
            int fixture = 985_100 + index;
            Instant kickoff = Instant.parse("2025-08-01T12:00:00Z")
                    .plus(index, ChronoUnit.DAYS);
            jdbc.update("""
                    INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                         kickoff_time, started, finished)
                    VALUES (?, ?, ?, ?, 9851, 9852, ?, true, true)
                    """, fixture, PREVIOUS_SEASON_ID, index + 1, fixture,
                    Timestamp.from(kickoff));
            for (int id : List.of(PLAYER, PLAYER + 1)) {
                jdbc.update("""
                        INSERT INTO player_fixture_stats (
                            fixture_id, player_id, season_id, gameweek, player_team_id,
                            opponent_team_id, was_home, kickoff_time, minutes, total_points,
                            expected_goals, expected_assists, expected_goals_conceded, value,
                            starts, defensive_contribution)
                        VALUES (?, ?, ?, ?, 9851, 9852, true, ?, 90, 4,
                                0.10, 0.10, 1.00, 50, 1, 20)
                        """, fixture, id, PREVIOUS_SEASON_ID, index + 1,
                        Timestamp.from(kickoff));
            }
        }
    }

    private static SeasonContext context() {
        return new SeasonContext(SEASON_ID, SEASON, true, SEASON,
                LocalDate.of(2026, 8, 1));
    }

    private static RankingMetrics.Candidate candidate(RankingMetrics.Snapshot snapshot, int id) {
        return snapshot.candidates().stream().filter(row -> row.capture().elementId() == id)
                .findFirst().orElseThrow();
    }

    private void seedSchedule(String season, Instant capturedAt) {
        RankingSeed.seedSchedule(jdbc, gameweekTable, fixtureTable, season, capturedAt, FIRST_DEADLINE);
    }

    private void seedDaily(int elementId, String name, int team, int position, String status,
                           LocalDate date, Instant capturedAt) {
        jdbc.update("""
                INSERT INTO %s (price_date, element_id, season, web_name, full_name,
                    team_id, team_short, team_name, element_type, now_cost,
                    selected_by_percent, status, news, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, 'K31', 'KAR Club', ?, 50, 1.0, ?, NULL, ?)
                """.formatted(dailyTable), Date.valueOf(date), elementId, SEASON, name, name,
                team, position, status, Timestamp.from(capturedAt));
    }

    private List<Integer> finishFixtures(int club, int count) {
        return finishFixturesAfter(club, count, Instant.parse("2026-08-01T12:00:00Z"));
    }

    private List<Integer> finishFixturesAfter(int club, int count, Instant firstKickoff) {
        List<Integer> ids = fixtureIdsFor(club).subList(0, count);
        for (int i = 0; i < ids.size(); i++) {
            finishFixture(ids.get(i), firstKickoff.plus(i, ChronoUnit.DAYS));
        }
        return new ArrayList<>(ids);
    }

    private List<Integer> fixtureIdsFor(int club) {
        return RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE).stream()
                .filter(row -> row.homeTeamId() == club || row.awayTeamId() == club)
                .map(ScheduleSource.FixtureRow::id).toList();
    }

    private List<Integer> finishFixturesAvoiding(int club, int count, Set<Integer> avoided,
                                                  int dayOffset) {
        List<Integer> ids = fixtureIdsFor(club).stream()
                .filter(id -> avoided.stream().noneMatch(other -> other != club
                        && fixtureInvolves(id, other)))
                .limit(count).toList();
        assertThat(ids).hasSize(count);
        for (int i = 0; i < ids.size(); i++) {
            finishFixture(ids.get(i), Instant.parse("2026-06-01T12:00:00Z")
                    .plus(dayOffset + i, ChronoUnit.DAYS));
        }
        return ids;
    }

    private void finishFixture(int fixtureId, Instant kickoff) {
        jdbc.update("UPDATE " + fixtureTable
                        + " SET kickoff_time = ?, started = true, finished = true WHERE season = ? AND id = ?",
                Timestamp.from(kickoff), SEASON, fixtureId);
    }

    private boolean fixtureInvolves(int fixtureId, int club) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + fixtureTable
                        + " WHERE season = ? AND id = ? AND (team_h = ? OR team_a = ?)",
                Integer.class, SEASON, fixtureId, club, club);
        return count != null && count > 0;
    }

    private void seedPfs(int playerId, int fixtureId, int minutes, int actualClub,
                         int stampedTeam, Integer opponentOverride) {
        var fixture = jdbc.queryForMap("SELECT gameweek, team_h, team_a, kickoff_time FROM "
                + fixtureTable + " WHERE season = ? AND id = ?", SEASON, fixtureId);
        int home = ((Number) fixture.get("team_h")).intValue();
        int away = ((Number) fixture.get("team_a")).intValue();
        boolean wasHome = home == actualClub;
        if (!wasHome && away != actualClub) {
            throw new IllegalArgumentException("actual club is not in fixture");
        }
        int actualOpponent = wasHome ? away : home;
        int opponent = opponentOverride == null ? actualOpponent : opponentOverride;
        jdbc.update("""
                INSERT INTO player_fixture_stats (
                    fixture_id, player_id, season_id, gameweek, player_team_id,
                    opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    expected_goals, expected_assists, expected_goals_conceded, value, starts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 4, 0.20, 0.10, 1.00, 50, 1)
                """, fixtureId, playerId, SEASON_ID, ((Number) fixture.get("gameweek")).intValue(),
                stampedTeam, opponent, wasHome, fixture.get("kickoff_time"), minutes);
    }

    private void seedPfsStats(int playerId, int fixtureId, int minutes, int actualClub,
                              int points, double xg) {
        seedPfs(playerId, fixtureId, minutes, actualClub, actualClub, null);
        jdbc.update("UPDATE player_fixture_stats SET total_points = ?, expected_goals = ?"
                        + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                points, xg, SEASON_ID, playerId, fixtureId);
    }

    private void seedPreviousRegular(int playerId, String name, int position) {
        int club = 9791;
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9791, ?, 9791, 'Previous Club', 'PRV', 3),
                    (9792, ?, 9792, 'Previous Opponent A', 'PRA', 3),
                    (9793, ?, 9793, 'Previous Opponent B', 'PRB', 3),
                    (9794, ?, 9794, 'Previous Opponent C', 'PRC', 3)
                """, PREVIOUS_SEASON_ID, PREVIOUS_SEASON_ID,
                PREVIOUS_SEASON_ID, PREVIOUS_SEASON_ID);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, web_name, element_type, now_cost)
                VALUES (?, ?, ?, ?, ?, 50)
                """, playerId, PREVIOUS_SEASON_ID, club, name, position);
        for (int i = 0; i < 3; i++) {
            int fixtureId = 979_100 + i;
            int opponent = 9792 + i;
            Instant kickoff = Instant.parse("2025-08-01T12:00:00Z").plus(i, ChronoUnit.DAYS);
            jdbc.update("""
                    INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                         kickoff_time, started, finished)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, true)
                    """, fixtureId, PREVIOUS_SEASON_ID, i + 1, fixtureId,
                    club, opponent, Timestamp.from(kickoff));
            jdbc.update("""
                    INSERT INTO player_fixture_stats (
                        fixture_id, player_id, season_id, gameweek, player_team_id,
                        opponent_team_id, was_home, kickoff_time, minutes, total_points,
                        expected_goals, expected_assists, expected_goals_conceded, value, starts)
                    VALUES (?, ?, ?, ?, ?, ?, true, ?, 90, 20, 2.00, 0.50, 0.50, 50, 1)
                    """, fixtureId, playerId, PREVIOUS_SEASON_ID, i + 1, club, opponent,
                    Timestamp.from(kickoff));
        }
    }

    private void seedUnmappablePfs(int playerId, int fixtureId, int stampedTeam) {
        jdbc.update("""
                INSERT INTO player_fixture_stats (
                    fixture_id, player_id, season_id, gameweek, player_team_id,
                    opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    expected_goals, expected_assists, expected_goals_conceded, value, starts)
                VALUES (?, ?, ?, 1, ?, ?, true, TIMESTAMPTZ '2026-08-01 12:00:00+00',
                        90, 4, 0.20, 0.10, 1.00, 50, 1)
                """, fixtureId, playerId, SEASON_ID, stampedTeam, RankingSeed.team(2));
    }
}
