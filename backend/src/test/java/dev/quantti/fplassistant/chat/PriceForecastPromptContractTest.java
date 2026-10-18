package dev.quantti.fplassistant.chat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import dev.quantti.fplassistant.tools.PriceTools;

import static org.assertj.core.api.Assertions.*;

class PriceForecastPromptContractTest {
    private String prompt() throws Exception {
        try (var input = getClass().getResourceAsStream("/prompts/system-prompt.st")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void routesDistinctSourcesWithAbsentToolFallbackAndNoHardcodedState() throws Exception {
        assertThat(prompt()).contains("getPlayerPriceForecast if that tool is available",
                "If the tool is absent or unavailable", "only the latest dated daily observation",
                "getRecentPriceChanges", "longer daily observations", "not a hardcoded forecast state",
                "do not infer an upcoming rise from daily trends or RAG strategy text",
                "A forecast is not an", "observed event", "Missing entries or an empty feed do not prove no change",
                "Progress is not probability", "guide, not a guarantee", "exact target UTC",
                "unsupported signals or stale evidence do not mean the price will stay unchanged")
                .doesNotContain("getPriceHistory carries today's price", "use getPriceHistory —");
        assertThat(PriceTools.class.getMethod("getPriceHistory", String.class, int.class)
                .getAnnotation(Tool.class).description())
                .contains("getPlayerPriceForecast", "getRecentPriceChanges", "dated observation",
                        "Never infer exact overnight events across missing days")
                .doesNotContain("use this for \"what does X cost now\"");
    }

    @Test
    void disputedCurrentPriceUsesTheSameForecastFirstRoutingAsTheOriginalQuestion() throws Exception {
        String corrections = prompt().split("## Current player facts and corrections", 2)[1]
                .split("## Which season the numbers are from", 2)[0];
        assertThat(corrections).contains(
                "For disputed current prices, use getPlayerPriceForecast if that tool is available",
                "only the latest dated daily observation", "not proof of the current price",
                "for disputed availability use getPlayerAvailability")
                .doesNotContain("For disputed prices use getPriceHistory;");
    }

    @Test
    void separatesPreDailyCaptureDisagreementAndUnknownPersonalSellingProceeds() throws Exception {
        assertThat(prompt()).contains("Before the 02:00 UK daily capture", "daily capture shows the older price",
                "never mix stale cached prices with newer prices to calculate affordability",
                "personal purchase price or selling price", "ask for the selling price",
                "clearly conditional example", "never invent proceeds",
                "Never infer exact overnight events across missing daily observations");
    }
}
