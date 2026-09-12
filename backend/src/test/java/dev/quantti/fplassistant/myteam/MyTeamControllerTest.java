package dev.quantti.fplassistant.myteam;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.quantti.fplassistant.myteam.FplEntryClient.EntrySummary;
import dev.quantti.fplassistant.myteam.FplEntryClient.LockedSquad;
import dev.quantti.fplassistant.config.SecurityConfig;
import dev.quantti.fplassistant.myteam.FplEntryClient.Pick;
import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import dev.quantti.fplassistant.tools.SeasonContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MyTeamController.class,
        properties = "fpl.myteam.max-requests-per-turn=7")
@ActiveProfiles("test")
@Import({SecurityConfig.class, LocalSigningKey.class})
class MyTeamControllerTest {

    private static final String USER_A = "11111111-1111-4111-8111-111111111111";
    private static final String USER_B = "22222222-2222-4222-8222-222222222222";
    private static final AtomicInteger IP = new AtomicInteger(10);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MyTeamService myTeam;

    @Value("${fpl.myteam.max-requests-per-turn}")
    int configuredAllowance;

    @Test
    void aTokenIsRequiredAndMustBeValid() throws Exception {
        mockMvc.perform(get("/api/my-team").header("Fly-Client-IP", nextIp()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/my-team")
                        .header("Fly-Client-IP", nextIp())
                        .header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(myTeam);
    }

    @Test
    void aVerifiedTokenStillNeedsAUuidSubject() throws Exception {
        mockMvc.perform(authenticated("not-a-uuid"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(myTeam);
    }

    @Test
    void aReadySquadHasThePurposeBuiltJsonAndIsNeverBrowserCached() throws Exception {
        when(myTeam.myTeam(any(), any())).thenReturn(readySquad());

        mockMvc.perform(authenticated(USER_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.entry.id").value(123))
                .andExpect(jsonPath("$.entry.name").value("Deadline FC"))
                .andExpect(jsonPath("$.lockedGameweek").value(5))
                .andExpect(jsonPath("$.deadline").value("2026-09-05T10:00:00Z"))
                .andExpect(jsonPath("$.loadedAt").value("2026-09-08T12:34:56Z"))
                .andExpect(jsonPath("$.lockedSquad.bank").value(12))
                .andExpect(jsonPath("$.lockedSquad.value").value(1003))
                .andExpect(jsonPath("$.lockedSquad.activeChip").value("freehit"))
                .andExpect(jsonPath("$.lockedSquad.players[0].name").value("Salah"))
                .andExpect(jsonPath("$.chips.known").value(true))
                .andExpect(jsonPath("$.chips.gameweek").value(6))
                .andExpect(jsonPath("$.continuing.status").value("available"))
                .andExpect(jsonPath("$.continuing.sourceGameweek").value(4))
                .andExpect(jsonPath("$.continuing.bank").value(5))
                .andExpect(jsonPath("$.continuing.value").value(995))
                .andExpect(jsonPath("$.continuing.players[0].name").value("Wood"))
                .andExpect(jsonPath("$.entry.managerName").doesNotExist())
                .andExpect(jsonPath("$.enrichment").doesNotExist())
                .andExpect(jsonPath("$.lockedSquad.players[0].depth").doesNotExist())
                .andExpect(jsonPath("$.continuing.players[0].depth").doesNotExist())
                .andExpect(jsonPath("$.gameweekPoints.gameweek").value(5))
                .andExpect(jsonPath("$.gameweekPoints.status").value("finished_unchecked"))
                .andExpect(jsonPath("$.gameweekPoints.entryTotal").value(55))
                .andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints").value(8))
                .andExpect(jsonPath("$.continuing.players[0].gameweekPoints")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void aPartialReadySquadPreservesNullFieldsAndOmitsUnknownContinuingFunds() throws Exception {
        MyTeamService.Result.Squad full = readySquad();
        Pick pick = new Pick(999, 12, 0, false, true, 1);
        MyTeamService.Result.Squad partial = new MyTeamService.Result.Squad(
                full.entry(), full.event(), Optional.empty(),
                new LockedSquad("freehit", 12, 1003, 55, List.of(pick)),
                List.of(new MyTeamService.SquadPlayer(pick, null, null)), full.chipEvent(),
                List.of(), List.of(), null, false, full.enrichment(),
                new MyTeamService.Continuing.Unknown("FPL publishes no previous picks"),
                full.fetchedAt(), null, full.eventStatusAsOf(), full.entryPointsAsOf(), null);
        when(myTeam.myTeam(any(), any())).thenReturn(partial);

        mockMvc.perform(authenticated(USER_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.deadline").hasJsonPath())
                .andExpect(jsonPath("$.deadline").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.chips.known").value(false))
                .andExpect(jsonPath("$.lockedSquad.players[0].name").value("Element 999"))
                .andExpect(jsonPath("$.lockedSquad.players[0].viceCaptain").value(true))
                .andExpect(jsonPath("$.lockedSquad.players[0].marketPrice").hasJsonPath())
                .andExpect(jsonPath("$.lockedSquad.players[0].marketPrice")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.continuing.status").value("unknown"))
                .andExpect(jsonPath("$.continuing.reason").value("FPL publishes no previous picks"))
                .andExpect(jsonPath("$.continuing.bank").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.continuing.value").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.continuing.players").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.gameweekPoints.status").value("unknown"))
                .andExpect(jsonPath("$.gameweekPoints.playersAsOf").hasJsonPath())
                .andExpect(jsonPath("$.gameweekPoints.playersAsOf")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints").hasJsonPath())
                .andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void noTeamIdIsASetupStateNotAnHttpError() throws Exception {
        assertState(new MyTeamService.Result.NoTeamId(), "no_team_id", 200);
    }

    @Test
    void aMissingStoredTeamIsASetupStateNotAnHttpError() throws Exception {
        assertState(new MyTeamService.Result.TeamNotFound(123), "team_not_found", 200);
    }

    @Test
    void noPublishedSquadYetIsAnEmptyStateNotAnHttpError() throws Exception {
        assertState(new MyTeamService.Result.NoSquadYet(123), "no_squad_yet", 200);
    }

    @Test
    void anFplOutageIsAServiceFailureWithoutUpstreamDetails() throws Exception {
        assertState(new MyTeamService.Result.Unavailable(), "unavailable", 503);
    }

    @Test
    void identityComesOnlyFromEachTokenAndEveryCallGetsANewFullBudget() throws Exception {
        when(myTeam.myTeam(any(), any())).thenReturn(new MyTeamService.Result.NoTeamId());

        mockMvc.perform(authenticated(USER_A)
                        .queryParam("userId", USER_B).queryParam("teamId", "999999"))
                .andExpect(status().isOk());
        mockMvc.perform(authenticated(USER_B)
                        .queryParam("userId", USER_A).queryParam("teamId", "111111"))
                .andExpect(status().isOk());

        ArgumentCaptor<UUID> users = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<FplBudget> budgets = ArgumentCaptor.forClass(FplBudget.class);
        verify(myTeam, org.mockito.Mockito.times(2)).myTeam(users.capture(), budgets.capture());
        assertThat(users.getAllValues()).containsExactly(UUID.fromString(USER_A),
                UUID.fromString(USER_B));
        assertThat(budgets.getAllValues().get(0)).isNotSameAs(budgets.getAllValues().get(1));
        for (FplBudget budget : budgets.getAllValues()) {
            int granted = 0;
            while (budget.take()) {
                granted++;
            }
            assertThat(granted).isEqualTo(configuredAllowance);
        }
    }

    private void assertState(MyTeamService.Result result, String expectedStatus,
                             int expectedHttpStatus) throws Exception {
        when(myTeam.myTeam(any(), any())).thenReturn(result);

        mockMvc.perform(authenticated(USER_A))
                .andExpect(status().is(expectedHttpStatus))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"status\":\"" + expectedStatus + "\"}"));
    }

    private static MockHttpServletRequestBuilder authenticated(String subject) {
        return get("/api/my-team")
                .header("Fly-Client-IP", nextIp())
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(subject));
    }

    private static String nextIp() {
        return "192.0.2." + IP.getAndIncrement();
    }

    private static MyTeamService.Result.Squad readySquad() {
        Pick freeHitPick = new Pick(302, 1, 2, true, false, 3);
        Pick continuingPick = new Pick(44, 1, 1, false, false, 4);
        LockedSquad freeHit = new LockedSquad("freehit", 12, 1003, 55,
                List.of(freeHitPick));
        LockedSquad continuing = new LockedSquad(null, 5, 995, 48,
                List.of(continuingPick));
        // Available, dated enrichment on both squads: the page must still carry none of it.
        Instant written = Instant.parse("2026-09-01T07:00:00Z");
        dev.quantti.fplassistant.tools.DataAge.Span writes =
                new dev.quantti.fplassistant.tools.DataAge.Span(written, written, 1, 0);
        SquadEnrichment.Depth depth = new SquadEnrichment.Depth(3.0,
                new java.math.BigDecimal("3.40"), new java.math.BigDecimal("1.20"), writes, writes);
        MyTeamService.SquadPlayer salah = new MyTeamService.SquadPlayer(freeHitPick,
                new FplBootstrap.Element(302, "Salah", 14, "LIV", 3, 126, "8.5", 12,
                        "12.0", "42.1", "a", "", null), depth);
        MyTeamService.SquadPlayer wood = new MyTeamService.SquadPlayer(continuingPick,
                new FplBootstrap.Element(44, "Wood", 4, "NFO", 4, 71, "5.0", 18,
                        "6.0", "25.0", "a", "", null), depth);
        SquadEnrichment.Enrichment enrichment = new SquadEnrichment.Enrichment(true,
                new SeasonContext(1, "2026-27", true, "2026-27",
                        LocalDate.of(2026, 8, 1)), Map.of(302, depth, 44, depth),
                new SquadEnrichment.StartingGameweek(5, writes));
        return new MyTeamService.Result.Squad(
                new EntrySummary(123, "Deadline FC", "Private Manager", 42, 100, 5,
                        List.of()), 5, Optional.of(Instant.parse("2026-09-05T10:00:00Z")),
                freeHit, List.of(salah), 6, List.of("bench boost"), List.of("wildcard"),
                20, true, enrichment,
                new MyTeamService.Continuing.Squad(4, continuing, List.of(wood)),
                Instant.parse("2026-09-08T12:34:56Z"), new FplBootstrap.EventStatus(true, false),
                Instant.parse("2026-09-08T12:34:56Z"),
                Instant.parse("2026-09-08T12:34:56Z"),
                new FplLivePoints.Live(5, Instant.parse("2026-09-08T12:34:56Z"),
                        Map.of(302, new FplLivePoints.ElementPoints(8, 90))));
    }
}
