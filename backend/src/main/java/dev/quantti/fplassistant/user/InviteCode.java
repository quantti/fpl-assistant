package dev.quantti.fplassistant.user;

import java.time.OffsetDateTime;

/**
 * One invite code: what it is worth, how many people may use it, and whether it is still open.
 *
 * <p>Replaces the single shared secret in {@code INVITE_CODE}, which could say only "yes" or
 * "no". A code now carries its own ceiling, so one can be worth ten places and another a hundred,
 * and its own benefit, so one can open the door and another take half off the subscription.
 *
 * <p><b>Stored in plain text, deliberately.</b> An invite code is a promotion code, not a
 * credential: it is printed on things, read out on podcasts and pasted into group chats, and the
 * owner has to be able to look up which one is which. What protects the tier is the ceiling on
 * each code and the account cap behind it, not the secrecy of the string. That is also why the
 * lookup is an ordinary indexed equality rather than a constant-time compare — the old code was
 * one shared secret where a timing signal would have leaked the whole gate; a table of promotion
 * codes has no such single point, and the per-IP rate limit is what blunts guessing.
 *
 * @param benefitKind {@link #ACCESS} or {@link #DISCOUNT_PERCENT}
 * @param benefitValue the percentage for a discount code, null for a plain access code
 * @param maxUses null means no ceiling of its own — the account cap is then the only limit
 * @param note free text for the owner — where the code was handed out, say
 * @param createdAt when the row was written
 */
public record InviteCode(long id, String code, String label, Integer maxUses, int uses,
                         String benefitKind, Integer benefitValue, boolean active,
                         OffsetDateTime validFrom, OffsetDateTime validUntil,
                         String note, OffsetDateTime createdAt) {

    /** Opens the door and nothing more. */
    public static final String ACCESS = "access";

    /** Opens the door and records a percentage off, for the billing phase to honour. */
    public static final String DISCOUNT_PERCENT = "discount_percent";

    /**
     * Why a code is closed, or {@link #OPEN}. Checked in this order: a disabled code is disabled
     * whatever else is true of it, and a used-up one is used up whether or not it has also
     * expired. The admin page shows this and the sign-up refusal is phrased from it, so the two
     * cannot drift apart.
     */
    public enum State { OPEN, DISABLED, EXHAUSTED, NOT_YET_VALID, EXPIRED }

    /** What is left, or null when the code has no ceiling of its own. */
    public Integer remaining() {
        return maxUses == null ? null : Math.max(0, maxUses - uses);
    }

    public boolean exhausted() {
        return maxUses != null && uses >= maxUses;
    }

    /**
     * The code's state at this instant. Kept out of the SQL so the reason a code was refused is
     * available to the caller and to the log — "expired" and "used up" are different things to
     * tell someone, and a single {@code WHERE} that returns no row cannot tell them apart.
     */
    public State state(OffsetDateTime when) {
        if (!active) {
            return State.DISABLED;
        }
        if (exhausted()) {
            return State.EXHAUSTED;
        }
        if (validFrom != null && when.isBefore(validFrom)) {
            return State.NOT_YET_VALID;
        }
        if (validUntil != null && when.isAfter(validUntil)) {
            return State.EXPIRED;
        }
        return State.OPEN;
    }

    /** Whether the code is open at this instant. */
    public boolean openAt(OffsetDateTime when) {
        return state(when) == State.OPEN;
    }

    /** Why the code was refused, or null when it was not. Phrased for the person who typed it. */
    public String refusalReason(OffsetDateTime when) {
        return switch (state(when)) {
            case OPEN -> null;
            case DISABLED -> "That invite code is no longer in use.";
            case EXHAUSTED -> "That invite code has been used up.";
            case NOT_YET_VALID -> "That invite code is not valid yet.";
            case EXPIRED -> "That invite code has expired.";
        };
    }
}
