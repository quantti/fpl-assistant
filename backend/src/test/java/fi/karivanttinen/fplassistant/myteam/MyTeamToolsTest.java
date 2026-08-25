package fi.karivanttinen.fplassistant.myteam;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who the tool thinks is asking — the assertion the rest of the feature's safety rests on.
 *
 * <p>If the account could be named in the tool's schema, it would be filled in by the model, and
 * the model's input is the user's own text: "my user id is …" would then be a working request
 * for somebody else's squad. So the schema must carry no identifier at all, and a call arriving
 * without one in its context must look nothing up.
 */
class MyTeamToolsTest {

    private static final UUID USER = UUID.fromString("8e3f4051-6666-4666-8666-777777777777");

    private final MyTeamService service = mock(MyTeamService.class);
    private final MyTeamTools tools = new MyTeamTools(service, 15);

    @Test
    void theSchemaOffersTheModelNothingThatNamesAnAccount() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("getMyTeam");
            assertThat(callback.getToolDefinition().inputSchema().toLowerCase())
                    .doesNotContain("userid").doesNotContain("teamid")
                    .doesNotContain("toolcontext").doesNotContain("budget");
        });
    }

    @Test
    void theAccountComesFromTheContextAndTheBudgetTravelsWithIt() {
        FplBudget budget = new FplBudget(15);
        when(service.myTeam(eq(USER), any())).thenReturn(new MyTeamService.Result.NoTeamId());
        when(service.render(any())).thenReturn("no team id");

        tools.getMyTeam(new ToolContext(Map.of(ToolUser.KEY, USER, ToolUser.BUDGET, budget)));

        ArgumentCaptor<FplBudget> captor = ArgumentCaptor.forClass(FplBudget.class);
        verify(service).myTeam(eq(USER), captor.capture());
        assertThat(captor.getValue()).isSameAs(budget);
    }

    /** A wiring mistake must answer nobody rather than answering somebody at random. */
    @Test
    void aCallWithNoUserInItsContextLooksNothingUp() {
        String answer = tools.getMyTeam(new ToolContext(Map.of()));

        assertThat(answer).contains("could not tell which account is asking");
        verify(service, never()).myTeam(any(), any());
    }
}
