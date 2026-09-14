package fi.karivanttinen.fplassistant.user;

import java.time.OffsetDateTime;
import java.util.UUID;

import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin API: who may call it, and that what it changes is what sign-up then enforces.
 *
 * <p>Two requests here go to {@code /api/auth/signup}, which is rate limited per IP at 20 a
 * minute and shares that budget with {@code SignupControllerTest} only if the two classes share
 * a context — they do not (this one imports {@code LocalSigningKey}). Keep the sign-up calls in
 * this class in single digits regardless.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
// A context no other class shares (LocalSigningKey plus a mocked SupabaseAdminClient): close it,
// or its connection pool stays cached and the suite exhausts the test database's max_connections
// (KAR-51).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InviteCodeAdminControllerTest {

    /** The id application-test.yml names in fpl.admin.user-ids. */
    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String NOT_ADMIN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String BASE = "/api/admin/invite-codes";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SupabaseAdminClient admin;

    @Value("${fpl.user.invite-code-table-name}")
    String codeTable;

    @Value("${fpl.user.invite-redemption-table-name}")
    String redemptionTable;

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

    private static MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestJwks.validTokenFor(user));
    }

    private ResultActions signUp(String code) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"correct horse battery"
                        + " staple\",\"inviteCode\":\"" + code + "\"}"));
    }

    @Test
    void withoutATokenItIs401AndAsANonAdminItIs403() throws Exception {
        seedCode("launch", 10, 0);
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(as(NOT_ADMIN, get(BASE))).andExpect(status().isForbidden());
        mockMvc.perform(as(NOT_ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"sneaky\",\"maxUses\":1}")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + codeTable, Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theListCarriesCountsAndAServerSideState() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long launch = seedCode("launch", 10, 3);
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(launch, UUID.randomUUID(), now.minusHours(2));
        seedRedemption(launch, null, now.minusHours(1));
        long full = seedCode("full", 1, 1);
        jdbc.update("UPDATE " + codeTable + " SET active = false WHERE code = 'full'");
        seedCode("open", null, 0);

        mockMvc.perform(as(ADMIN, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCap").value(2))
                .andExpect(jsonPath("$.placesTaken").value(3))
                .andExpect(jsonPath("$.burnt").value(1))
                .andExpect(jsonPath("$.codes", hasSize(3)))
                .andExpect(jsonPath("$.codes[0].code").value("launch"))
                .andExpect(jsonPath("$.codes[0].uses").value(3))
                .andExpect(jsonPath("$.codes[0].remaining").value(7))
                .andExpect(jsonPath("$.codes[0].accounts").value(2))
                .andExpect(jsonPath("$.codes[0].burnt").value(1))
                .andExpect(jsonPath("$.codes[0].state").value("OPEN"))
                .andExpect(jsonPath("$.codes[0].lastRedeemedAt").isNotEmpty())
                .andExpect(jsonPath("$.codes[1].id").value(full))
                .andExpect(jsonPath("$.codes[1].state").value("DISABLED"))
                .andExpect(jsonPath("$.codes[2].maxUses").doesNotExist())
                .andExpect(jsonPath("$.codes[2].remaining").doesNotExist())
                .andExpect(jsonPath("$.codes[2].state").value("OPEN"));
    }

    @Test
    void creatingACodeAnswers201AndADuplicateInAnyCaseAnswers409() throws Exception {
        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\" Podcast \",\"maxUses\":50,\"benefitKind\":\"discount_percent\","
                        + "\"benefitValue\":25,\"validUntil\":\"2027-06-01T00:00:00Z\","
                        + "\"note\":\"episode 12\"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/admin/invite-codes/\\d+")))
                .andExpect(jsonPath("$.code").value("Podcast"))
                .andExpect(jsonPath("$.label").value("Podcast"))
                .andExpect(jsonPath("$.maxUses").value(50))
                .andExpect(jsonPath("$.remaining").value(50))
                .andExpect(jsonPath("$.benefitKind").value("discount_percent"))
                .andExpect(jsonPath("$.benefitValue").value(25))
                .andExpect(jsonPath("$.note").value("episode 12"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.accounts").value(0));

        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"podcast\",\"maxUses\":1}")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + codeTable, Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anInvalidCodeRequestIs400AndWritesNothing() throws Exception {
        String[] bodies = {
                "{\"code\":\"  \",\"maxUses\":1}",
                "{\"code\":\"x\",\"maxUses\":0}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"discount_percent\"}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"discount_percent\",\"benefitValue\":101}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"free_forever\"}",
                "{\"code\":\"x\",\"maxUses\":1,\"validFrom\":\"2027-01-02T00:00:00Z\","
                        + "\"validUntil\":\"2027-01-01T00:00:00Z\"}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"access\",\"benefitValue\":10}",
                "{\"code\":\"has space\",\"maxUses\":1}",
                "{\"code\":\"" + "a".repeat(65) + "\",\"maxUses\":1}",
        };
        for (String body : bodies) {
            mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + codeTable, Integer.class)).isZero();
    }

    /** The requirement: switched off in one call, and sign-up refuses it on the next attempt. */
    @Test
    void disablingACodeStopsSignUpAndEnablingRestoresIt() throws Exception {
        when(admin.configured()).thenReturn(true);
        long id = seedCode("launch", 10, 0);

        mockMvc.perform(as(ADMIN, post(BASE + "/" + id + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.state").value("DISABLED"));
        signUp("launch").andExpect(status().isForbidden());
        verify(admin, never()).createUser(any(), any());

        mockMvc.perform(as(ADMIN, post(BASE + "/" + id + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.state").value("OPEN"));
        // Refused for the configured-but-unavailable Supabase, not for the code: the peek passed.
        when(admin.createUser(any(), any())).thenReturn(new SupabaseAdminClient.Unavailable());
        signUp("launch").andExpect(status().isServiceUnavailable());

        mockMvc.perform(as(ADMIN, post(BASE + "/999999/disable"))).andExpect(status().isNotFound());
    }

    @Test
    void theCeilingIsSetRemovedAndRefusedBelowWhatIsSpent() throws Exception {
        long id = seedCode("launch", 10, 4);

        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":4}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUses").value(4))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.state").value("EXHAUSTED"));
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":3}")))
                .andExpect(status().isConflict());
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":0}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN, delete(BASE + "/" + id + "/max-uses")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andExpect(jsonPath("$.state").value("OPEN"));
        mockMvc.perform(as(ADMIN, delete(BASE + "/999999/max-uses"))).andExpect(status().isNotFound());
    }

    @Test
    void redemptionsAreTheCodesAttributionNewestFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        UUID user = UUID.randomUUID();
        seedRedemption(id, user, now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));

        mockMvc.perform(as(ADMIN, get(BASE + "/" + id + "/redemptions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].userId").value(user.toString()));
        mockMvc.perform(as(ADMIN, get(BASE + "/999999/redemptions"))).andExpect(status().isNotFound());
    }

    @Test
    void releasingBurntPlacesReportsWhatItDid() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        seedRedemption(id, null, now.minusHours(1));
        seedRedemption(id, null, now.minusMinutes(1));

        mockMvc.perform(as(ADMIN, post(BASE + "/release-burnt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(1))
                .andExpect(jsonPath("$.reconciled[0]").value("launch"));
        assertThat(jdbc.queryForObject("SELECT uses FROM " + codeTable + " WHERE id = ?",
                Integer.class, id)).isEqualTo(1);
    }
}
