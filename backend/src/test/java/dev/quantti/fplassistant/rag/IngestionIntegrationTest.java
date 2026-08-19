package dev.quantti.fplassistant.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end ingestion against the ephemeral test database, pinned to the isolated test
 * table {@code fpl_embeddings_test} by the {@code test} profile
 * ({@code src/test/resources/application-test.yml}).
 *
 * <p>These are the only tests that need a real {@code OPENAI_API_KEY}: ingestion embeds for
 * real, and a dummy key fails with {@code 401 Incorrect API key provided}. Stubbing the
 * embedding model would make them offline and free but would stop them proving the thing
 * they exist to prove — that a document survives chunking, embedding and pgvector storage
 * with its metadata intact.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionIntegrationTest {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${spring.ai.vectorstore.pgvector.table-name}")
    String configuredTable;

    /**
     * Ingestion truncates whatever table it is pointed at, so it must be pointed at the isolated
     * one. This used to be backed up by a second check comparing the production corpus's row
     * count before and after the run; that check was removed with the move to an ephemeral test
     * database, where the production table does not exist to be counted. The pinning assertion
     * below is what remains, and it prevents damage rather than detecting it afterwards.
     */
    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(configuredTable)
                .as("ingestion tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
    }

    @Test
    void ingestionWritesChunksWithMetadataIntact() {
        ingestionService.ingest("classpath:docs-fixtures/*.md");

        // fixture-reference.md: intro + 3 sections = 4; fixture-timely.md: intro + 2 sections = 3;
        // fixture-expired.md: intro + 1 section = 2 → 9 total
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);

        List<Map<String, Object>> rows = allRows();
        assertThat(rows).hasSize(9);

        Map<String, Object> section = row(rows, "When to bench boost");
        assertThat(section.get("title")).isEqualTo("Bench Boost Playbook");
        assertThat(section.get("kind")).isEqualTo("reference");
        assertThat(section.get("valid_from")).isEqualTo("2026-08-21");
        assertThat(section.get("valid_until")).isNull();
        assertThat((String) section.get("content")).startsWith("Bench Boost Playbook > When to bench boost\n");
        assertThat((String) section.get("content")).contains("all 15 players' points");

        Map<String, Object> intro = row(rows, "Bench Boost Playbook");
        assertThat((String) intro.get("content")).startsWith("Bench Boost Playbook\n");
        assertThat((String) intro.get("content")).doesNotContain("kind:");

        Map<String, Object> timely = row(rows, "The pick");
        assertThat(timely.get("title")).isEqualTo("Gameweek 3 Captaincy Preview");
        assertThat(timely.get("kind")).isEqualTo("timely");
        assertThat(timely.get("valid_until")).isEqualTo("2026-09-22");
        assertThat((String) timely.get("content")).startsWith("Gameweek 3 Captaincy Preview > The pick\n");
    }

    @Test
    void reIngestingTheSameCorpusDoesNotDuplicateRows() {
        ingestionService.ingest("classpath:docs-fixtures/*.md");
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);

        ingestionService.ingest("classpath:docs-fixtures/*.md");
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);
    }

    @Test
    void ingestingADifferentCorpusReplacesThePreviousOne() {
        ingestionService.ingest("classpath:docs-fixtures/*.md");
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);

        ingestionService.ingest("classpath:docs-fixtures-alt/*.md");

        assertThat(countRows("fpl_embeddings_test")).isEqualTo(2);
        List<Map<String, Object>> rows = allRows();
        assertThat(rows).extracting(r -> r.get("content"))
                .anyMatch(c -> ((String) c).contains("seven-segment violet walrus"));
        assertThat(rows).extracting(r -> r.get("content"))
                .noneMatch(c -> ((String) c).contains("Salah"));
    }

    @Test
    void documentMissingFrontMatterFailsBeforeDeletingAnything() {
        ingestionService.ingest("classpath:docs-fixtures/*.md");
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);

        assertThatThrownBy(() -> ingestionService.ingest("classpath:docs-fixtures-bad/*.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-frontmatter.md")
                .hasMessageContaining("missing YAML front matter");

        // Validation happens before truncation: the previous corpus is still there.
        assertThat(countRows("fpl_embeddings_test")).isEqualTo(9);
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> allRows() {
        return jdbc.queryForList("""
                SELECT content, metadata->>'title' AS title, metadata->>'section' AS section,
                       metadata->>'kind' AS kind, metadata->>'valid_from' AS valid_from,
                       metadata->>'valid_until' AS valid_until
                FROM fpl_embeddings_test
                """);
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String section) {
        return rows.stream()
                .filter(r -> section.equals(r.get("section")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chunk with section " + section + " in " + rows));
    }
}
