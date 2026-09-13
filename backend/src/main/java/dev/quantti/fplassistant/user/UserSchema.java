package dev.quantti.fplassistant.user;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent DDL for the tables this project's user layer owns — four from Phase 5a and the
 * invite ledger the free tier adds — run once at startup, the same pattern
 * {@code HistoricalSchema} and {@code PriceSchema} use, since this project has no migration tool.
 *
 * <p><b>Every table here is {@code fpl_}-prefixed.</b> The shared database already carries
 * {@code upstream-stats-app}'s own {@code app_user} and {@code manager}, so a
 * {@code CREATE TABLE IF NOT EXISTS app_user} would find that table, do nothing, and then write
 * our rows into it — the one failure mode "IF NOT EXISTS everywhere" cannot protect against.
 * That argument does not depend on the name: {@code conversation}, {@code message} and
 * {@code usage_event} are just as available for shared to claim, and our
 * {@code ALTER TABLE ... ENABLE ROW LEVEL SECURITY} would then quietly close a table they read
 * through PostgREST. The prefix is what the existing {@code fpl_embeddings},
 * {@code fpl_daily_price} and {@code fpl_player_price} already do.
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
 * <p><b>{@code weekly_message_limit} is the per-user quota override.</b> The limit has to live
 * in data rather than configuration because it differs per person: the owner's account is
 * unlimited, and a given user may be granted more than the default without a deploy. One nullable
 * column carries all of it:
 *
 * <ul>
 *   <li>{@code NULL} — no override; the configured default ({@code fpl.quota.free-messages-per-week})
 *       applies. This is what almost every row holds.</li>
 *   <li>{@code -1} — unlimited. A sentinel rather than a second column, and enforced by a CHECK
 *       so a typo cannot invent a third meaning.</li>
 *   <li>{@code 0} — no messages at all: a way to suspend an account without deleting it.</li>
 *   <li><i>n</i> — that many messages per rolling seven days.</li>
 * </ul>
 *
 * <p>Changing one is a statement, since there is no admin surface and one person needs this:
 * {@code UPDATE fpl_user_profile SET weekly_message_limit = -1 WHERE user_id = '<uuid>'} — and
 * an {@code INSERT ... ON CONFLICT DO UPDATE} if the row does not exist yet.
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
@DependsOn("playerCatalogueSchema")
public class UserSchema {

    private static final Logger log = LoggerFactory.getLogger(UserSchema.class);

    private final JdbcTemplate jdbc;
    private final String profileTable;
    private final String conversationTable;
    private final String messageTable;
    private final String usageTable;
    private final String inviteTable;
    private final String catalogueTable;

    public UserSchema(JdbcTemplate jdbc,
                      @Value("${fpl.user.profile-table-name}") String profileTable,
                      @Value("${fpl.user.conversation-table-name}") String conversationTable,
                      @Value("${fpl.user.message-table-name}") String messageTable,
                      @Value("${fpl.user.usage-table-name}") String usageTable,
                      @Value("${fpl.user.invite-redemption-table-name}") String inviteTable,
                      @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable) {
        this.jdbc = jdbc;
        this.profileTable = profileTable;
        this.conversationTable = conversationTable;
        this.messageTable = messageTable;
        this.usageTable = usageTable;
        this.inviteTable = inviteTable;
        this.catalogueTable = catalogueTable;
    }

