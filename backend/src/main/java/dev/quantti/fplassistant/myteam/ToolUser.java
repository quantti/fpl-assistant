package dev.quantti.fplassistant.myteam;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * How a tool learns whose account is asking — and the one way it is allowed to.
 *
 * <p><b>The user id travels in the {@link ToolContext}, never as a tool parameter.</b> Everything
 * in a tool's schema is filled in by the model, and the model's input is the user's own text:
 * a {@code UUID userId} parameter is a working request for someone else's squad as soon as
 * someone types one. Spring AI keeps {@code ToolContext} out of the generated schema, so the
 * model cannot see it, cannot set it, and cannot be talked into setting it.
 *
 * <p>{@code SecurityContextHolder} is not an option either: tool calls run on the provider's
 * reactive stream threads, where the request thread's context is gone.
 */
public final class ToolUser {

    private static final Logger log = LoggerFactory.getLogger(ToolUser.class);

    /** Set by {@code ChatService} on every request. */
    public static final String KEY = "fpl.userId";
    /** The per-request FPL call allowance, set beside the user id. */
    public static final String BUDGET = "fpl.budget";

    /** Thrown when the context carries no user. Tools turn it into a sentence, never a guess. */
    public static class NoUserException extends RuntimeException {
        NoUserException() {
            super("no user id in the tool context");
        }
    }

    private ToolUser() {
    }

    public static UUID required(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(KEY);
        if (value instanceof UUID userId) {
            return userId;
        }
        // A wiring mistake, not a user mistake — and the only safe response is to answer nobody.
        log.warn("a my-team tool was called with no user id in its context; refusing");
        throw new NoUserException();
    }

    /**
     * The request's remaining FPL call allowance. A missing budget is a wiring mistake too, but
     * a harmless one to absorb: a fresh allowance still bounds this single call, which is what
     * the cap is for.
     */
    public static FplBudget budget(ToolContext context, int fallbackAllowance) {
        Object value = context == null ? null : context.getContext().get(BUDGET);
        if (value instanceof FplBudget budget) {
            return budget;
        }
        log.warn("a my-team tool was called with no request budget; using a fresh one");
        return new FplBudget(fallbackAllowance);
    }
}
