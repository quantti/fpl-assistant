package dev.quantti.fplassistant.tools;

import java.time.LocalDate;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Set-piece duty read from the daily capture.
 *
 * <p>The test that carries the feature is {@link #aDutyThatChangedIsReportedWithTheDayItChanged}:
 * the reason this is a tool rather than a written note is that the order is revised — most of
 * all by the first weekend of a season, when a penalty is actually taken. A tool that only
 * printed today's order would be a document with extra steps.
 */
@SpringBootTest
@ActiveProfiles("test")
class SetPieceToolsIntegrationTest {

    private static final String TEST_SEASON = "test-sp"; // season columns are VARCHAR(10)
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private static final int TAKER = 9601;
    private static final int DEPUTY = 9602;
    private static final int CORNERS = 9603;
    private static final int NOBODY = 9604;

    @BeforeAll
    static void guardTheFixture() {
        FictitiousTestData.assertSafeSeason(TEST_SEASON);
        FictitiousTestData.assertSafeCode(TAKER);
        FictitiousTestData.assertSafeCode(NOBODY);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SetPieceTools tools;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndResetFixture() {
        assertThat(table).as("set-piece tests must use the isolated table").endsWith("_test");
        deleteTestRows();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", TEST_SEASON);
    }

    private void insert(int elementId, String webName, LocalDate date, Integer penalties,
                        Integer directFk, Integer corners) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, status,"
                        + " penalties_order, direct_fk_order, corners_order)"
                        + " VALUES (?, ?, ?, ?, ?, 'TST', 'Testville United', 3, 100, 0, 0, 'a',"
                        + " ?, ?, ?)",
                TEST_SEASON, date, elementId, webName, webName + " Fictitious",
                penalties, directFk, corners);
    }

    @Test
    void theOrderIsListedByDutyWithTheCaptureDateOnIt() {
        insert(TAKER, "Spotkick", TODAY, 1, 2, null);
        insert(DEPUTY, "Secondtest", TODAY, 2, null, null);
        insert(CORNERS, "Cornertest", TODAY, null, 1, 1);

        String result = tools.getSetPieceTakers("Testville");

        assertThat(result)
                .contains("Testville United (TST)")
                .contains("Penalties: 1 Spotkick, 2 Secondtest")
                .contains("Direct free kicks: 1 Cornertest, 2 Spotkick")
                .contains("Corners and indirect free kicks: 1 Cornertest")
                .contains(TODAY.toString());
    }

    /** The whole reason this is not a document: duty changes, and the date is the useful part. */
    @Test
    void aDutyThatChangedIsReportedWithTheDayItChanged() {
        insert(TAKER, "Spotkick", YESTERDAY, 2, null, null);
        insert(DEPUTY, "Secondtest", YESTERDAY, 1, null, null);
        insert(TAKER, "Spotkick", TODAY, 1, null, null);
        insert(DEPUTY, "Secondtest", TODAY, 2, null, null);

        String result = tools.getSetPieceTakers("TST");

        assertThat(result)
                .contains("Penalties: 1 Spotkick, 2 Secondtest")
                .contains("Changes in the last 60 days")
                .contains(TODAY + ": Spotkick penalties #2 -> #1")
                .contains(TODAY + ": Secondtest penalties #1 -> #2");
    }

    @Test
    void aSettledOrderSaysSoRatherThanListingNothing() {
        insert(TAKER, "Spotkick", YESTERDAY, 1, null, null);
        insert(TAKER, "Spotkick", TODAY, 1, null, null);

        assertThat(tools.getSetPieceTakers("Testville"))
                .contains("No change in the last 60 days");
    }

    /**
     * The day the columns were added, every earlier capture holds three nulls — it predates the
     * data, it does not record an absence of duty. Comparing against it would announce that
     * every taker in the league changed duty that morning, which is what production did on the
     * first run and what this test stops.
     */
    @Test
    void capturesFromBeforeSetPieceDataExistedAreNotReportedAsChanges() {
        insert(TAKER, "Spotkick", YESTERDAY, null, null, null);
        insert(TAKER, "Spotkick", TODAY, 1, null, null);

        assertThat(tools.getSetPieceTakers("Testville"))
                .contains("Penalties: 1 Spotkick")
                .contains("No change in the last 60 days")
                .doesNotContain("penalties none -> #1");
    }

    /** A player who genuinely loses a duty still shows up as a change. */
    @Test
    void losingADutyIsStillAChange() {
        insert(TAKER, "Spotkick", YESTERDAY, 1, null, null);
        insert(TAKER, "Spotkick", TODAY, null, null, 2);

        assertThat(tools.getSetPieceTakers("Testville"))
                .contains(TODAY + ": Spotkick penalties #1 -> none");
    }

    /**
     * The dangerous failure for this tool: a club nobody matched must not read as a club with no
     * takers, which would tell a user their penalty taker has lost the duty.
     */
    @Test
    void anUnmatchedClubSaysSoInsteadOfAnsweringEmpty() {
        insert(TAKER, "Spotkick", TODAY, 1, null, null);

        assertThat(tools.getSetPieceTakers("Nonexistent Athletic"))
                .contains("No club matches")
                .doesNotContain("Penalties:");
    }

    @Test
    void aClubWithNoPublishedOrderIsAnAbsenceOfDataNotOfTakers() {
        insert(NOBODY, "Nodutytest", TODAY, null, null, null);

        assertThat(tools.getSetPieceTakers("Testville"))
                .contains("publishes no set-piece order")
                .contains("absence of data");
    }
}
