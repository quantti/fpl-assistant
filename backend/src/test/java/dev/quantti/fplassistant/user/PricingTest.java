package dev.quantti.fplassistant.user;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic, spelled out. Cached and written tokens are not billed at the input rate, and a
 * cost that pretends they are is wrong in both directions at once — too low for a cache write,
 * too high for a cache read.
 */
class PricingTest {

    private static final Pricing.Rates ANTHROPIC = new Pricing.Rates(
            "claude-sonnet-5", new BigDecimal("3.00"), new BigDecimal("15.00"),
            new BigDecimal("0.1"), new BigDecimal("1.25"), false);

    /** A provider that folds cached tokens into its prompt count, as OpenAI does. */
    private static final Pricing.Rates INCLUSIVE = new Pricing.Rates(
            "some-model", new BigDecimal("3.00"), new BigDecimal("15.00"),
            new BigDecimal("0.1"), new BigDecimal("1.25"), true);

    private static final Pricing.Rates UNPRICED = new Pricing.Rates(
            "unpriced", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, true);

    private static Pricing pricing(Pricing.Rates anthropic, Pricing.Rates openai) {
        return new Pricing(new Pricing.Configured(anthropic, openai));
    }

    @Test
    void eachKindOfTokenIsBilledAtItsOwnRate() {
        // 1000 × $3/M + 2000 × $0.30/M + 500 × $3.75/M + 400 × $15/M
        assertThat(pricing(ANTHROPIC, UNPRICED).costOf("anthropic", 1000, 2000, 500, 400))
                .contains(new BigDecimal("0.011475"));
    }

    @Test
    void aProviderThatCountsCachedTokensInsideItsPromptTotalIsNotBilledTwice() {
        // 1000 prompt tokens of which 400 were cached: 600 at full rate, 400 at the read rate.
        assertThat(pricing(INCLUSIVE, UNPRICED).costOf("anthropic", 1000, 400, 0, 0))
                .contains(new BigDecimal("0.001920"));
    }

    @Test
    void anUnpricedProviderRecordsNothingRatherThanZero() {
        assertThat(pricing(ANTHROPIC, UNPRICED).costOf("openai", 1000, 0, 0, 500)).isEmpty();
        assertThat(pricing(ANTHROPIC, UNPRICED).costOf("something-else", 1000, 0, 0, 500)).isEmpty();
    }

    @Test
    void aTurnWithNoMeasurementHasNoCost() {
        assertThat(pricing(ANTHROPIC, UNPRICED).costOf("anthropic", null, null, null, null))
                .as("zero would read as free; null reads as unknown, which is what it is")
                .isEmpty();
    }
}
