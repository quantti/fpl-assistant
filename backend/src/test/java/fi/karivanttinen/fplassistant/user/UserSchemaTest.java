package fi.karivanttinen.fplassistant.user;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void everyTableIsPinnedToAnIsolatedCopyUnderTheTestProfile() {
        assertThat(List.of(profileTable, conversationTable, messageTable, usageTable))
                .allSatisfy(table -> assertThat(table).endsWith("_test"));
    }

    /**
     * tapas-fpl-app owns `app_user` in this same database. CREATE TABLE IF NOT EXISTS would find
     * that table, do nothing, and then this project would write its rows into tapas' table.
     */
    @Test
    void theProfileTableIsNotNamedAppUser() {
        assertThat(profileTable).doesNotStartWith("app_user");
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
    void aSecondRunChangesNothing() {
        schema.ensureSchema();

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, conversationTable))
                .isNotNull();
    }

    @Test
    void deletingAConversationTakesItsMessagesAndLeavesUsageAlone() {
        UUID user = UUID.randomUUID();
        UUID conversation = jdbc.queryForObject(
                "INSERT INTO " + conversationTable + " (user_id, title) VALUES (?, ?) RETURNING id",
                UUID.class, user, "seeded by UserSchemaTest");
        jdbc.update("INSERT INTO " + messageTable + " (conversation_id, role, content)"
                + " VALUES (?, 'user', 'question')", conversation);
        jdbc.update("INSERT INTO " + usageTable + " (user_id, input_tokens, output_tokens)"
                + " VALUES (?, 100, 200)", user);

        jdbc.update("DELETE FROM " + conversationTable + " WHERE id = ?", conversation);

        assertThat(countFor(messageTable, "conversation_id", conversation)).isZero();
        assertThat(countFor(usageTable, "user_id", user))
                .as("usage counted from something the user can delete is a quota they can refill")
                .isEqualTo(1);

        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", user);
    }

    /**
     * Checked structurally as well as behaviourally: a foreign key added later would reintroduce
     * the refill path, and the cascade test above would still pass until someone also added
     * ON DELETE CASCADE.
     */
    @Test
    void usageEventHasNoForeignKeyToConversation() {
        List<String> references = jdbc.queryForList("""
                SELECT confrelid::regclass::text
                FROM pg_constraint
                WHERE conrelid = ?::regclass AND contype = 'f'
                """, String.class, usageTable);

        assertThat(references).doesNotContain(conversationTable);
    }

    @Test
    void aMessageMustBeFromTheUserOrTheAssistant() {
        UUID conversation = jdbc.queryForObject(
                "INSERT INTO " + conversationTable + " (user_id) VALUES (?) RETURNING id",
                UUID.class, UUID.randomUUID());

        try {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO " + messageTable
                    + " (conversation_id, role, content) VALUES (?, 'system', 'x')", conversation))
                    .as("only user and assistant turns are stored")
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.update("DELETE FROM " + conversationTable + " WHERE id = ?", conversation);
        }
    }

    /**
     * The quota has to be settable per person — the owner's account is unlimited, and a user may
     * be granted more than the default without a deploy — so it lives in data, not configuration.
     * One nullable column carries the three states, and the CHECK is what stops a typo inventing
     * a fourth.
     */
    @Test
    void theWeeklyLimitIsAPerUserOverrideWithADefaultOfNone() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + profileTable + " (user_id) VALUES (?)", user);

        try {
            assertThat(limitOf(user))
                    .as("no override means the configured default applies")
                    .isNull();

            for (int limit : new int[] {-1, 0, 500}) {
                jdbc.update("UPDATE " + profileTable + " SET weekly_message_limit = ? WHERE user_id = ?",
                        limit, user);
                assertThat(limitOf(user)).isEqualTo(limit);
            }

            assertThatThrownBy(() -> jdbc.update("UPDATE " + profileTable
                    + " SET weekly_message_limit = -2 WHERE user_id = ?", user))
                    .as("-1 is unlimited; anything below it has no meaning")
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", user);
        }
    }

    private Integer limitOf(UUID user) {
        return jdbc.queryForObject(
                "SELECT weekly_message_limit FROM " + profileTable + " WHERE user_id = ?",
                Integer.class, user);
    }

    private int countFor(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
