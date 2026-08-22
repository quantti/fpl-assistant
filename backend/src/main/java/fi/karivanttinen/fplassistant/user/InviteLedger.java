package fi.karivanttinen.fplassistant.user;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account cap, and the record of which code an account came in on.
 *
 * <p><b>Reserving is a transaction of its own and commits before Supabase is called.</b> That
 * ordering is the whole point. One transaction cannot span Postgres and Supabase, so the only
 * real choice is which way the pair fails when it comes apart. Counting after the account exists
 * fails toward <i>undercounting</i> — an account nobody counted, and a cap that drifts upward
 * with every failure, silently. Reserving first fails toward <i>overcounting</i>: a Supabase
 * error leaves a committed row with a null {@code user_id}, burning one slot. That is a visible
 * row, releasable by hand, and it errs toward refusing one person too many rather than letting
 * the tier grow past its ceiling with no trace.
 *
 * <p>A slot is therefore never released automatically. Releasing one is a statement, since there
 * is no admin surface and this is a rare event:
 * {@code DELETE FROM fpl_invite_redemption WHERE user_id IS NULL}.
 *
 * <p>The advisory key is constant rather than per user, so every sign-up serialises against every
 * other. That would be wrong for chat, where the lock is per person; it is right here, because
 * the cap is a global count and the total volume is a few dozen rows for the lifetime of the
 * tier. Without it, two requests at {@code taken == max - 1} both read the same number and both
 * go through.
 */
@Service
public class InviteLedger {

    /** The cap is reached. Thrown rather than returned: the caller can only answer 409. */
    public static class CapReachedException extends RuntimeException {
        CapReachedException(int maxAccounts) {
            super("the invite cap of " + maxAccounts + " accounts is reached");
        }
    }

    private final JdbcTemplate jdbc;
    private final String inviteTable;
    private final int maxAccounts;

    public InviteLedger(JdbcTemplate jdbc,
                        @Value("${fpl.user.invite-redemption-table-name}") String inviteTable,
                        @Value("${fpl.invite.max-accounts}") int maxAccounts) {
        this.jdbc = jdbc;
        this.inviteTable = inviteTable;
        this.maxAccounts = maxAccounts;
    }

    /**
     * Takes one of the remaining slots, or refuses when there are none.
     *
     * @param codeLabel which code is being redeemed — a fingerprint, never the code
     * @return the reserved row's id, to be stamped with the user id once Supabase has created it
     * @throws CapReachedException when the tier is full
     */
    @Transactional
    public long reserve(String codeLabel) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, inviteTable);
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM " + inviteTable, Integer.class);
        if (taken >= maxAccounts) {
            throw new CapReachedException(maxAccounts);
        }
        return jdbc.queryForObject(
                "INSERT INTO " + inviteTable + " (code_label) VALUES (?) RETURNING id",
                Long.class, codeLabel);
    }

    /** Names the account a reserved slot belongs to, once Supabase has created it. */
    public void assign(long slotId, UUID userId) {
        jdbc.update("UPDATE " + inviteTable + " SET user_id = ? WHERE id = ?", userId, slotId);
    }
}
