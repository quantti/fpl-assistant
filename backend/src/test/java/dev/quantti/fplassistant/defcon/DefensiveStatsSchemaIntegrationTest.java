package dev.quantti.fplassistant.defcon;

import java.util.List;

import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The columns this project adds to a table tapas owns.
 *
 * <p>Asserted against the schema rather than assumed, because the DDL runs once at boot and a
 * silent failure would only show up as every defensive number being null months later.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefensiveStatsSchemaIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void everyDefensiveContributionColumnExists() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = ? AND column_name = ANY (?)
                """, String.class, DefensiveStatsSchema.TABLE,
                DefensiveStatsSchema.COLUMNS);

        assertThat(columns).containsExactlyInAnyOrder(DefensiveStatsSchema.COLUMNS);
    }

    /**
     * Nullable, and no default. Zero is a real reading — a forward who tackled nobody — so a
     * DEFAULT 0 would make "nothing happened" indistinguishable from "never fetched", which is
     * the mistake that made the first set-piece capture report the whole league as changed.
     */
    @Test
    void theColumnsAreNullableWithNoDefaultSoUnfetchedIsNotZero() {
        List<java.util.Map<String, Object>> meta = jdbc.queryForList("""
                SELECT column_name, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = ? AND column_name = ANY (?)
                """, DefensiveStatsSchema.TABLE, DefensiveStatsSchema.COLUMNS);

        assertThat(meta).hasSize(DefensiveStatsSchema.COLUMNS.length);
        assertThat(meta).allSatisfy(row -> {
            assertThat(row.get("is_nullable")).isEqualTo("YES");
            assertThat(row.get("column_default")).isNull();
        });
    }

    /** The write path the sync uses: UPDATE on an existing tapas row, never INSERT. */
    @Test
    void theSyncsUpdateStatementReachesASeededRow() {
        int updated = jdbc.update("""
                UPDATE player_fixture_stats
                   SET defensive_contribution = ?, clearances_blocks_interceptions = ?,
                       recoveries = ?, tackles = ?
                 WHERE fixture_id = ? AND player_id = ? AND season_id = ?
                """, 2, 6, 9, 4, 9101, 9201, TapasSeed.POPULATED_SEASON_ID);

        assertThat(updated).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT defensive_contribution FROM player_fixture_stats
                WHERE fixture_id = 9101 AND player_id = 9201 AND season_id = ?
                """, Integer.class, TapasSeed.POPULATED_SEASON_ID)).isEqualTo(2);
    }
}
