package dev.quantti.fplassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for front matter validation and section chunking. No Spring context,
 * no database — ingestion mechanics that do not touch the store.
 */
class IngestionServiceTest {

    private static final String REFERENCE_DOC = """
            ---
            title: Bench Boost Playbook
            kind: reference
            valid_from: 2026-08-21
            ---

            # Bench Boost Playbook

            Reference guide to the Bench Boost chip.

            ## When to bench boost

            Bench Boost counts all 15 players' points for one Gameweek.

            ## Common mistakes

            A bench of non-starters wastes the slot.
            """;

    private static final IngestionService.FrontMatter REFERENCE =
            new IngestionService.FrontMatter("Bench Boost Playbook", "reference", "2026-08-21", null);

    // ------------------------------------------------------------------
    // Front matter parsing
    // ------------------------------------------------------------------

    @Test
    void parsesAReferenceDocument() {
        IngestionService.FrontMatter fm =
                IngestionService.parseFrontMatter(REFERENCE_DOC, "fixture-reference.md");

        assertThat(fm.title()).isEqualTo("Bench Boost Playbook");
        assertThat(fm.kind()).isEqualTo("reference");
        assertThat(fm.validFrom()).isEqualTo("2026-08-21");
        assertThat(fm.validUntil()).isNull();
    }

    @Test
    void parsesATimelyDocumentWithExpiry() {
        String doc = """
                ---
                title: Gameweek 3 Captaincy Preview
                kind: timely
                valid_from: 2026-09-15
                valid_until: 2026-09-22
                ---
                # Gameweek 3 Captaincy Preview
                """;

        IngestionService.FrontMatter fm = IngestionService.parseFrontMatter(doc, "gw3.md");

        assertThat(fm.kind()).isEqualTo("timely");
        assertThat(fm.validUntil()).isEqualTo("2026-09-22");
    }

    @Test
    void missingFrontMatterFailsLoudlyNamingTheFile() {
        assertThatThrownBy(() -> IngestionService.parseFrontMatter("# No front matter", "bad.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.md")
                .hasMessageContaining("missing YAML front matter");
    }

    @Test
    void missingRequiredFieldFailsLoudly() {
        String noTitle = """
                ---
                kind: reference
                valid_from: 2026-08-21
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(noTitle, "no-title.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-title.md")
                .hasMessageContaining("'title'");

        String noValidFrom = """
                ---
                title: T
                kind: reference
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(noValidFrom, "no-date.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'valid_from'");
    }

    @Test
    void unknownKindFailsLoudly() {
        String doc = """
                ---
                title: T
                kind: evergreen
                valid_from: 2026-08-21
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(doc, "kind.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be 'reference' or 'timely'");
    }

    @Test
    void timelyDocumentWithoutValidUntilFailsLoudly() {
        String doc = """
                ---
                title: GW3 Preview
                kind: timely
                valid_from: 2026-09-15
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(doc, "gw3.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires 'valid_until'");
    }

    @Test
    void malformedDatesFailLoudly() {
        String badValidFrom = """
                ---
                title: T
                kind: reference
                valid_from: 21/08/2026
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(badValidFrom, "date.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO date");

        String badValidUntil = """
                ---
                title: T
                kind: timely
                valid_from: 2026-09-15
                valid_until: next week
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(badValidUntil, "date.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO date");
    }

    @Test
    void nonYamlFrontMatterFailsLoudly() {
        String doc = "---\nthis is not: [valid: yaml: at all\n---\n# x";
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(doc, "yaml.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yaml.md")
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void calendarImpossibleDatesFailLoudly() {
        // 2026-02-30 passes a shape regex but is not a real date — it would crash
        // LocalDate.parse at retrieval time, so ingestion must reject it.
        String badValidFrom = """
                ---
                title: T
                kind: reference
                valid_from: 2026-02-30
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(badValidFrom, "date.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO date");

        String badValidUntil = """
                ---
                title: T
                kind: timely
                valid_from: 2026-09-01
                valid_until: 2026-09-31
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(badValidUntil, "date.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO date");
    }

    @Test
    void validUntilBeforeValidFromFailsLoudly() {
        String doc = """
                ---
                title: T
                kind: timely
                valid_from: 2026-10-01
                valid_until: 2026-09-15
                ---
                # x
                """;
        assertThatThrownBy(() -> IngestionService.parseFrontMatter(doc, "inverted.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inverted.md")
                .hasMessageContaining("must not be before");
    }

    // ------------------------------------------------------------------
    // Section chunking
    // ------------------------------------------------------------------

    @Test
    void splitsIntoOneChunkPerSectionWithHeaderPrefixedIntoTheBody() {
        List<Document> chunks =
                IngestionService.chunk(IngestionService.stripFrontMatter(REFERENCE_DOC), REFERENCE, "ref.md");

        // intro + 2 sections
        assertThat(chunks).hasSize(3);

        Document intro = chunks.get(0);
        assertThat(intro.getText()).startsWith("Bench Boost Playbook\nReference guide");
        assertThat(intro.getMetadata()).containsEntry("title", "Bench Boost Playbook")
                .containsEntry("section", "Bench Boost Playbook")
                .containsEntry("kind", "reference")
                .containsEntry("valid_from", "2026-08-21");

        Document section = chunks.get(1);
        assertThat(section.getText()).startsWith("Bench Boost Playbook > When to bench boost\nBench Boost counts all 15");
        assertThat(section.getMetadata()).containsEntry("title", "Bench Boost Playbook")
                .containsEntry("section", "When to bench boost")
                .containsEntry("kind", "reference")
                .containsEntry("valid_from", "2026-08-21");

        // every chunk carries the section context, never the YAML
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.getText()).doesNotContain("kind:");
            assertThat(c.getText()).doesNotContain("valid_from");
        });
    }

