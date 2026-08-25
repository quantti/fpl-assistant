package fi.karivanttinen.fplassistant.myteam;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
 * The mini-league tools' half of the same rule: a league id is a parameter because it names a
 * league, the account never is. The service still checks membership before it fetches anything —
 * that is {@link MiniLeagueServiceTest}'s job.
 */
class MiniLeagueToolsTest {

    private static final UUID USER = UUID.fromString("9f405162-7777-4777-8777-888888888888");

    private final MiniLeagueService service = mock(MiniLeagueService.class);
    private final MiniLeagueTools tools = new MiniLeagueTools(service, 15);

    @Test
    void theSchemasCarryTheLeagueIdAndTheFocusAndNothingElse() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).hasSize(2);
        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema().toLowerCase();
            assertThat(schema).doesNotContain("userid").doesNotContain("toolcontext");
            if (callback.getToolDefinition().name().equals("getMiniLeagueOpponents")) {
                assertThat(schema).contains("leagueid").contains("focus");
            }
        }
    }

    @Test
    void theFocusIsPassedThroughUntouchedForTheServiceToInterpret() {
        when(service.opponents(eq(USER), eq(12), eq("top50"), any())).thenReturn("clamped");

        String answer = tools.getMiniLeagueOpponents(12, "top50",
                new ToolContext(Map.of(ToolUser.KEY, USER, ToolUser.BUDGET, new FplBudget(15))));

        assertThat(answer).isEqualTo("clamped");
    }

    @Test
    void aCallWithNoUserInItsContextLooksNothingUp() {
        assertThat(tools.getMyMiniLeagues(new ToolContext(Map.of())))
                .contains("could not tell which account is asking");
        verify(service, never()).myLeagues(any(), any());
    }
}
