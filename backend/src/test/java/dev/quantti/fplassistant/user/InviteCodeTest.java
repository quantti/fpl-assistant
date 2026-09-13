package dev.quantti.fplassistant.user;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One rule for whether a code is open, read two ways: the state the admin page shows and the
 * refusal the person typing the code is told. They must never disagree, so both come from
 * {@link InviteCode#state}.
 */
class InviteCodeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 13, 12, 0, 0, 0, ZoneOffset.UTC);

    private static InviteCode code(Integer maxUses, int uses, boolean active,
                                   OffsetDateTime validFrom, OffsetDateTime validUntil) {
        return new InviteCode(1, "launch", "launch", maxUses, uses, InviteCode.ACCESS, null,
                active, validFrom, validUntil, null, NOW.minusDays(1));
    }

    @Test
    void anOpenCodeHasNoRefusal() {
        InviteCode open = code(10, 3, true, null, null);
        assertThat(open.state(NOW)).isEqualTo(InviteCode.State.OPEN);
        assertThat(open.refusalReason(NOW)).isNull();
        assertThat(open.openAt(NOW)).isTrue();
    }

    @Test
    void disabledWinsOverEverythingElse() {
        InviteCode disabled = code(1, 1, false, NOW.plusDays(1), NOW.minusDays(1));
        assertThat(disabled.state(NOW)).isEqualTo(InviteCode.State.DISABLED);
        assertThat(disabled.refusalReason(NOW)).isEqualTo("That invite code is no longer in use.");
    }

    @Test
    void usedUpComesBeforeTheValidityWindow() {
        InviteCode full = code(5, 5, true, null, NOW.minusDays(1));
        assertThat(full.state(NOW)).isEqualTo(InviteCode.State.EXHAUSTED);
        assertThat(full.refusalReason(NOW)).isEqualTo("That invite code has been used up.");
        assertThat(full.remaining()).isZero();
    }

    @Test
    void notYetValidAndExpiredAreDistinct() {
        InviteCode early = code(null, 0, true, NOW.plusHours(1), null);
        InviteCode late = code(null, 0, true, null, NOW.minusHours(1));
        assertThat(early.state(NOW)).isEqualTo(InviteCode.State.NOT_YET_VALID);
        assertThat(early.refusalReason(NOW)).isEqualTo("That invite code is not valid yet.");
        assertThat(late.state(NOW)).isEqualTo(InviteCode.State.EXPIRED);
        assertThat(late.refusalReason(NOW)).isEqualTo("That invite code has expired.");
    }

    @Test
    void aCodeWithNoCeilingIsNeverExhausted() {
        InviteCode unlimited = code(null, 1_000, true, null, null);
        assertThat(unlimited.exhausted()).isFalse();
        assertThat(unlimited.remaining()).isNull();
        assertThat(unlimited.state(NOW)).isEqualTo(InviteCode.State.OPEN);
    }
}
