package fi.karivanttinen.fplassistant.defcon;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the vaastav mapping. The CSV text is shaped like the real
 * {@code 2025-26/gws/merged_gw.csv}: parsed by header name, with the columns in a different order
 * from the real file on purpose — the real one has changed order between seasons, and reading by
 * position would put recoveries in the tackles column without anything failing.
 */
class DefensiveStatsBackfillServiceTest {

    private static final String HEADER =
            "name,element,fixture,GW,minutes,tackles,recoveries,"
                    + "clearances_blocks_interceptions,defensive_contribution\n";

    @Test
    void everyRowBecomesAnUpdateGuardedOnMinutes() {
        String csv = HEADER
                + "Reinildo,541,5,1,90,2,3,6,8\n"
                + "Sarr,593,6,1,90,2,4,4,10\n";

        List<Object[]> updates =
                DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26");

        assertThat(updates).hasSize(2);
        // defcon, cbi, recoveries, tackles, fixture, player, season, minutes
        assertThat(updates.getFirst()).containsExactly(8, 6, 3, 2, 5, 541, 3, 90);
        assertThat(updates.get(1)).containsExactly(10, 4, 4, 2, 6, 593, 3, 90);
    }

    /**
     * The guard that makes a bulk write to a shared table safe: minutes travel into the WHERE
     * clause, so a row is touched only when both sources agree on what happened in it.
     */
    @Test
    void minutesAreCarriedAsTheLastArgumentSoTheyCanGuardTheUpdate() {
        String csv = HEADER + "Winterburn,96,1,1,6,0,0,0,0\n";

        Object[] update = DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26").getFirst();

        assertThat(update[update.length - 1]).isEqualTo(6);
    }

    /** A season played before FPL introduced the rule has no such column, and that is not an error. */
    @Test
    void aSeasonWithoutTheColumnYieldsNothingRatherThanThrowing() {
        String csv = "name,element,fixture,GW,minutes\nOld,1,1,1,90\n";

        assertThat(DefensiveStatsBackfillService.toUpdates(csv, 3, "2019-20")).isEmpty();
    }

    /** vaastav writes some integer columns as "3.0"; a count has no fraction to lose. */
    @Test
    void integersWrittenAsDecimalsAreRead() {
        String csv = HEADER + "Decimal,7,2,1,90.0,1.0,2.0,3.0,4.0\n";

        assertThat(DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26").getFirst())
                .containsExactly(4, 3, 2, 1, 2, 7, 3, 90);
    }

    /** A row with no key cannot be written anywhere, so it is skipped rather than guessed at. */
    @Test
    void rowsMissingAKeyColumnAreSkipped() {
        String csv = HEADER
                + "NoFixture,541,,1,90,2,3,6,8\n"
                + "NoMinutes,542,5,1,,2,3,6,8\n"
                + "Good,543,5,1,90,2,3,6,8\n";

        List<Object[]> updates =
                DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26");

        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst()[5]).isEqualTo(543);
    }

    /**
     * A row with fewer fields than the header. record.get would throw IllegalArgumentException
     * past the IOException catch and abort the whole backfill; isSet turns it into a null.
     */
    @Test
    void aShortRowDoesNotAbortTheRun() {
        String csv = HEADER
                + "Ragged,541,5,1,90,2\n"
                + "Good,543,5,1,90,2,3,6,8\n";

        List<Object[]> updates =
                DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26");

        assertThat(updates).hasSize(2);
        // The ragged row reaches only as far as tackles; the columns beyond it stay null rather
        // than throwing, and the row after it is unaffected.
        assertThat(updates.getFirst()).containsExactly(null, null, null, 2, 5, 541, 3, 90);
        assertThat(updates.get(1)).containsExactly(8, 6, 3, 2, 5, 543, 3, 90);
    }

    /**
     * vaastav's 2025-26 file lists ten player-fixture pairs twice, with identical values. Left
     * in, they make the run report more rows updated than the table holds — which reads as a
     * write having gone somewhere it should not have.
     */
    @Test
    void aPlayerFixturePairListedTwiceBecomesOneUpdate() {
        String csv = HEADER
                + "Kroupi,100,29,4,9,0,1,0,1\n"
                + "Kroupi,100,29,4,9,0,1,0,1\n";

        assertThat(DefensiveStatsBackfillService.toUpdates(csv, 3, "2025-26")).hasSize(1);
    }

    @Test
    void anEmptyFileYieldsNothing() {
        assertThat(DefensiveStatsBackfillService.toUpdates(HEADER, 3, "2025-26")).isEmpty();
    }
}
