package dev.quantti.fplassistant.chat;

/**
 * What one answer cost, as the provider reported it. Any field may be null: a provider that does
 * not report a number is recorded as not having reported it, never as zero — a stored zero is
 * indistinguishable from a real measurement and would quietly poison the averages the quota is
 * meant to be tuned from.
 *
 * <p>{@code cachedTokens} is the prompt-cache read count. The pricing in the Phase 5 spec assumes
 * caching applies; Task 8 checks this column to find out whether it does.
 */
public record TokenUsage(Integer inputTokens, Integer cachedTokens, Integer outputTokens) {}
