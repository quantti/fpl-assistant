package dev.quantti.fplassistant.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PriceStrategyDocumentTest {

    private static final String PRICE_DOCUMENT = "price-change-mechanics-and-team-value.md";

    @ParameterizedTest
    @ValueSource(strings = {
            PRICE_DOCUMENT,
            "strategy-transfers.md",
            "strategy-deadline-and-team-news.md",
            "strategy-wildcard-and-free-hit.md",
            "strategy-team-building.md",
            "strategy-preseason-team-building.md"
    })
    void updatedReferencesHaveDatedSelfDescribingChunks(String filename) throws IOException {
        String markdown = read(filename);
        var frontMatter = IngestionService.parseFrontMatter(markdown, filename);
        assertThat(frontMatter.kind()).isEqualTo("reference");
        assertThat(frontMatter.validFrom()).isEqualTo("2026-10-18");
        assertThat(markdown).contains("FPL Price Changes and Team Value");
        assertThat(chunks(filename)).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("title", frontMatter.title())
                    .containsEntry("kind", "reference")
                    .containsEntry("valid_from", "2026-10-18");
            assertThat(chunk.getText()).startsWith(frontMatter.title())
                    .doesNotContain("valid_from:");
        });
    }

    @Test
    void officialPredictorChunkExplainsThresholdsAndRequiresPlayerEvidence() throws IOException {
        assertThat(section(PRICE_DOCUMENT, "Using the official Price Changes page"))
                .contains("2026/27", "15 minutes", "00:00", "Europe/London",
                        "not a probability", "not a guarantee", "dated player evidence",
                        "does not supply a live player forecast");
    }

    @Test
    void sellingExamplesFollowHalfProfitAndFullLossRules() throws IOException {
        String selling = section(PRICE_DOCUMENT, "Purchase price, market price and selling price");
        var matcher = Pattern.compile(
                "\\| £([0-9]+\\.[0-9])m \\| £([0-9]+\\.[0-9])m \\| £([0-9]+\\.[0-9])m \\|")
                .matcher(read(PRICE_DOCUMENT));
        int examples = 0;
        while (matcher.find()) {
            int purchase = tenths(matcher.group(1));
            int current = tenths(matcher.group(2));
            int sellingPrice = tenths(matcher.group(3));
            int expected = current <= purchase ? current : purchase + (current - purchase) / 2;
            assertThat(sellingPrice).as("purchase %s, current %s", purchase, current)
                    .isEqualTo(expected);
            examples++;
        }
        assertThat(examples).isEqualTo(6);
        assertThat(selling).contains("half the profit", "rounded down", "losses below the purchase price");
    }

    @Test
    void valueAndFundsChunkKeepsMarketAndPersonalSellingValuesSeparate() throws IOException {
        assertThat(section(PRICE_DOCUMENT, "Squad value, selling value and money in the bank"))
                .contains("personal selling prices", "bank", "£104.0m", "£5.5m",
                        "does not reveal your personal selling prices");
    }

    @Test
    void timingChunkContainsAffordabilityAndPointsConditionsTogether() throws IOException {
        assertThat(section(PRICE_DOCUMENT, "Deciding whether to transfer before a price change"))
                .contains("personal selling price", "£0.2m", "team news", "-4",
                        "does not change your personal selling price");
        assertThat(section("strategy-transfers.md", "Price changes and transfer timing"))
                .contains("personal selling price", "team news", "-4", "official");
        assertThat(section("strategy-deadline-and-team-news.md", "Price checks versus waiting for team news"))
                .contains("personal selling price", "team news", "official");
    }

    @Test
    void historyChunkDoesNotTurnCountersOrGapsIntoAnOvernightEvent() throws IOException {
        assertThat(section(PRICE_DOCUMENT, "What captured daily price history can establish"))
                .contains("consecutive", "missing", "gameweek", "not a forecast",
                        "dated tool evidence");
    }

    @Test
    void chipChunkExplainsRebuyCostAndRestoredOriginalSquad() throws IOException {
        String filename = "strategy-wildcard-and-free-hit.md";
        assertThat(section(filename, "Price changes during Wildcard and Free Hit"))
                .contains("Wildcard", "Free Hit", "original squad", "bank", "£7.2m", "£7.5m",
                        "previous Gameweek deadline", "not the moment you pressed the chip button");
        assertThat(read(filename)).doesNotContain("changes can be made and reversed freely");
    }

    @Test
    void preseasonChunkDoesNotInventTransferDrivenPriceChangesBeforeLaunchDeadline() throws IOException {
        String filename = "strategy-preseason-team-building.md";
        assertThat(section(filename, "The pre-season calendar"))
                .contains("do not change before the season starts");
        assertThat(read(filename)).doesNotContain("Prices and ownership move daily from reveal to deadline");
    }

    private static int tenths(String millions) {
        return new BigDecimal(millions).movePointRight(1).intValueExact();
    }

    private static String read(String filename) throws IOException {
        return new ClassPathResource("docs/" + filename).getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Document> chunks(String filename) throws IOException {
        String markdown = read(filename);
        return IngestionService.chunk(IngestionService.stripFrontMatter(markdown),
                IngestionService.parseFrontMatter(markdown, filename), filename);
    }

    private static String section(String filename, String heading) throws IOException {
        List<Document> matches = chunks(filename).stream()
                .filter(chunk -> heading.equals(chunk.getMetadata().get("section"))).toList();
        assertThat(matches).as("%s section %s", filename, heading).hasSize(1);
        return matches.getFirst().getText();
    }
}
