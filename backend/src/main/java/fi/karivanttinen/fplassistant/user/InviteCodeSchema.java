package fi.karivanttinen.fplassistant.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Idempotent DDL for {@code fpl_invite_code}, and the columns {@code fpl_invite_redemption} needs
 * to point at it. Same shape as the other schema classes: every statement is
 * {@code IF NOT EXISTS} and the table names are properties so the test profile writes to isolated
 * {@code _test} copies.
 *
 * <p><b>Why a table rather than the {@code INVITE_CODE} environment variable it replaces.</b> One
 * secret in the environment can express exactly one thing — whether a string matches — and every
 * question the owner actually has is about something else: how many places are left on the code
 * given to a podcast, which code brought the people who signed up last week, whether the launch
 * code should stop working in March, and what a particular code is worth once billing exists. A
 * row can answer all of those; a rotation of one shared secret can answer none of them, and it
 * revokes everyone at once.
 *
 * <p><b>{@code uses} is a stored counter, not a count of redemptions.</b> The redemption rows are
 * the audit trail and the counter is the thing the ceiling is checked against, incremented in the
 * same transaction that writes the row. Counting rows on every sign-up would be correct too, but
 * it puts the ceiling one join away from the lock that protects it; a column on the row being
 * locked cannot drift from what the lock is guarding. They are reconcilable at any time, which is
 * what makes the redundancy safe rather than a second source of truth.
 *
 * <p>The redemption table keeps its {@code code_label} column. Rows written before this existed
 * carry a fingerprint and no {@code code_id}, and dropping the column would erase which code
 * those accounts came in on.
 */
@Component
public class InviteCodeSchema {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeSchema.class);

    private final JdbcTemplate jdbc;
    private final String codeTable;
    private final String redemptionTable;

    public InviteCodeSchema(JdbcTemplate jdbc,
                            @Value("${fpl.user.invite-code-table-name}") String codeTable,
                            @Value("${fpl.user.invite-redemption-table-name}") String redemptionTable) {
        this.jdbc = jdbc;
        this.codeTable = codeTable;
        this.redemptionTable = redemptionTable;
    }

    @PostConstruct
    public void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    code TEXT NOT NULL,
                    label TEXT,
                    max_uses INTEGER,
                    uses INTEGER NOT NULL DEFAULT 0,
                    benefit_kind TEXT NOT NULL DEFAULT 'access',
                    benefit_value INTEGER,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    valid_from TIMESTAMPTZ,
                    valid_until TIMESTAMPTZ,
                    note TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT %s_uses_within_max CHECK (max_uses IS NULL OR uses <= max_uses),
                    CONSTRAINT %s_benefit_value_present CHECK (
                        benefit_kind <> 'discount_percent'
                        OR (benefit_value IS NOT NULL AND benefit_value BETWEEN 1 AND 100))
                )
                """.formatted(codeTable, codeTable, codeTable));

        // Case-insensitive uniqueness, because the code is typed by a person and matched exactly.
        // Two codes differing only in case would be indistinguishable to whoever was given one.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_%s_code ON %s (LOWER(code))"
                .formatted(codeTable, codeTable));

        // The redemption row points at the code it used. Nullable: rows written under the single
        // shared secret have no code row to point at, and inventing one would put a code in the
        // table that was never issued.
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS code_id BIGINT"
                .formatted(redemptionTable));

        // "How many places has this code used" is asked under a lock on every sign-up.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_code_id ON %s (code_id)"
                .formatted(redemptionTable, redemptionTable));

        // Same reasoning as every other table this project creates: PostgREST exposes anything
        // in the public schema to the anon key, and a table of live invite codes is the last one
        // that should be readable that way. No policy is added, so only the service role — which
        // bypasses RLS — reaches it, which is exactly the set of callers that should.
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(codeTable));

        log.info("invite code tables ready ({} and {}.code_id)", codeTable, redemptionTable);
    }
}
