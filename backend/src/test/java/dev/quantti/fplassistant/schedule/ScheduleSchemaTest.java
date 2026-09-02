package dev.quantti.fplassistant.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @Test
    void bothTablesExistWithRowLevelSecurityOn() {
        assertThat(gameweekTable).endsWith("_test");
        assertThat(fixtureTable).endsWith("_test");
        for (String table : new String[] {gameweekTable, fixtureTable}) {
            Boolean rls = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass(?)", Boolean.class, table);
            assertThat(rls).as(table).isTrue();
        }
    }
}
