package dev.quantti.fplassistant.user;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent DDL for the four tables Phase 5a adds, run once at startup — the same pattern
 * {@code HistoricalSchema} and {@code PriceSchema} use, since this project has no migration tool.
 *
 * <p><b>The profile table is not called {@code app_user}.</b> The shared database already carries
 * {@code tapas-fpl-app}'s own {@code app_user} and {@code manager} tables, so a
 * {@code CREATE TABLE IF NOT EXISTS app_user} here would find that table, do nothing, and then
 * write our rows into it — the one failure mode "IF NOT EXISTS everywhere" cannot protect
 * against. Phase 5's spec is explicit that our users are unrelated to tapas' and must not be
 * joined to them.
 *
 * <p>{@code user_id} is the JWT {@code sub} claim and nothing else. There is no foreign key to
 * {@code auth.users}: it would tie this DDL to privileges on Supabase's auth schema, which the
 * ephemeral test database does not have at all. Account deletion is not implemented in this
 * phase, so nothing yet depends on the cascade such a key would give.
 *
 * <p><b>{@code usage_event} has no foreign key to {@code conversation} and no cascade path from
 * it.</b> That is the point of the table: usage counted from something the user can delete is a
 * quota the user can refill by deleting a conversation.
 *
 * <p><b>RLS with no policies</b>, which is a deliberate deviation from the spec's "write the
 * policies as defence-in-depth". Deny-all is strictly stronger than an {@code auth.uid()}-scoped
 * policy, it needs no dependency on Supabase's auth schema, and nothing in this project reads
 * these tables through PostgREST — the backend connects as a role with {@code rolbypassrls} and
 * filters on {@code sub} in application SQL. RLS is the second lock on a door we do not use, so
 * the strongest setting is also the simplest. Authorization is the application's job: see the
 * Phase 5a plan.
 */
@Component
public class UserSchema {

    private static final Logger log = LoggerFactory.getLogger(UserSchema.class);

    private final JdbcTemplate jdbc;
    private final String profileTable;
    private final String conversationTable;
    private final String messageTable;
    private final String usageTable;

    public UserSchema(JdbcTemplate jdbc,
                      @Value("${fpl.user.profile-table-name}") String profileTable,
                      @Value("${fpl.user.conversation-table-name}") String conversationTable,
                      @Value("${fpl.user.message-table-name}") String messageTable,
                      @Value("${fpl.user.usage-table-name}") String usageTable) {
        this.jdbc = jdbc;
        this.profileTable = profileTable;
        this.conversationTable = conversationTable;
        this.messageTable = messageTable;
        this.usageTable = usageTable;
    }

    @PostConstruct
    void ensureSchema() {
        // The FPL entry id is not season-scoped: FPL renumbers players every season but a
        // manager keeps their entry id, so unlike fpl_daily_price.element_id this needs no
        // season column. Nullable, because chat works without it and sign-up must not be
        // blocked by it.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    user_id UUID PRIMARY KEY,
                    fpl_team_id INTEGER,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(profileTable));

        // A UUID id rather than a sequence: the id travels to the client, and a guessable one
        // invites probing for other users' conversations. The API answers 404 either way, but
        // not handing out the map is cheaper than defending it.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID NOT NULL,
                    title VARCHAR(200),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    archived_at TIMESTAMPTZ
                )
                """.formatted(conversationTable));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id UUID NOT NULL REFERENCES %s(id) ON DELETE CASCADE,
                    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(messageTable, conversationTable));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    user_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    input_tokens INTEGER,
                    cached_tokens INTEGER,
                    output_tokens INTEGER
                )
                """.formatted(usageTable));

        // The quota check is a range count on exactly this pair, on every chat request.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_created ON %s(user_id, created_at)"
                .formatted(usageTable, usageTable));
        // The conversation list is this order, filtered by owner.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_updated ON %s(user_id, updated_at DESC)"
                .formatted(conversationTable, conversationTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_conversation ON %s(conversation_id, created_at)"
                .formatted(messageTable, messageTable));

        for (String table : new String[] {profileTable, conversationTable, messageTable, usageTable}) {
            jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table));
        }

        log.debug("user schema ensured: {}, {}, {}, {}",
                profileTable, conversationTable, messageTable, usageTable);
    }
}
