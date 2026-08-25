package dev.quantti.fplassistant.myteam;

import java.util.UUID;

import dev.quantti.fplassistant.tools.FplTool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The squad-aware half of the advice tools.
 *
 * <p>No parameter identifies the account: the user id arrives in the {@link ToolContext}, which
 * Spring AI keeps out of the schema the model sees. See {@link ToolUser} for why that is the only
 * acceptable arrangement.
 */
@Component
public class MyTeamTools implements FplTool {

    private final MyTeamService myTeam;
    private final int allowance;

    public MyTeamTools(MyTeamService myTeam,
                       @Value("${fpl.myteam.max-requests-per-turn}") int allowance) {
        this.myTeam = myTeam;
        this.allowance = allowance;
    }

    @Tool(description = """
            Get the user's own FPL squad as it was locked at the last deadline: the starting XI
            and bench with captain and vice, bank, squad value, chips played and still playable,
            and each player's price, form, points, ownership, availability and fixture
            difficulty. Use it before advising on transfers, captaincy, chip timing or squad
            structure. Takes no arguments - the account is known from the session. If it says no
            team id is stored, tell the user to save it in their profile rather than guessing at
            their squad. Transfers made after the last deadline are not visible to this tool.
            """)
    public String getMyTeam(ToolContext context) {
        try {
            UUID userId = ToolUser.required(context);
            return myTeam.render(myTeam.myTeam(userId, ToolUser.budget(context, allowance)));
        } catch (ToolUser.NoUserException e) {
            return "This tool could not tell which account is asking, so it looked nothing up."
                    + " Answer generally and do not claim to know the user's squad.";
        }
    }
}