    @Test
    void timelyChunksCarryTheExpiryInMetadata() {
        String doc = """
                ---
                title: Gameweek 3 Captaincy Preview
                kind: timely
                valid_from: 2026-09-15
                valid_until: 2026-09-22
                ---

                # Gameweek 3 Captaincy Preview

                A short preview of the week's captaincy.

                ## The pick

                Salah at home is the standout captain.
                """;
        IngestionService.FrontMatter fm =
                IngestionService.parseFrontMatter(doc, "gw3.md");
        List<Document> chunks = IngestionService.chunk(IngestionService.stripFrontMatter(doc), fm, "gw3.md");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).getMetadata()).containsEntry("section", "The pick")
                .containsEntry("kind", "timely")
                .containsEntry("valid_until", "2026-09-22");
    }

    @Test
    void headingWithoutBodyProducesNoChunkButLeafSectionsStillLand() {
        String doc = """
                # Doc

                Intro.

                ## Parent only

                ### Leaf one

                Leaf one body.

                ### Leaf two

                Leaf two body.
                """;
        List<Document> chunks = IngestionService.chunk(doc, REFERENCE, "nested.md");

        // "Parent only" has no body of its own -> dropped; the leaves remain
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1).getMetadata().get("section")).isEqualTo("Leaf one");
        assertThat(chunks.get(2).getMetadata().get("section")).isEqualTo("Leaf two");
    }

    @Test
    void trailingThematicBreakDoesNotCreateOrCorruptChunks() {
        String doc = "# Doc\n\nIntro.\n\n## Section\n\nBody text.\n\n---\n";
        List<Document> chunks = IngestionService.chunk(doc, REFERENCE, "hr.md");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).getText()).isEqualTo("Bench Boost Playbook > Section\nBody text.");
    }

    @Test
    void headinglessDocumentBecomesOneChunkTitledWithTheDocTitle() {
        List<Document> chunks = IngestionService.chunk(
                "Just a paragraph with no headings at all.", REFERENCE, "plain.md");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).startsWith("Bench Boost Playbook\n");
        assertThat(chunks.get(0).getMetadata().get("section")).isEqualTo("Bench Boost Playbook");
    }

    @Test
    void minutesStrategyKeepsRulesAndReliabilityCaveatsInSelfContainedChunks() throws Exception {
        String file = "strategy-minutes-and-rotation.md";
        String markdown = new ClassPathResource("docs/" + file)
                .getContentAsString(StandardCharsets.UTF_8);
        IngestionService.FrontMatter fm = IngestionService.parseFrontMatter(markdown, file);
        List<Document> chunks = IngestionService.chunk(IngestionService.stripFrontMatter(markdown), fm, file);

        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("title", fm.title())
                    .containsEntry("kind", "reference")
                    .containsEntry("valid_from", "2026-09-09")
                    .doesNotContainKey("valid_until");
            assertThat(chunk.getText()).startsWith(fm.title())
                    .doesNotContain("valid_from:");
        });
        assertThat(chunks).filteredOn(c -> "Why 60 minutes changes a defender's value"
                        .equals(c.getMetadata().get("section")))
                .singleElement().satisfies(chunk -> assertThat(chunk.getText())
                        .contains("Appearance points", "1–59", "60+",
                                "sent off", "a later team goal removes the clean sheet",
                                "Defensive contribution has no 60-minute eligibility rule"));
        assertThat(chunks).filteredOn(c -> "Choosing reliable defenders before attacking upside"
                        .equals(c.getMetadata().get("section")))
                .singleElement().satisfies(chunk -> assertThat(chunk.getText())
                        .contains("reliable first choice", "minutes-aware projection",
                                "no universal probability cutoff"));
        assertThat(chunks).filteredOn(c -> "The recent under-60 exclusion used by the ranking tools"
                        .equals(c.getMetadata().get("section")))
                .singleElement().satisfies(chunk -> assertThat(chunk.getText())
                        .contains("Every expected fixture remains in the denominator",
                                "no 60+ or current-total-minutes admission gate",
                                "three latest complete observed fixtures",
                                "never establish current availability", "not a calibrated"));
    }

    @Test
    void documentWithNoBodyContentFailsLoudly() {
        String doc = "# Heading only\n\n## Another heading\n";
        assertThatThrownBy(() -> IngestionService.chunk(doc, REFERENCE, "empty.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty.md")
                .hasMessageContaining("no section content");
    }
}
