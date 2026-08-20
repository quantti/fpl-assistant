package fi.karivanttinen.fplassistant.chat;

/**
 * What one answer cost, as the provider reported it. Any field may be null: a provider that does
 * not report a number is recorded as not having reported it, never as zero — a stored zero is
 * indistinguishable from a real measurement and would quietly poison the averages the quota is
 * meant to be tuned from.
 *
 * <p>{@code cachedTokens} is the prompt-cache read count and {@code cacheWriteTokens} what it
 * cost to put them there. Both are needed to price a turn: Anthropic reports {@code inputTokens}
 * as the uncached remainder, bills reads at about a tenth of the input rate and writes at a
 * premium, so a cost computed from input and output alone is always an underestimate. The
 * pricing in the Phase 5 spec assumes caching applies; Task 8 checks these columns to find out
 * whether it does.
 */
public record TokenUsage(String model, Integer inputTokens, Integer cachedTokens,
                        Integer cacheWriteTokens, Integer outputTokens) {

    /**
     * Whether any count was actually reported. A record can carry a model name and nothing else —
     * providers stamp the model on chunks that measure nothing — and treating that as a
     * measurement would charge a user for a turn that produced no tokens at all.
     */
    public boolean measured() {
        return inputTokens != null || cachedTokens != null || cacheWriteTokens != null
                || outputTokens != null;
    }
}
