package dev.quantti.fplassistant.user;

import java.util.UUID;

import dev.quantti.fplassistant.config.SecurityConfig;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile endpoints, including the two failures that must stay distinguishable and the one
 * that matters most: one user's team id is not another's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileControllerTest {

    private static final String USER_A = "11111111-1111-4111-8111-111111111111";
    private static final String USER_B = "22222222-2222-4222-8222-222222222222";

    @TestConfiguration
    static class LocalSigningKey {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return SecurityConfig.decoder(TestJwks.jwksUri(), TestJwks.ISSUER, TestJwks.AUDIENCE);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    FplEntryLookup entries;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    @Test
    void theProfileNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTeamIdRoundTrips() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.EXISTS);

        setTeamId(USER_A, "1234567").andExpect(status().isOk())
                .andExpect(jsonPath("$.fplTeamId").value(1234567));
        readProfile(USER_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.fplTeamId").value(1234567));

        cleanUp(USER_A);
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

        cleanUp(USER_A);
        cleanUp(USER_B);
    }

    @Test
    void anIdFplDoesNotKnowIsRejectedAndNothingIsSaved() throws Exception {
        when(entries.lookup(9999999)).thenReturn(FplEntryLookup.Result.NOT_FOUND);

        setTeamId(USER_A, "9999999").andExpect(status().isBadRequest());

        assertThat(storedTeamId(USER_A)).isNull();
    }

    /**
     * The distinction the whole lookup exists for: FPL being down must not read as "your team id
     * is wrong", and must not silently store an unchecked id either.
     */
    @Test
    void fplBeingUnreachableIsItsOwnAnswerAndSavesNothing() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.UNAVAILABLE);

        setTeamId(USER_A, "1234567").andExpect(status().isServiceUnavailable());

        assertThat(storedTeamId(USER_A)).isNull();
    }

    @Test
    void anImpossibleIdIsRejectedWithoutAskingFpl() throws Exception {
        setTeamId(USER_A, "0").andExpect(status().isBadRequest());

        verify(entries, never()).lookup(anyInt());
    }

    @Test
    void nullClearsTheTeamId() throws Exception {
        when(entries.lookup(1234567)).thenReturn(FplEntryLookup.Result.EXISTS);
        setTeamId(USER_A, "1234567").andExpect(status().isOk());

        setTeamId(USER_A, "null").andExpect(status().isOk());

        assertThat(storedTeamId(USER_A)).isNull();
        cleanUp(USER_A);
    }

    private org.springframework.test.web.servlet.ResultActions setTeamId(String user, String value)
            throws Exception {
        return mockMvc.perform(put("/api/profile/fpl-team-id")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fplTeamId\":" + value + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions readProfile(String user)
            throws Exception {
        return mockMvc.perform(get("/api/profile")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user)));
    }

    private Integer storedTeamId(String user) {
        return jdbc.query("SELECT fpl_team_id FROM " + profileTable + " WHERE user_id = ?",
                rs -> rs.next() ? (Integer) rs.getObject("fpl_team_id") : null, UUID.fromString(user));
    }

    private void cleanUp(String user) {
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", UUID.fromString(user));
    }
}
