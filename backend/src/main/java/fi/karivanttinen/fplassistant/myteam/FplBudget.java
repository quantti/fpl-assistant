package fi.karivanttinen.fplassistant.myteam;

/**
 * How many calls to FPL one chat turn may still make.
 *
 * <p>Created per request and carried in the {@link org.springframework.ai.chat.model.ToolContext}
 * beside the user id. A counter on the singleton service would be shared by every conversation
 * on the machine: one mini-league question would throttle everyone else's chat, and the number
 * would measure nothing anybody asked for.
 *
 * <p>Cache hits cost nothing — that is the whole reason ten opponents fit inside fifteen calls.
 */
public final class FplBudget {

    private final int allowance;
    private int spent;

    public FplBudget(int allowance) {
        this.allowance = allowance;
    }

    /** Takes one call, or answers false when the turn has spent its allowance. */
    public synchronized boolean take() {
        if (spent >= allowance) {
            return false;
        }
        spent++;
        return true;
    }

    public synchronized int spent() {
        return spent;
    }
}
