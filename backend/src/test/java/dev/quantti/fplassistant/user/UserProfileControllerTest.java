package dev.quantti.fplassistant.user;

import java.util.UUID;

import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile endpoints, including the two failures that must stay distinguishable and the one
 * that matters most: one user's team id is not another's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class UserProfileControllerTest {

    private static final String USER_A = "11111111-1111-4111-8111-111111111111";
    private static final String USER_B = "22222222-2222-4222-8222-222222222222";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    FplEntryLookup entries;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    /** Unconditional, so one failing assertion cannot poison the tests that run after it. */
    @AfterEach
    void clearProfiles() {
        for (String user : new String[] {USER_A, USER_B}) {
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", UUID.fromString(user));
        }
    }

    @Test
    void readingTheProfileNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }

    /** The write shape is the one worth checking: it is what changes stored data. */
    @Test
    void writingTheProfileNeedsAToken() throws Exception {
        mockMvc.perform(put("/api/profile/fpl-team-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fplTeamId\":1234567}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/profile/fpl-team-id")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTeamIdRoundTrips() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.EXISTS);

        setTeamId(USER_A, "1234567").andExpect(status().isOk())
                .andExpect(jsonPath("$.fplTeamId").value(1234567));
        readProfile(USER_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.fplTeamId").value(1234567));
    }

    @Test
    void aUserWithNoTeamIdGetsNull() throws Exception {
        readProfile(USER_B).andExpect(status().isOk())
                .andExpect(jsonPath("$.fplTeamId").doesNotExist());
    }

    @Test
    void oneUsersTeamIdIsNotAnothers() throws Exception {
        when(entries.lookup(anyInt())).thenReturn(FplEntryLookup.Result.EXISTS);
        setTeamId(USER_A, "1111111").andExpect(status().isOk());
        setTeamId(USER_B, "2222222").andExpect(status().isOk());

        readProfile(USER_A).andExpect(jsonPath("$.fplTeamId").value(1111111));
        readProfile(USER_B).andExpect(jsonPath("$.fplTeamId").value(2222222));
    }

    @Test
    void anIdFplDoesNotKnowIsRejectedAndNothingIsSaved() throws Exception {
        when(entries.lookup(9999999)).thenReturn(FplEntryLookup.Result.NOT_FOUND);

        setTeamId(USER_A, "9999999")
                .andExpect(status().isBadRequest())
                // The status alone does not tell the user which of the two problems they have.
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "FPL does not know a team with id 9999999")));

        assertThat(storedTeamId(USER_A)).isNull();
    }

    /**
     * The distinction the whole lookup exists for: FPL being down must not read as "your team id
     * is wrong", and must not silently store an unchecked id either.
     */
    @Test
    void fplBeingUnreachableIsItsOwnAnswerAndSavesNothing() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.UNAVAILABLE);

        setTeamId(USER_A, "1234567")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Could not reach FPL")));

        assertThat(storedTeamId(USER_A)).isNull();
    }

    @Test
    void anImpossibleIdIsRejectedWithoutAskingFpl() throws Exception {
        setTeamId(USER_A, "0").andExpect(status().isBadRequest());

        verify(entries, never()).lookup(anyInt());
    }

    /**
     * A missing or misspelled field must not read as "clear it". Clearing is a DELETE, so the
     * destructive path is never the one you reach by sending nothing.
     */
    @Test
    void aBodyWithoutTheFieldIsRejectedRatherThanWipingTheStoredId() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.EXISTS);
        setTeamId(USER_A, "1234567").andExpect(status().isOk());

        mockMvc.perform(put("/api/profile/fpl-team-id")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":42}"))
                .andExpect(status().isBadRequest());

        assertThat(storedTeamId(USER_A)).isEqualTo(1234567);
    }

    @Test
    void deleteClearsTheTeamId() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.EXISTS);
        setTeamId(USER_A, "1234567").andExpect(status().isOk());

        mockMvc.perform(delete("/api/profile/fpl-team-id")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_A)))
                .andExpect(status().isOk());

        assertThat(storedTeamId(USER_A)).isNull();
    }

    private ResultActions setTeamId(String user, String value) throws Exception {
        return mockMvc.perform(put("/api/profile/fpl-team-id")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fplTeamId\":" + value + "}"));
    }

    private ResultActions readProfile(String user) throws Exception {
        return mockMvc.perform(get("/api/profile")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user)));
    }

    private Integer storedTeamId(String user) {
        return jdbc.query("SELECT fpl_team_id FROM " + profileTable + " WHERE user_id = ?",
                rs -> rs.next() ? (Integer) rs.getObject("fpl_team_id") : null,
                UUID.fromString(user));
    }
}
