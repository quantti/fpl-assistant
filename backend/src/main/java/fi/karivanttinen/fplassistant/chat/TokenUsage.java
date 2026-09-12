package fi.karivanttinen.fplassistant.chat;

/**
 * The sum of usage the provider reported for one user turn's model invocations. Any field may be
 * null: an invocation that does not report a number contributes nothing known, never a fabricated
 * zero. A stored zero is an explicit provider measurement.
 *
 * <p>{@code cachedTokens} is the prompt-cache read count and {@code cacheWriteTokens} the cache
 * write count. {@code inputTokens} is normalized to the uncached remainder before invocation
 * snapshots are summed, so cache reads remain separate on every provider. Counts cover mapped
 * Spring AI responses only; SDK retries or failures that emit no usage remain unknown.
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
