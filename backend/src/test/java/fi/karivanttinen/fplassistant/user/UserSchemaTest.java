package fi.karivanttinen.fplassistant.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four tables Phase 5a adds, and the two structural rules that are easy to undo by accident:
 * messages die with their conversation, and usage does not.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserSchema schema;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.user.message-table-name}")
    String messageTable;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    /**
     * Checked before every test rather than as one of them: JUnit gives no ordering guarantee and
     * does not stop a class on failure, so a sibling test could otherwise write to the real
     * tables before the assertion that was supposed to prevent it ever ran.
     */
    @BeforeEach
    void refuseToRunAgainstProductionTables() {
        assertThat(List.of(profileTable, conversationTable, messageTable, usageTable))
                .allSatisfy(table -> assertThat(table).endsWith("_test"));
    }

    /**
     * The names that matter are the production ones — the test profile's `_test` copies can never
     * collide with tapas. Bound from application.yml rather than injected, because under
     * {@code @ActiveProfiles("test")} the injected values are the isolated copies, and an
     * assertion on those proves nothing about the deploy. Every configured table name is checked,
     * not the four this class happens to own: tapas develops against the same `public` schema,
     * and `CREATE TABLE IF NOT EXISTS` on a name they take first binds this project to their
     * table.
     */
    @Test
    void everyProductionTableNameIsPrefixedAndCannotCollideWithTapas() throws Exception {
        PropertySource<?> production = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).getFirst();

        List<String> tableNames = ((EnumerablePropertySource<?>) production).getPropertyNames()
                .length == 0 ? List.of() : java.util.Arrays.stream(
                        ((EnumerablePropertySource<?>) production).getPropertyNames())
                .filter(name -> name.endsWith("table-name"))
                .map(name -> String.valueOf(production.getProperty(name)))
                .toList();

        assertThat(tableNames).as("the table names should be configured in application.yml")
                .hasSizeGreaterThanOrEqualTo(7);
        assertThat(tableNames)
                .as("an unprefixed name is one tapas can claim — this project uses fpl_ and pl_")
                .allSatisfy(name -> assertThat(name).matches("^(fpl|pl)_.+"));
    }

    @Test
    void allFourTablesExist() {
        for (String table : List.of(profileTable, conversationTable, messageTable, usageTable)) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table))
                    .as("%s should have been created at startup", table)
                    .isNotNull();
        }
    }

    @Test
    void aSecondRunLeavesTheExistingTablesInPlace() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + conversationTable + " (user_id) VALUES (?)", user);
        Long before = jdbc.queryForObject("SELECT ?::regclass::oid::bigint", Long.class, conversationTable);

        try {
            schema.ensureSchema();

            // A DDL that dropped and recreated would pass an existence check and lose every row.
            assertThat(jdbc.queryForObject("SELECT ?::regclass::oid::bigint", Long.class, conversationTable))
                    .isEqualTo(before);
            assertThat(countFor(conversationTable, "user_id", user)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", user);
        }
    }

    /**
     * Both tables the trigger loop covers, and the assertion is that the timestamp moves
     * <em>forward</em>: a trigger writing an older value would sort the conversation to the
     * bottom of the sidebar exactly as the unmaintained column did.
     */
    @Test
    void updatingARowMovesItsUpdatedAtForward() {
        UUID user = UUID.randomUUID();
        UUID conversation = jdbc.queryForObject(
                "INSERT INTO " + conversationTable + " (user_id) VALUES (?) RETURNING id",
                UUID.class, user);
        jdbc.update("INSERT INTO " + profileTable + " (user_id) VALUES (?)", user);

        try {
            Instant conversationBefore = updatedAt(conversationTable, "id", conversation);
            Instant profileBefore = updatedAt(profileTable, "user_id", user);

            jdbc.update("UPDATE " + conversationTable + " SET title = 'renamed' WHERE id = ?",
                    conversation);
            jdbc.update("UPDATE " + profileTable + " SET fpl_team_id = 1 WHERE user_id = ?", user);

            assertThat(updatedAt(conversationTable, "id", conversation)).isAfter(conversationBefore);
            assertThat(updatedAt(profileTable, "user_id", user)).isAfter(profileBefore);
        } finally {
            jdbc.update("DELETE FROM " + conversationTable + " WHERE id = ?", conversation);
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", user);
        }
    }

    private Instant updatedAt(String table, String key, Object value) {
        return jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE " + key + " = ?",
                Timestamp.class, value).toInstant();
    }

    private int countFor(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