    /**
     * Generated rather than written by the application: no code path can forget it, and two
     * callers cannot disagree about what "total" means. A NULL column contributes nothing —
     * unreported is not zero, but it is not a number to add either.
     */
    private static final String TOTAL_TOKENS_COLUMN =
            "total_tokens INTEGER GENERATED ALWAYS AS ("
            + " COALESCE(input_tokens, 0) + COALESCE(cached_tokens, 0)"
            + " + COALESCE(cache_write_tokens, 0) + COALESCE(output_tokens, 0)) STORED";

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
                    weekly_message_limit INTEGER,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT chk_%s_weekly_limit
                        CHECK (weekly_message_limit IS NULL OR weekly_message_limit >= -1)
                )
                """.formatted(profileTable, profileTable));

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
                    catalogue_id UUID REFERENCES %s(id),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(messageTable, conversationTable, catalogueTable));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    user_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    -- Distinguishes a chat turn from anything else that records usage later:
                    -- title generation, a retry, a background call. Without it the quota query
                    -- counts every row as a message, and neither this nor the provider can be
                    -- backfilled once rows exist — the two chat providers price differently.
                    event_type VARCHAR(32) NOT NULL DEFAULT 'chat',
                    provider VARCHAR(20),
                    -- Which conversation the turn belonged to, for attributing cost to a
                    -- message. Deliberately NOT a foreign key and with no cascade: usage counted
                    -- from something the user can delete is a quota the user can refill by
                    -- deleting a conversation. The column may dangle, and that is the point.
                    conversation_id UUID,
                    -- The model that answered. The rates below are configured per provider, so
                    -- without this a model switch silently reprices history with no trace.
                    model VARCHAR(64),
                    input_tokens INTEGER,
                    cached_tokens INTEGER,
                    -- Anthropic bills cache writes at a premium and cache reads at a tenth, and
                    -- reports input_tokens as the uncached remainder only: the prompt's real
                    -- size is input + cache_write + cache_read. Without this column the premium
                    -- is invisible and every cost is an underestimate.
                    cache_write_tokens INTEGER,
                    output_tokens INTEGER,
                    -- The number the question is usually about: how much did this turn spend,
                    -- all kinds together. See TOTAL_TOKENS_COLUMN above.
                    %s
                )
                """.formatted(usageTable, TOTAL_TOKENS_COLUMN));

        // The account cap is a count of this table, so a row has to exist before the account
        // does: the slot is reserved and committed, and only then is Supabase asked to create
        // the user. One transaction cannot span both, and this is the direction chosen — see
        // InviteLedger. user_id is therefore nullable and is not the key: a row with none is a
        // reserved slot, or a burnt one if the remote call failed. UNIQUE still holds one row
        // per account.
        //
        // code_label records which code an account came in on, so a rotation stays legible
        // afterwards. It is a fingerprint of the code, never the code: this table is not a
        // secret store. No email column either — auth.users has it, and a second copy is PII
        // kept for a purpose user_id already serves.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    user_id UUID UNIQUE,
                    code_label TEXT,
                    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(inviteTable));

        // The columns above arrived after the table existed in production, and this project has
        // no migration tool. IF NOT EXISTS makes each one a no-op on a database that has it.
        // The catalogue schema is created first so this additive reference is also safe during an
        // upgrade from a database whose message table predates shortlist metadata.
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS catalogue_id UUID REFERENCES %s(id)"
                .formatted(messageTable, catalogueTable));
        for (String column : new String[] {
                "conversation_id UUID", "model VARCHAR(64)", "cache_write_tokens INTEGER",
                TOTAL_TOKENS_COLUMN}) {
            jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s".formatted(usageTable, column));
        }

        // The quota check is a range count on exactly this pair, on every chat request.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_created ON %s(user_id, created_at)"
                .formatted(usageTable, usageTable));
        // The conversation list is this order, filtered by owner.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_updated ON %s(user_id, updated_at DESC)"
                .formatted(conversationTable, conversationTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_conversation ON %s(conversation_id, created_at)"
                .formatted(messageTable, messageTable));

        // updated_at is the sort key of the conversation list's only index, and nothing was
        // maintaining it: a conversation used five minutes ago would keep its creation time and
        // sink to the bottom of the sidebar. Left to callers it is one forgotten UPDATE away
        // from wrong; as a trigger it is structural.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_set_updated_at() RETURNS trigger
                LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
                BEGIN
                    NEW.updated_at = NOW();
                    RETURN NEW;
                END $$
                """);
        for (String table : new String[] {profileTable, conversationTable}) {
            // One statement, not DROP + CREATE: between those two an in-flight UPDATE commits
            // with updated_at frozen — the exact bug the trigger exists to prevent, reopened
            // for a moment on every boot. CREATE OR REPLACE TRIGGER is Postgres 14+.
            jdbc.execute("""
                    CREATE OR REPLACE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %s
                    FOR EACH ROW EXECUTE FUNCTION fpl_set_updated_at()
                    """.formatted(table, table));
        }

        for (String table : new String[] {profileTable, conversationTable, messageTable, usageTable,
                inviteTable}) {
            jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table));
        }

        log.debug("user schema ensured: {}, {}, {}, {}, {}",
                profileTable, conversationTable, messageTable, usageTable, inviteTable);
    }
}
