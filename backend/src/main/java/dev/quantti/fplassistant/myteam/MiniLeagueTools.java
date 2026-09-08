package dev.quantti.fplassistant.myteam;

import java.util.UUID;

import dev.quantti.fplassistant.tools.FplTool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mini-league questions. {@code leagueId} is a parameter because it names a league, not an
 * account — and the service still checks that the authenticated user is in that league before it
 * fetches anything. The account itself comes from the {@link ToolContext}; see {@link ToolUser}.
 */
@Component
public class MiniLeagueTools implements FplTool {

    private final MiniLeagueService miniLeagues;
    private final int allowance;

    public MiniLeagueTools(MiniLeagueService miniLeagues,
                           @Value("${fpl.myteam.max-requests-per-turn}") int allowance) {
        this.miniLeagues = miniLeagues;
        this.allowance = allowance;
    }

    @Tool(description = """
            List the user's FPL mini-leagues - the ones somebody created and invited people to -
            with their rank in each and the league id to pass to getMiniLeagueOpponents. Use it
            when the user asks about a mini-league without naming which one. Takes no arguments.
            """)
    public String getMyMiniLeagues(ToolContext context) {
        try {
            UUID userId = ToolUser.required(context);
            return miniLeagues.myLeagues(userId, ToolUser.budget(context, allowance));
        } catch (ToolUser.NoUserException e) {
            return refusal();
        }
    }

    @Tool(description = """
            Get teams from one of the user's mini-leagues, with the players each owns that the
            user does not - the part that changes advice. focus="nearby" (default) returns the
            managers ranked just above and below the user, focus="top10" the league leaders (any
            N, capped at ten teams), and focus="entry:12345" one specific team. Squads are the
            ones locked at the last deadline. If either manager played a Free Hit, the reply
            separates that gameweek's scoring-pick differences from continuing-squad differences
            for future advice, or warns when the continuing squad cannot be verified. Transfers
            since the deadline are not visible. Only call this when the user brings up a mini-league.
            """)
    public String getMiniLeagueOpponents(int leagueId,
                                         @ToolParam(required = false) String focus,
                                         ToolContext context) {
        try {
            UUID userId = ToolUser.required(context);
            return miniLeagues.opponents(userId, leagueId, focus,
                    ToolUser.budget(context, allowance));
        } catch (ToolUser.NoUserException e) {
            return refusal();
        }
    }

    private static String refusal() {
        return "This tool could not tell which account is asking, so it looked nothing up."
                + " Answer generally and do not claim to know the user's leagues.";
    }
}
