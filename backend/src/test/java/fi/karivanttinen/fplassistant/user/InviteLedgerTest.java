package fi.karivanttinen.fplassistant.user;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin reads and writes on the ledger. Counts are asserted against rows the test seeds:
 * {@code uses} is the stored counter, {@code accounts} the rows with a user, {@code burnt} the
 * rows without one, and after a burnt place they are three different numbers.
 */
@SpringBootTest
@ActiveProfiles("test")
class InviteLedgerTest {

    @Autowired
    InviteLedger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.user.invite-code-table-name}")
    String codeTable;

    @Value("${fpl.user.invite-redemption-table-name}")
    String redemptionTable;

    @Value("${fpl.invite.max-accounts}")
    int accountCap;

    @BeforeEach
    @AfterEach
    void empty() {
        jdbc.update("DELETE FROM " + redemptionTable);
        jdbc.update("DELETE FROM " + codeTable);
    }

    private long seedCode(String code, Integer maxUses, int uses) {
        return jdbc.queryForObject("INSERT INTO " + codeTable
                + " (code, label, max_uses, uses) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, code, code, maxUses, uses);
    }

    private void seedRedemption(long codeId, UUID userId, OffsetDateTime at) {
        jdbc.update("INSERT INTO " + redemptionTable
                + " (code_id, code_label, user_id, redeemed_at) VALUES (?, ?, ?, ?)",
                codeId, "seed", userId, at);
    }

    @Test
    void theSummaryCountsAccountsAndBurntPlacesPerCodeAndOverall() {
        OffsetDateTime now = OffsetDateTime.now();
        long launch = seedCode("launch", 10, 3);
        long podcast = seedCode("podcast", null, 0);
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(2));
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(launch, null, now.minusHours(1));

        InviteLedger.Summary summary = ledger.summary();

        assertThat(summary.accountCap()).isEqualTo(accountCap);
        assertThat(summary.placesTaken()).isEqualTo(3);
        assertThat(summary.burnt()).isEqualTo(1);
        assertThat(summary.codes()).extracting(s -> s.code().code()).containsExactly("launch", "podcast");

        InviteLedger.CodeSummary first = summary.codes().getFirst();
        assertThat(first.code().uses()).isEqualTo(3);
        assertThat(first.accounts()).isEqualTo(2);
        assertThat(first.burnt()).isEqualTo(1);
        assertThat(first.lastRedeemedAt()).isAfter(now.minusHours(2));

        InviteLedger.CodeSummary second = summary.codes().get(1);
        assertThat(second.accounts()).isZero();
        assertThat(second.burnt()).isZero();
        assertThat(second.lastRedeemedAt()).isNull();
        assertThat(ledger.summaryOf(podcast)).isPresent();
        assertThat(ledger.summaryOf(999_999)).isEmpty();
    }

    @Test
    void disablingAndEnablingFlipTheFlagAndReportAnUnknownId() {
        long id = seedCode("launch", 10, 0);

        assertThat(ledger.setActive(id, false)).isTrue();
        assertThat(ledger.findById(id).orElseThrow().active()).isFalse();
        assertThat(ledger.setActive(id, true)).isTrue();
        assertThat(ledger.findById(id).orElseThrow().active()).isTrue();
        assertThat(ledger.setActive(999_999, false)).isFalse();
    }

    @Test
    void theCeilingCanMoveDownToWhatIsSpentButNotBelowIt() {
        long id = seedCode("launch", 10, 4);

        assertThat(ledger.setMaxUses(id, 4).orElseThrow().remaining()).isZero();
        assertThatThrownBy(() -> ledger.setMaxUses(id, 3))
                .isInstanceOf(InviteLedger.CeilingBelowUsesException.class);
        assertThat(ledger.findById(id).orElseThrow().maxUses()).isEqualTo(4);
        assertThat(ledger.setMaxUses(id, null).orElseThrow().maxUses()).isNull();
        assertThat(ledger.setMaxUses(999_999, 5)).isEmpty();
    }

    @Test
    void redemptionsAreListedNewestFirstWithBurntPlacesAsNullUsers() {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        UUID older = UUID.randomUUID();
        seedRedemption(id, older, now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));

        List<InviteLedger.Redemption> rows = ledger.redemptionsOf(id);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).userId()).isNull();
        assertThat(rows.get(1).userId()).isEqualTo(older);
        assertThat(ledger.redemptionsOf(999_999)).isEmpty();
    }

    /** A young burnt row may be a sign-up whose Supabase call is still in flight. */
    @Test
    void releasingBurntPlacesSparesRecentOnesAndReconcilesTheCounter() {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 3);
        seedRedemption(id, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));
        seedRedemption(id, null, now.minusMinutes(1));

        InviteLedger.Release release = ledger.releaseBurnt(Duration.ofMinutes(10));

        assertThat(release.released()).isEqualTo(1);
        assertThat(release.reconciled()).containsExactly("launch");
        assertThat(ledger.findById(id).orElseThrow().uses()).isEqualTo(2);
        assertThat(ledger.summary().burnt()).isEqualTo(1);
    }

    @Test
    void createRecordsTheWholeValidityWindowAndTheNote() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(1).withNano(0);
        OffsetDateTime until = from.plusDays(30);

        InviteCode created = ledger.create("Podcast", "The pod", 50, InviteCode.DISCOUNT_PERCENT,
                25, from, until, "read out in episode 12").orElseThrow();

        assertThat(created.code()).isEqualTo("Podcast");
        // Instants, not OffsetDateTimes: Postgres hands the value back in the session's offset,
        // and the JVM's default may differ. Same moment is what matters.
        assertThat(created.validFrom().toInstant()).isEqualTo(from.toInstant());
        assertThat(created.validUntil().toInstant()).isEqualTo(until.toInstant());
        assertThat(created.note()).isEqualTo("read out in episode 12");
        assertThat(created.createdAt()).isNotNull();
        assertThat(ledger.create("podcast", "again", 1, InviteCode.ACCESS, null, null, null, null))
                .isEmpty();
    }
}
