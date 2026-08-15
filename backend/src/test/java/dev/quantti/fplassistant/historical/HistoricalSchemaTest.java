package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HistoricalSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.historical.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("historical schema tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = 'schema-test'");
    }

    @Test
    void tableExistsWithExpectedColumns() {
        Integer columnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ?",
                Integer.class, tableName);
        assertThat(columnCount).isGreaterThanOrEqualTo(13);
    }

    // Codes below the highest real FPL team code (127) are used everywhere else in this
    // package, since other tests in this table populate real historical seasons — a
    // fictitious pair avoids colliding with a real fixture on the shared natural key, which
    // does not distinguish by `source`.
    private static final int FICTITIOUS_HOME = 9001;
    private static final int FICTITIOUS_AWAY = 9002;

    @Test
    void naturalKeyRejectsADuplicateSeasonAndTeamPair() {
        insertMatch(FICTITIOUS_HOME, FICTITIOUS_AWAY);

        assertThatThrownBy(() -> insertMatch(FICTITIOUS_HOME, FICTITIOUS_AWAY))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameTeamsInDifferentSeasonsAreBothAllowed() {
        insertMatch(FICTITIOUS_HOME, FICTITIOUS_AWAY);

        jdbc.update("""
                INSERT INTO %s (season, home_team_code, away_team_code, home_team_name, away_team_name,
                                 home_goals, away_goals, source)
                VALUES ('2099-00', ?, ?, 'Test Home FC', 'Test Away FC', 1, 1, 'schema-test')
                """.formatted(tableName), FICTITIOUS_HOME, FICTITIOUS_AWAY);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = 'schema-test'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    private void insertMatch(int homeCode, int awayCode) {
        jdbc.update("""
                INSERT INTO %s (season, home_team_code, away_team_code, home_team_name, away_team_name,
                                 home_goals, away_goals, kickoff_time, source)
                VALUES ('2098-99', ?, ?, 'Test Home FC', 'Test Away FC', 2, 1, ?, 'schema-test')
                """.formatted(tableName), homeCode, awayCode, Timestamp.from(java.time.Instant.now()));
    }
}
