package dev.quantti.fplassistant.user;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * What one turn cost, priced from configured rates at the moment it happened.
 *
 * <p>The cost is stored, not derived on read. Rates change — a model switch, a promotional
 * period ending — and a figure that reprices itself every time it is read is not a record of what
 * was spent. A provider with no rates configured records nothing rather than a guess.
 *
 * <p>Providers do not agree on what a prompt token count means. Anthropic reports
 * {@code input_tokens} as the <em>uncached remainder</em>, with cache reads and cache writes
 * counted separately; others fold cached tokens into the prompt count, in which case the cached
 * part has to be subtracted before the full rate is applied. That is what
 * {@code cachedIncludedInInput} selects.
 */
@Component
public class Pricing {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /** USD per million tokens, and how this provider counts what it billed. */
    public record Rates(String model,
                        BigDecimal inputPerMillion,
                        BigDecimal outputPerMillion,
                        BigDecimal cacheReadMultiplier,
                        BigDecimal cacheWriteMultiplier,
                        boolean cachedIncludedInInput) {

        boolean priced() {
            return inputPerMillion != null && outputPerMillion != null
                    && (inputPerMillion.signum() > 0 || outputPerMillion.signum() > 0);
        }
    }

    @ConfigurationProperties("fpl.pricing")
    public record Configured(Rates anthropic, Rates openai) {}

    private final Configured configured;

    public Pricing(Configured configured) {
        this.configured = configured;
    }

    /** The configured rates for a provider, or empty when it has none. */
    Optional<Rates> ratesFor(String provider) {
        Rates rates = switch (provider == null ? "" : provider) {
            case "anthropic" -> configured.anthropic();
            case "openai" -> configured.openai();
            default -> null;
        };
        return rates != null && rates.priced() ? Optional.of(rates) : Optional.empty();
    }

    /**
     * Empty when the provider is unpriced, or when nothing was measured. A turn whose tokens
     * were never reported has no cost we can state, and stating zero would read as free.
     */
    public Optional<BigDecimal> costOf(String provider, Integer inputTokens, Integer cachedTokens,
                                       Integer cacheWriteTokens, Integer outputTokens) {
        Optional<Rates> found = ratesFor(provider);
        if (found.isEmpty() || (inputTokens == null && outputTokens == null)) {
            return Optional.empty();
        }
        Rates rates = found.get();

        long cached = value(cachedTokens);
        long written = value(cacheWriteTokens);
        long input = value(inputTokens);
        if (rates.cachedIncludedInInput()) {
            // The provider counted the cached tokens inside its prompt total; charging the full
            // rate on them as well would bill the discount twice over.
            input = Math.max(0, input - cached);
        }

        BigDecimal fullPrice = rates.inputPerMillion();
        return Optional.of(perMillion(input, fullPrice)
                .add(perMillion(cached, fullPrice.multiply(rates.cacheReadMultiplier())))
                .add(perMillion(written, fullPrice.multiply(rates.cacheWriteMultiplier())))
                .add(perMillion(value(outputTokens), rates.outputPerMillion()))
                .setScale(6, RoundingMode.HALF_UP));
    }

    private static long value(Integer tokens) {
        return tokens == null ? 0 : tokens;
    }

    private static BigDecimal perMillion(long tokens, BigDecimal rate) {
        return BigDecimal.valueOf(tokens).multiply(rate).divide(PER_MILLION, 10, RoundingMode.HALF_UP);
    }
}
