package fi.karivanttinen.fplassistant.user;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invite codes, the ceilings on them, and the record of which code an account came in on.
 *
 * <p><b>Reserving is a transaction of its own and commits before Supabase is called.</b> That
 * ordering is the whole point. One transaction cannot span Postgres and Supabase, so the only
 * real choice is which way the pair fails when it comes apart. Counting after the account exists
 * fails toward <i>undercounting</i> — an account nobody counted, and a ceiling that drifts upward
 * with every failure, silently. Reserving first fails toward <i>overcounting</i>: a Supabase
 * error leaves a committed row with a null {@code user_id}, burning one place. That is a visible
 * row, releasable by hand, and it errs toward refusing one person too many rather than letting
 * the tier grow past its ceiling with no trace.
 *
 * <p>A place is therefore never released automatically. Releasing one is a statement, since there
 * is no admin surface and this is a rare event — and with per-code ceilings it is now two:
 * {@code DELETE FROM fpl_invite_redemption WHERE user_id IS NULL} and then re-running
 * {@link #reconcile} to bring each code's counter back in line with its rows. Since KAR-54 that
 * is {@link #releaseBurnt}, reached through {@code POST /api/admin/invite-codes/release-burnt}.
 *
 * <p>The advisory key is constant rather than per code, so every sign-up serialises against every
 * other. Per-code would be the tighter lock, but the account cap is a global count that has to be
 * read under the same lock as the per-code ceiling, and the total volume is a few dozen sign-ups
 * for the lifetime of the tier. Without it, two requests at {@code uses == max_uses - 1} both
 * read the same number and both go through.
 */
@Service
public class InviteLedger {

    /** The tier is full, or this code is. Thrown rather than returned: the caller answers 409. */
    public static class CapReachedException extends RuntimeException {
        CapReachedException(String message) {
            super(message);
        }
    }

    /** The code does not exist, or is closed. The message is what the person who typed it sees. */
    public static class CodeRefusedException extends RuntimeException {
        CodeRefusedException(String message) {
            super(message);
        }
    }

    /** A reserved place, and what the code it came from is worth. */
    public record Reservation(long slotId, InviteCode code) {}

    /** A ceiling lower than what the code has already spent. The caller answers 409. */
    public static class CeilingBelowUsesException extends RuntimeException {
        CeilingBelowUsesException(String message) {
            super(message);
        }
    }

    /**
     * One code with the counts the admin page shows beside it. {@code uses} on the code is the
     * stored counter the ceiling is checked against; {@code accounts} is the redemption rows with
     * a user and {@code burnt} the rows without one. They differ only after a burnt place, which
     * is why all three are shown.
     */
    public record CodeSummary(InviteCode code, int accounts, int burnt,
                              OffsetDateTime lastRedeemedAt) {}

    /**
     * The ledger at a glance. {@code placesTaken} is every redemption row, burnt included — the
     * number the account cap is checked against.
     */
    public record Summary(int accountCap, int placesTaken, int burnt, List<CodeSummary> codes) {}

    /** One redemption; {@code userId} is null for a burnt place. */
    public record Redemption(long id, UUID userId, OffsetDateTime redeemedAt) {}

    /** What releasing burnt places did: rows deleted, and the codes whose counter moved. */
    public record Release(int released, List<String> reconciled) {}

    private final JdbcTemplate jdbc;
    private final String codeTable;
    private final String redemptionTable;
    private final int maxAccounts;

    public InviteLedger(JdbcTemplate jdbc,
                        @Value("${fpl.user.invite-code-table-name}") String codeTable,
                        @Value("${fpl.user.invite-redemption-table-name}") String redemptionTable,
                        @Value("${fpl.invite.max-accounts}") int maxAccounts) {
        this.jdbc = jdbc;
        this.codeTable = codeTable;
        this.redemptionTable = redemptionTable;
        this.maxAccounts = maxAccounts;
    }

    /**
     * Takes one place on the given code, or refuses.
     *
     * @param presented the code as the user typed it; matched case-insensitively and trimmed
     * @return the reserved row and the code's terms, to be stamped with the user id once Supabase
     *         has created the account
     * @throws CodeRefusedException when the code is unknown, inactive, expired or not yet valid
     * @throws CapReachedException when the code or the tier is full
     */
    @Transactional
    public Reservation reserve(String presented) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                redemptionTable);

        InviteCode code = findByCode(presented)
                .orElseThrow(() -> new CodeRefusedException("That invite code is not valid."));

        OffsetDateTime now = OffsetDateTime.now();
        if (code.exhausted()) {
            throw new CapReachedException("the code " + code.label() + " has used all "
                    + code.maxUses() + " of its places");
        }
        String refusal = code.refusalReason(now);
        if (refusal != null) {
            throw new CodeRefusedException(refusal);
        }

        // The account cap is a cost ceiling across every code, not a property of any one of them.
        // A code worth a hundred places still cannot take the tier past what it can pay for.
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM " + redemptionTable,
                Integer.class);
        if (taken != null && taken >= maxAccounts) {
            throw new CapReachedException("the account cap of " + maxAccounts + " is reached");
        }

        jdbc.update("UPDATE " + codeTable + " SET uses = uses + 1 WHERE id = ?", code.id());
        Long slotId = jdbc.queryForObject(
                "INSERT INTO " + redemptionTable + " (code_id, code_label) VALUES (?, ?)"
                        + " RETURNING id",
                Long.class, code.id(), code.label());
        return new Reservation(slotId == null ? 0 : slotId, code);
    }

    /** Names the account a reserved place belongs to, once Supabase has created it. */
    public void assign(long slotId, UUID userId) {
        jdbc.update("UPDATE " + redemptionTable + " SET user_id = ? WHERE id = ?", userId, slotId);
    }

    /** The code an account came in on, for the billing phase to read its benefit from. */
    public Optional<InviteCode> codeFor(UUID userId) {
        List<InviteCode> rows = jdbc.query("""
                SELECT c.* FROM %s c
                JOIN %s r ON r.code_id = c.id
                WHERE r.user_id = ?
                """.formatted(codeTable, redemptionTable), InviteLedger::mapCode, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<InviteCode> findByCode(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        List<InviteCode> rows = jdbc.query(
                "SELECT * FROM " + codeTable + " WHERE LOWER(code) = LOWER(?)",
                InviteLedger::mapCode, presented.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<InviteCode> all() {
        return jdbc.query("SELECT * FROM " + codeTable + " ORDER BY created_at, id",
                InviteLedger::mapCode);
    }

    /**
     * Creates a code. Returns empty when one with that string already exists, so re-running the
     * command that issues codes cannot quietly reset a live code's ceiling.
     */
    public Optional<InviteCode> create(String code, String label, Integer maxUses,
                                       String benefitKind, Integer benefitValue,
                                       OffsetDateTime validFrom, OffsetDateTime validUntil,
                                       String note) {
        if (findByCode(code).isPresent()) {
            return Optional.empty();
        }
        jdbc.update("""
                INSERT INTO %s (code, label, max_uses, benefit_kind, benefit_value, valid_from,
                                valid_until, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(codeTable),
                code.trim(), label, maxUses, benefitKind, benefitValue, validFrom, validUntil, note);
        return findByCode(code);
    }

    public Summary summary() {
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM " + redemptionTable,
                Integer.class);
        Integer burnt = jdbc.queryForObject(
                "SELECT count(*) FROM " + redemptionTable + " WHERE user_id IS NULL",
                Integer.class);
        return new Summary(maxAccounts, taken == null ? 0 : taken, burnt == null ? 0 : burnt,
                summaries("", new Object[0]));
    }

    public Optional<CodeSummary> summaryOf(long id) {
        List<CodeSummary> rows = summaries("WHERE c.id = ?", new Object[] {id});
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    // One query, not one per code: count(user_id) counts the rows with a user, count(*) all of
    // them, and the difference is the burnt places.
    private List<CodeSummary> summaries(String where, Object[] args) {
        return jdbc.query("""
                SELECT c.*,
                       COALESCE(r.accounts, 0) AS accounts,
                       COALESCE(r.burnt, 0) AS burnt,
                       r.last_redeemed_at
                  FROM %s c
                  LEFT JOIN (SELECT code_id,
                                    count(user_id) AS accounts,
                                    count(*) - count(user_id) AS burnt,
                                    max(redeemed_at) AS last_redeemed_at
                               FROM %s
                              GROUP BY code_id) r ON r.code_id = c.id
                 %s
                 ORDER BY c.created_at, c.id
                """.formatted(codeTable, redemptionTable, where),
                (rs, rowNum) -> new CodeSummary(mapCode(rs, rowNum), rs.getInt("accounts"),
                        rs.getInt("burnt"), rs.getObject("last_redeemed_at", OffsetDateTime.class)),
                args);
    }

    public Optional<InviteCode> findById(long id) {
        List<InviteCode> rows = jdbc.query("SELECT * FROM " + codeTable + " WHERE id = ?",
                InviteLedger::mapCode, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Opens or closes a code. False when no such code exists. Idempotent. */
    public boolean setActive(long id, boolean active) {
        return jdbc.update("UPDATE " + codeTable + " SET active = ? WHERE id = ?", active, id) == 1;
    }

    /**
     * Moves a code's ceiling; null removes it. Under the sign-up lock, so a reservation cannot
     * slip between the read and the write, and the CHECK constraint stays a backstop rather than
     * the error the caller sees.
     *
     * @throws CeilingBelowUsesException when the ceiling would be below what is already spent —
     *         the code should be disabled instead, and the message says so
     */
    @Transactional
    public Optional<InviteCode> setMaxUses(long id, Integer maxUses) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                redemptionTable);
        Optional<InviteCode> current = findById(id);
        if (current.isEmpty()) {
            return current;
        }
        if (maxUses != null && maxUses < current.get().uses()) {
            throw new CeilingBelowUsesException("The code " + current.get().code() + " has already"
                    + " used " + current.get().uses() + " places; disable it rather than setting"
                    + " a ceiling below that.");
        }
        jdbc.update("UPDATE " + codeTable + " SET max_uses = ? WHERE id = ?", maxUses, id);
        return findById(id);
    }

    /** The places a code has given out, newest first. Burnt places carry no user. */
    public List<Redemption> redemptionsOf(long codeId) {
        return jdbc.query("SELECT id, user_id, redeemed_at FROM " + redemptionTable
                        + " WHERE code_id = ? ORDER BY redeemed_at DESC, id DESC",
                (rs, rowNum) -> new Redemption(rs.getLong("id"),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("redeemed_at", OffsetDateTime.class)),
                codeId);
    }

    /**
     * Deletes burnt places older than {@code grace} and brings every counter back in line — the
     * two-statement procedure from {@code docs/DEVELOPMENT.md} as one call, under the sign-up
     * lock. A burnt row younger than the grace period is left alone: it may be a sign-up whose
     * Supabase call has not yet answered, and deleting it would let that account through
     * uncounted.
     */
    @Transactional
    public Release releaseBurnt(Duration grace) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                redemptionTable);
        int released = jdbc.update("DELETE FROM " + redemptionTable
                        + " WHERE user_id IS NULL AND redeemed_at < ?",
                OffsetDateTime.now().minus(grace));
        return new Release(released, reconcile());
    }

    /**
     * Brings every code's counter back in line with the redemption rows that point at it, and
     * reports the codes that had drifted.
     *
     * <p>The counter and the rows are written in one transaction, so they cannot drift on their
     * own. They drift when a place is released by hand — the documented way to recover one burnt
     * by a failed Supabase call — because that deletes a row and leaves the counter untouched.
     * This is the second half of that procedure.
     */
    @Transactional
    public List<String> reconcile() {
        List<String> drifted = jdbc.queryForList("""
                SELECT c.code FROM %s c
                WHERE c.uses <> (SELECT count(*) FROM %s r WHERE r.code_id = c.id)
                """.formatted(codeTable, redemptionTable), String.class);
        jdbc.update("""
                UPDATE %s c
                   SET uses = (SELECT count(*) FROM %s r WHERE r.code_id = c.id)
                 WHERE c.uses <> (SELECT count(*) FROM %s r WHERE r.code_id = c.id)
                """.formatted(codeTable, redemptionTable, redemptionTable));
        return drifted;
    }

    private static InviteCode mapCode(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Integer maxUses = (Integer) rs.getObject("max_uses");
        Integer benefitValue = (Integer) rs.getObject("benefit_value");
        return new InviteCode(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("label"),
                maxUses,
                rs.getInt("uses"),
                rs.getString("benefit_kind"),
                benefitValue,
                rs.getBoolean("active"),
                rs.getObject("valid_from", OffsetDateTime.class),
                rs.getObject("valid_until", OffsetDateTime.class),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
