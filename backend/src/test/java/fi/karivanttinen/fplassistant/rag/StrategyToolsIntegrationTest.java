package fi.karivanttinen.fplassistant.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end retrieval against the ephemeral test database. Needs a real
 * {@code OPENAI_API_KEY}: the query is embedded for real before the similarity search.
 * The clock is pinned to
 * 2026-08-14 so the fixture dates are deterministic forever: the timely fixture
 * (valid until 2026-09-22) is live, the expired fixture (valid until 2026-08-10) is
 * expired, and the reference fixtures (no valid_until) never expire — regardless of when
 * the suite runs.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StrategyToolsIntegrationTest {

    @Autowired
    StrategyTools strategyTools;

    @Autowired
    IngestionService ingestionService;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${spring.ai.vectorstore.pgvector.table-name}")
    String configuredTable;

    /** These tests ingest, which truncates. Refuse to start unless pinned off production. */
    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(configuredTable)
                .as("retrieval tests ingest fixtures; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void returnsRelevantReferenceChunksWithAttribution() {
        ingestFixtures();

        String result = strategyTools.searchStrategyDocs("bench boost points for all fifteen players");

        assertThat(result).contains("Bench Boost Playbook");
        assertThat(result).contains("reference");
        assertThat(result).contains("valid 2026-08-21 (no expiry)");
        assertThat(result).contains("When to bench boost");
        assertThat(result).contains("all 15 players' points");
    }

    @Test
    void realCorpusRetrievesTheFactualMinutesPolicy() {
        // Seed the checked-in documents into the isolated store, not the production embeddings.
        ingestionService.ingest("classpath:docs/*.md");

        String result = strategyTools.searchStrategyDocs(
                "Does a 45-minute appearance exclude a player or change the expected points"
                        + " ranking? What factual note should I give?");

        assertThat(result).contains("FPL Minutes Facts and the 60-Minute Rule")
                .contains("The ranking-tools policy")
                .contains("latest five complete observed fixtures")
                .contains("neutral fact");
    }

    @Test
    void returnsLiveTimelyChunksLabelledWithTheirValidityWindow() {
        ingestFixtures();

        String result = strategyTools.searchStrategyDocs("who should I captain in gameweek three");

        assertThat(result).contains("Gameweek 3 Captaincy Preview");
        assertThat(result).contains("timely");
        assertThat(result).contains("valid 2026-09-15 to 2026-09-22");
    }

    @Test
    void excludesExpiredTimelyContent() {
        ingestFixtures();

        // "greenhouse gerbil" appears only in the expired fixture — if expiry filtering
        // silently rots, this query returns it and the assertion fails.
        String result = strategyTools.searchStrategyDocs("greenhouse gerbil hamstring injury note");

        assertThat(result).doesNotContain("greenhouse gerbil");
        assertThat(result).doesNotContain("GW2 Injury Note");
    }

    @Test
    void emptyStoreIsDistinguishableFromNoMatch() {
        jdbc.execute("DELETE FROM fpl_embeddings_test");

        String result = strategyTools.searchStrategyDocs("bench boost");

        assertThat(result).contains("No strategy documents are loaded");
    }

    @Test
    void corpusWhoseEveryMatchIsExpiredIsDistinguishable() {
        ingestionService.ingest("classpath:docs-fixtures-expired/*.md");

        // Every chunk in the store is expired, so the widened candidate pool contains
        // nothing live — the all-expired message must come back, not an empty answer.
        String result = strategyTools.searchStrategyDocs("violet zebra paddle");

        assertThat(result).contains("No strategy documents are currently valid");
    }

    private void ingestFixtures() {
        ingestionService.ingest("classpath:docs-fixtures/*.md");
    }
}
