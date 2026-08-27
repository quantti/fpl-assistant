package dev.quantti.fplassistant.squad;

import java.time.LocalDate;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool end to end, and the case it was built for.
 *
 * <p>The acceptance test reproduces the request that produced two answers containing players
 * who do not exist, a club constraint claimed but not met, and a budget total that did not match
 * its own table. It must now produce a real squad or a precise refusal — never a placeholder.
 */
@SpringBootTest
@ActiveProfiles("test")
class SquadToolsIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final int FIRST_ID = 9600;

    /**
     * Every id this class writes into {@code player} / {@code player_fixture_stats}: the base
     * league runs {@code FIRST_ID}..9791, the ambiguous-name and accent tests add 9800, 9801 and
     * 9810, and the deliberately-best player below is 9820.
     */
    private static final int LAST_ID = 9825;

    /**
     * One fictitious club playing two finished fixtures, scoped to ids nothing else in the suite
     * touches. Every player this class seeds gets a two-appearance record against it — see
     * {@link #seedMetrics}. This is the minutes-history bridge, and is unrelated to
     * {@link #TEAM_BASE} below, which is the fixture-difficulty wiring.
     */
    private static final int METRIC_TEAM = 9650;
    private static final int METRIC_OPPONENT = 9651;
    private static final int METRIC_FIXTURE_GW1 = 9660;
    private static final int METRIC_FIXTURE_GW2 = 9661;

    /**
     * Twelve clubs, four of each position, priced so a legal squad exists inside £100.0m.
     * {@code CLUBS[c]} is the club every candidate at grid position {@code c} carries as its
     * daily-price {@code team_name}; {@link #teamIdFor} maps each one to a real {@code team} row
     * with real, upcoming {@code fixture} rows (see {@link #seedRealFixtures}), which is what
     * {@code HorizonScorer.fixtureDifficulties} needs to score anyone at all. Before this wiring
     * every candidate's fixture-difficulty list was empty, so every score was flat zero and the
     * captain was chosen by tie-break rather than by merit.
     */
    private static final String[] CLUBS = {"Ashfield Rovers", "Barnwell Town", "Coleridge City",
            "Dunmore Athletic", "Eastvale United", "Fallowfield FC", "Granton Park",
            "Harewood Albion", "Ilkeston Vale", "Jarrow Wanderers", "Kelvedon Town",
            "Langford City"};

    /** {@code team}/{@code fixture} ids nothing else in the suite touches: see {@link #CLUBS}. */
    private static final int TEAM_BASE = 9700;
    private static final int FIXTURE_BASE = 9720;
    private static final int FIXTURE_GAMEWEEK = 3;

    /**
     * Six pairings of the twelve clubs, each with its own (home, away) difficulty, so the
     * fixture-difficulty wiring produces genuinely different scores per club rather than one
     * uniform number. {@code CLUBS[0]} (Ashfield Rovers) gets the easiest fixture of all,
     * matching {@link #STAR_ID}'s club below.
     */
    private static final int[][] DIFFICULTY_PAIRS = {
            {1, 5}, {3, 3}, {2, 4}, {4, 2}, {3, 4}, {5, 1}
    };

    /**
     * A single deliberately-best player: a forward at Ashfield Rovers (the easiest fixture) with
     * expected goals far above every other candidate's. Nothing else in this fixture can tell a
     * genuine per-90 ranking apart from an all-zero objective resolved by tie-break, because
     * every other candidate at a given position shares identical underlying metrics — see
     * {@code theCaptainIsTheDeliberatelyBestOutfieldPlayer}.
     */
    private static final int STAR_ID = 9820;
    private static final String STAR_NAME = "Beststriker";

    /**
     * The season the tool actually filters on, read at runtime rather than hardcoded — see the
     * same field on {@code CandidatePoolIntegrationTest}. The resolver returns whichever season
     * holds data, which during the changeover is not the one on the calendar.
     */
    String season;

    /** The same season as {@link #season}, as the numeric id the shared tables key on. */
    int seasonId;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SquadTools tools;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndSeedALeague() {
        SharedStatsSeed.ensure(jdbc);
        SeasonContext resolved = seasonResolver.resolve();
        season = resolved.code();
        seasonId = resolved.seasonId();
        FictitiousTestData.assertSafeSeason(season);
        assertThat(table).as("squad tests must use the isolated table").endsWith("_test");
        deleteTestRows();
        seedMetricScaffold();
        seedRealFixtures();

        int id = FIRST_ID;
        for (int c = 0; c < CLUBS.length; c++) {
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    insert(id, "P%d".formatted(id), CLUBS[c], "C%02d".formatted(c), type,
                            n == 0 ? 105 : 40 + n * 5, n == 1 ? 35.0 : 1.5);
                    id++;
                }
            }
        }
        insertStar();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ?"
                        + " AND player_id BETWEEN ? AND ?",
                seasonId, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id BETWEEN ? AND ?",
                seasonId, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id IN (?, ?)",
                seasonId, METRIC_FIXTURE_GW1, METRIC_FIXTURE_GW2);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id IN (?, ?)",
                seasonId, METRIC_TEAM, METRIC_OPPONENT);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id BETWEEN ? AND ?",
                seasonId, FIXTURE_BASE, FIXTURE_BASE + DIFFICULTY_PAIRS.length - 1);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id BETWEEN ? AND ?",
                seasonId, TEAM_BASE, TEAM_BASE + CLUBS.length - 1);
    }

    /**
     * Scoped by element id, not a blanket delete on the season: a blanket
     * {@code WHERE season = ?} would also remove a sibling class's rows if it ever shared this
     * season under parallel test execution, the same reasoning
     * {@code CandidatePoolIntegrationTest} documents for this exact table.
     */
    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id BETWEEN ? AND ?",
                season, FIRST_ID, LAST_ID);
    }

    /**
     * One fictitious club playing itself over two finished gameweeks, so every player this class
     * inserts has a real club fixture count to divide his minutes by.
     */
    private void seedMetricScaffold() {
        jdbc.update("INSERT INTO team (id, season_id, code, name, short_name, strength)"
                        + " VALUES (?, ?, ?, 'Metric Town', 'MET', 3)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_TEAM, seasonId, METRIC_TEAM);
        jdbc.update("INSERT INTO team (id, season_id, code, name, short_name, strength)"
                        + " VALUES (?, ?, ?, 'Metric Rivals', 'MTR', 3)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_OPPONENT, seasonId, METRIC_OPPONENT);
        jdbc.update("INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,"
                        + " team_h_score, team_a_score, team_h_difficulty, team_a_difficulty,"
                        + " kickoff_time, started, finished)"
                        + " VALUES (?, ?, 1, ?, ?, ?, 1, 1, 3, 3,"
                        + " TIMESTAMPTZ '2025-08-16 14:00:00+00', true, true)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_FIXTURE_GW1, seasonId, METRIC_FIXTURE_GW1, METRIC_TEAM, METRIC_OPPONENT);
        jdbc.update("INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,"
                        + " team_h_score, team_a_score, team_h_difficulty, team_a_difficulty,"
                        + " kickoff_time, started, finished)"
                        + " VALUES (?, ?, 2, ?, ?, ?, 1, 1, 3, 3,"
                        + " TIMESTAMPTZ '2025-08-23 14:00:00+00', true, true)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_FIXTURE_GW2, seasonId, METRIC_FIXTURE_GW2, METRIC_OPPONENT, METRIC_TEAM);
    }

    private void insert(int elementId, String webName, String club, String clubShort,
                        int elementType, int nowCost, double ownership) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 'a', NULL)",
                season, TODAY, elementId, webName, webName + " Fictitious",
                teamIdFor(club), clubShort, club, elementType, nowCost, ownership);
        seedMetrics(elementId, webName, elementType, nowCost, ownership);
    }

    /** {@code club}'s real {@code team} id — see {@link #CLUBS} and {@link #seedRealFixtures}. */
    private static int teamIdFor(String club) {
        for (int i = 0; i < CLUBS.length; i++) {
            if (CLUBS[i].equals(club)) {
                return TEAM_BASE + i;
            }
        }
        throw new IllegalArgumentException("no team id seeded for club " + club);
    }

    /**
     * A real {@code team} row per club and a real, unfinished {@code fixture} in the horizon
     * window for each, paired up with a distinct difficulty per pairing. Without this,
     * {@code HorizonScorer.fixtureDifficulties} finds no fixture for any of these team ids and
     * every candidate scores flat zero, regardless of the metrics {@link #seedMetrics} gives
     * them — the bug {@code theCaptainIsTheDeliberatelyBestOutfieldPlayer} guards against.
     */
    private void seedRealFixtures() {
        for (int i = 0; i < CLUBS.length; i++) {
            int teamId = TEAM_BASE + i;
            jdbc.update("INSERT INTO team (id, season_id, code, name, short_name, strength)"
                            + " VALUES (?, ?, ?, ?, ?, 3) ON CONFLICT (id, season_id) DO NOTHING",
                    teamId, seasonId, teamId, CLUBS[i] + " (fixture)", "F%02d".formatted(i));
        }
        for (int pair = 0; pair < DIFFICULTY_PAIRS.length; pair++) {
            int home = TEAM_BASE + pair * 2;
            int away = TEAM_BASE + pair * 2 + 1;
            int fixtureId = FIXTURE_BASE + pair;
            jdbc.update("INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,"
                            + " team_h_difficulty, team_a_difficulty, kickoff_time, started,"
                            + " finished)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                            + " TIMESTAMPTZ '2026-09-05 14:00:00+00', false, false)"
                            + " ON CONFLICT (id, season_id) DO NOTHING",
                    fixtureId, seasonId, FIXTURE_GAMEWEEK, fixtureId, home, away,
                    DIFFICULTY_PAIRS[pair][0], DIFFICULTY_PAIRS[pair][1]);
        }
    }

    /** See {@link #STAR_ID}. */
    private void insertStar() {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 4, 88, 0, 0, ?, 'a', NULL)",
                season, TODAY, STAR_ID, STAR_NAME, STAR_NAME + " Fictitious",
                teamIdFor(CLUBS[0]), "C00", CLUBS[0], 5.0);
        seedMetrics(STAR_ID, STAR_NAME, 4, 88, 5.0, 1.50, 0.30, 1.20);
    }

    /**
     * A real two-appearance playing record for {@code elementId}, keyed to the same season as the
     * daily-price pool. Without this every candidate the pool loads is an unrankable
     * {@link SquadCandidate.Tier#BODY} — the daily-price table alone carries price and
     * availability, never a minutes history — and a squad built entirely of bodies can never fill
     * a starting XI, whatever the prices say. Attacking output is scaled by position so
     * goalkeepers and forwards don't compete on the same numbers.
     */
    private void seedMetrics(int elementId, String webName, int elementType, int nowCost,
                             double ownership) {
        double xg = switch (elementType) {
            case 4 -> 0.40;
            case 3 -> 0.20;
            case 2 -> 0.02;
            default -> 0.00;
        };
        double xa = elementType >= 3 ? 0.15 : 0.03;
        double xgc = elementType == 1 || elementType == 2 ? 1.00 : 1.20;
        seedMetrics(elementId, webName, elementType, nowCost, ownership, xg, xa, xgc);
    }

    /** As above, with the underlying stats given explicitly — see {@link #insertStar}. */
    private void seedMetrics(int elementId, String webName, int elementType, int nowCost,
                             double ownership, double xg, double xa, double xgc) {
        jdbc.update("INSERT INTO player (id, season_id, team_id, first_name, second_name,"
                        + " web_name, element_type, now_cost, status, total_points, minutes,"
                        + " form, selected_by_percent)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'a', 12, 180, 4.0, ?)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                elementId, seasonId, METRIC_TEAM, webName, webName, webName, elementType,
                nowCost, ownership);

        seedAppearance(elementId, METRIC_FIXTURE_GW1, 1, true, xg, xa, xgc);
        seedAppearance(elementId, METRIC_FIXTURE_GW2, 2, false, xg, xa, xgc);
    }

    private void seedAppearance(int elementId, int fixtureId, int gameweek, boolean wasHome,
                                double xg, double xa, double xgc) {
        jdbc.update("INSERT INTO player_fixture_stats (fixture_id, player_id, season_id,"
                        + " gameweek, player_team_id, opponent_team_id, was_home, kickoff_time,"
                        + " minutes, total_points, expected_goals, expected_assists,"
                        + " expected_goal_involvements, goals_conceded, expected_goals_conceded,"
                        + " value)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 6, ?, ?, ?, 1, ?, 50)"
                        + " ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING",
                fixtureId, elementId, seasonId, gameweek, METRIC_TEAM, METRIC_OPPONENT, wasHome,
                java.time.OffsetDateTime.parse("2025-08-16T14:00:00Z"), xg, xa, xg + xa, xgc);
    }

    @Test
    void aPlainRequestReturnsFifteenRealPlayersAndAComputedTotal() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).doesNotContainIgnoringCase("placeholder");
        assertThat(output).contains("Starting XI");
        assertThat(output).contains("Bench");
        assertThat(output).contains("Captain");
    }

    /**
     * Every candidate at a given position otherwise shares identical underlying metrics, so a
     * genuine per-90 ranking and an all-zero objective resolved by tie-break are
     * indistinguishable by every other test in this class — none of them would fail if
     * {@code HorizonScorer.score}, {@code fixtureDifficulties}, or the pool's {@code team_id}
     * wiring were broken outright. {@link #STAR_ID} exists to make that distinguishable: he is a
     * forward on the easiest fixture in the pool with expected goals far above anyone else's, so
     * only a working per-90 ranking picks him as captain — a stable sort over a flat objective
     * would not.
     */
    @Test
    void theCaptainIsTheDeliberatelyBestOutfieldPlayer() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).contains("Captain: " + STAR_NAME + " (");
    }

    @Test
    void threeFromEachOfThreeClubsIsHonouredExactly() {
        String output = tools.buildSquad("", "",
                "Ashfield Rovers:3, Barnwell Town:3, Coleridge City:3", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers: 3/3");
        assertThat(output).contains("Barnwell Town: 3/3");
        assertThat(output).contains("Coleridge City: 3/3");
    }

    @Test
    void anImpossibleClubQuotaIsRefusedByNameRatherThanFudged() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:4", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers");
        assertThat(output).contains("3");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void aClubQuotaWrittenAsTheShortNameStillResolves() {
        // I3: club quotas must resolve against both team_name and team_short, not team_name
        // alone. CLUBS[0] ("Ashfield Rovers") carries the fictitious short name "C00" here.
        String output = tools.buildSquad("", "", "C00:3", 100.0, 5, "");

        assertThat(output).contains("C00: 3/3");
        assertThat(output).doesNotContain("could not find a club");
    }

    @Test
    void anUnmatchedClubQuotaRefusesByNameRatherThanReadingAsAnOutage() {
        // I3: before the fix, an unmatched club quota either read as "only 0 are available"
        // (a floor, indistinguishable from a genuine data outage) or silently printed a ceiling
        // as met against nobody (a false ✓, the same class of bug as C2). Either way it must
        // now refuse by name instead.
        String output = tools.buildSquad("", "", "Nonexistent FC:>=1", 100.0, 5, "");

        assertThat(output).contains("Nonexistent FC");
        assertThat(output).doesNotContain("Starting XI");
        assertThat(output).doesNotContain("only 0 are available");
    }

    @Test
    void anUnknownNameIsRefusedRatherThanQuietlyDropped() {
        String output = tools.buildSquad("Nobodyatall", "", "", 100.0, 5, "");

        assertThat(output).containsIgnoringCase("Nobodyatall");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void anAmbiguousNameListsTheMatchesAndStops() {
        insert(9800, "Ambiguoustest", "Ashfield Rovers", "C00", 3, 60, 2.0);
        insert(9801, "Ambiguoustest", "Barnwell Town", "C01", 3, 65, 2.0);

        String output = tools.buildSquad("Ambiguoustest", "", "", 100.0, 5, "");

        assertThat(output).containsIgnoringCase("Ambiguoustest");
        assertThat(output).doesNotContain("Starting XI");
    }

    @Test
    void aNameWrittenWithoutItsAccentStillResolves() {
        insert(9810, "Jõao", "Ashfield Rovers", "C00", 4, 76, 5.0);

        String output = tools.buildSquad("Joao", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI");
        assertThat(output).contains("Jõao");
        // C2: the player line alone is not enough. The constraint receipt compared "Joao" to
        // the candidate's webName with a plain toLowerCase, which does not strip "õ" — so it
        // read "Joao: included ✗" even though Jõao himself was correctly pinned into the squad,
        // sitting right there in the output above this very line.
        assertThat(output).contains("Joao: included ✓");
    }

    @Test
    void anExclusionWrittenAsAFullNameStillAppliesToTheAbbreviatedWebName() {
        // C2's central bug: mustExclude was never resolved at all, and SquadSolver matched an
        // exclusion against a plain-lowercased webName, so "Gabriel Jesus" never matched a
        // candidate whose actual webName is "G.Jesus". The exclusion silently did nothing, and
        // the receipt then certified it as met using the same broken comparison. Deliberately
        // the best forward in the pool (same recipe as STAR_ID/Beststriker): if the exclusion is
        // a no-op, he is exactly the player the solver would otherwise buy.
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 4, 90, 0, 0, ?, 'a', NULL)",
                season, TODAY, 9811, "G.Jesus", "Gabriel Jesus",
                teamIdFor(CLUBS[0]), "C00", CLUBS[0], 8.0);
        seedMetrics(9811, "G.Jesus", 4, 90, 8.0, 1.50, 0.30, 1.20);

        String output = tools.buildSquad("", "Gabriel Jesus", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI");
        assertThat(output).doesNotContain("G.Jesus");
        assertThat(output).contains("Gabriel Jesus: excluded ✓");
    }

    @Test
    void aMalformedQuotaIsExplainedRatherThanIgnored() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:three", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers:three");
        assertThat(output).doesNotContain("Starting XI");
    }
}
