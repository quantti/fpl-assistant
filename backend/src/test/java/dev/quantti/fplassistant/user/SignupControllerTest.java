package dev.quantti.fplassistant.user;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate: an invite code checked where the client cannot reach it, and a hard cap on how many
 * accounts that code can create.
 *
 * <p>The test profile sets the code to {@code test-invite-code} and the cap to 2, so the cap is
 * two rows away rather than thirty.
 *
 * <p><b>Every request here spends per-IP rate-limit budget</b> — sign-up is one of the limited
 * paths, MockMvc presents one address for all of them, and the window is 20 a minute. This class
 * sits at nine. A tenth test is fine; a class that grows past the limit starts failing whichever
 * test happens to run last, for a reason that has nothing to do with what it asserts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupControllerTest {

    private static final String CODE = "test-invite-code";

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SupabaseAdminClient admin;

    @Value("${fpl.user.invite-redemption-table-name}")
    String inviteTable;

    @Value("${fpl.user.invite-code-table-name}")
    String codeTable;

    /** A code has to exist for anyone to sign up at all: an empty table refuses everyone. */
    @BeforeEach
    void issueACode() {
        jdbc.update("DELETE FROM " + inviteTable);
        jdbc.update("DELETE FROM " + codeTable);
        jdbc.update("INSERT INTO " + codeTable + " (code, label, max_uses) VALUES (?, ?, ?)",
                CODE, "test", 5);
    }

    /** Unconditional: a leftover reservation would put the next test at the cap. */
    @AfterEach
    void emptyTheLedger() {
        jdbc.update("DELETE FROM " + inviteTable);
        jdbc.update("DELETE FROM " + codeTable);
    }

    private long codeId() {
        return jdbc.queryForObject("SELECT id FROM " + codeTable + " WHERE code = ?",
                Long.class, CODE);
    }

    private int uses(String code) {
        return jdbc.queryForObject("SELECT uses FROM " + codeTable + " WHERE code = ?",
                Integer.class, code);
    }

    /**
     * The assertion that matters is that no call happens — a wrong code that still created an
     * account and then deleted it would pass a status check and fail the point of the gate.
     */
    @Test
    void aWrongCodeIsRefusedAndSupabaseIsNeverAsked() throws Exception {
        signUp("a@example.com", PASSWORD, "not-the-code").andExpect(status().isForbidden());

        verify(admin, never()).createUser(any(), any());
        assertThat(ledgerRows()).isZero();
    }

    @Test
    void theHappyPathCreatesTheAccountAndWritesExactlyOneLedgerRow() throws Exception {
        UUID created = UUID.randomUUID();
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(eq("a@example.com"), eq(PASSWORD)))
                .thenReturn(new SupabaseAdminClient.Created(created));

        signUp("a@example.com", PASSWORD, CODE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(created.toString()));

        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM " + inviteTable, UUID.class))
                .isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT code_id FROM " + inviteTable, Long.class))
                .as("which code the account came in on, so it stays traceable afterwards")
                .isEqualTo(codeId());
        assertThat(uses(CODE))
                .as("the ceiling is checked against a counter, so it has to move")
                .isEqualTo(1);
    }

    /** The frontend must not read a bare 201 as "you are signed in" — that was the old bug. */
    @Test
    void theResponseSaysTheAddressStillNeedsVerifying() throws Exception {
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(any(), any()))
                .thenReturn(new SupabaseAdminClient.Created(UUID.randomUUID()));

        signUp("a@example.com", PASSWORD, CODE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationRequired").value(true));
    }

    /**
     * The point of per-code ceilings: one code runs out without touching any other. A shared
     * secret could only be revoked for everyone at once.
     */
    @Test
    void aCodeThatIsUsedUpIsRefusedWhileAnotherCodeStillWorks() throws Exception {
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(any(), any()))
                .thenReturn(new SupabaseAdminClient.Created(UUID.randomUUID()));
        jdbc.update("INSERT INTO " + codeTable + " (code, label, max_uses, uses) VALUES"
                + " (?, ?, ?, ?)", "spent", "spent", 1, 1);
        jdbc.update("INSERT INTO " + codeTable + " (code, label, max_uses) VALUES (?, ?, ?)",
                "roomy", "roomy", 20);

        signUp("a@example.com", PASSWORD, "spent").andExpect(status().isForbidden());
        verify(admin, never()).createUser(any(), any());

        signUp("b@example.com", PASSWORD, "roomy").andExpect(status().isCreated());
    }

    /** An expired code says so, rather than "not valid" — different problem, different fix. */
    @Test
    void anExpiredCodeIsRefusedWithItsOwnReason() throws Exception {
        when(admin.configured()).thenReturn(true);
        jdbc.update("INSERT INTO " + codeTable
                + " (code, label, max_uses, valid_until) VALUES (?, ?, ?, NOW() - INTERVAL '1 day')",
                "lapsed", "lapsed", 10);

        signUp("a@example.com", PASSWORD, "lapsed")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("That invite code has expired."));
        verify(admin, never()).createUser(any(), any());
    }

    /** A code deactivated by hand stops working without being deleted or losing its history. */
    @Test
    void anInactiveCodeIsRefused() throws Exception {
        when(admin.configured()).thenReturn(true);
        jdbc.update("UPDATE " + codeTable + " SET active = false WHERE code = ?", CODE);

        signUp("a@example.com", PASSWORD, CODE).andExpect(status().isForbidden());
        verify(admin, never()).createUser(any(), any());
    }

    /** Codes are matched case-insensitively: they are read aloud and typed by hand. */
    @Test
    void aCodeMatchesWhateverCaseItIsTypedIn() throws Exception {
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(any(), any()))
                .thenReturn(new SupabaseAdminClient.Created(UUID.randomUUID()));

        signUp("a@example.com", PASSWORD, CODE.toUpperCase()).andExpect(status().isCreated());
    }

    /** The benefit is recorded at redemption so the billing phase can read it later. */
    @Test
    void aDiscountCodeRecordsWhatItIsWorth() throws Exception {
        when(admin.configured()).thenReturn(true);
        UUID user = UUID.randomUUID();
        when(admin.createUser(any(), any())).thenReturn(new SupabaseAdminClient.Created(user));
        jdbc.update("INSERT INTO " + codeTable + " (code, label, max_uses, benefit_kind,"
                + " benefit_value) VALUES (?, ?, ?, ?, ?)", "half", "half", 10,
                InviteCode.DISCOUNT_PERCENT, 50);

        signUp("a@example.com", PASSWORD, "half").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                SELECT c.benefit_value FROM %s c JOIN %s r ON r.code_id = c.id
                WHERE r.user_id = ?
                """.formatted(codeTable, inviteTable), Integer.class, user)).isEqualTo(50);
    }

    @Test
    void atTheCapSignUpIsRefusedAndNoAccountIsCreated() throws Exception {
        when(admin.configured()).thenReturn(true);
        jdbc.update("INSERT INTO " + inviteTable + " (user_id) VALUES (?), (?)",
                UUID.randomUUID(), UUID.randomUUID());

        signUp("a@example.com", PASSWORD, CODE).andExpect(status().isConflict());

        verify(admin, never()).createUser(any(), any());
        assertThat(ledgerRows()).isEqualTo(2);
    }

    /**
     * The deliberate failure direction. One transaction cannot span Postgres and Supabase, so the
     * slot is reserved and committed first: a refusal burns it. A burnt slot is a visible row
     * that can be released by hand — counting after the fact would instead lose the account from
     * the cap entirely, and the cap would drift upward with nobody told.
     */
    @Test
    void aSupabaseRejectionLeavesTheReservedSlotBehind() throws Exception {
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(any(), any())).thenReturn(
                new SupabaseAdminClient.Rejected("Password should be at least 6 characters"));

        signUp("a@example.com", "short", CODE)
                .andExpect(status().isUnprocessableEntity())
                // Supabase's own words reach the form, in the "detail" field of the problem
                // response Spring writes. The sign-up form reads exactly that field, so this
                // pins the shape as much as the text.
                .andExpect(jsonPath("$.detail").value("Password should be at least 6 characters"));

        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + inviteTable + " WHERE user_id IS NULL", Integer.class))
                .isEqualTo(1);
    }

    /** "Could not ask" is not "the answer is no": the user is told to try again, not to retype. */
    @Test
    void anUnreachableSupabaseIsAServiceProblemRatherThanARejection() throws Exception {
        when(admin.configured()).thenReturn(true);
        when(admin.createUser(any(), any())).thenReturn(new SupabaseAdminClient.Unavailable());

        signUp("a@example.com", PASSWORD, CODE).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aDeployWithNoServiceRoleKeyRefusesBeforeItBurnsASlot() throws Exception {
        when(admin.configured()).thenReturn(false);

        signUp("a@example.com", PASSWORD, CODE).andExpect(status().isServiceUnavailable());

        assertThat(ledgerRows()).isZero();
    }

    @Test
    void anEmptyAddressOrPasswordIsABadRequestAndCostsNothing() throws Exception {
        when(admin.configured()).thenReturn(true);

        signUp("", PASSWORD, CODE).andExpect(status().isBadRequest());
        signUp("a@example.com", "", CODE).andExpect(status().isBadRequest());

        verify(admin, never()).createUser(any(), any());
        assertThat(ledgerRows()).isZero();
    }

    /**
     * There is no token at sign-up and there cannot be one, so the endpoint has to be reachable
     * without it — refused on the code, never on the absence of authentication.
     */
    @Test
    void itIsReachableWithoutAToken() throws Exception {
        signUp("a@example.com", PASSWORD, "not-the-code").andExpect(status().isForbidden());
    }

    private ResultActions signUp(String email, String password, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "inviteCode": "%s"}
                        """.formatted(email, password, code)));
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM " + inviteTable, Integer.class);
    }
}
