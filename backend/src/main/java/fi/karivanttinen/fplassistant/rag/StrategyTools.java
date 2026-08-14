package fi.karivanttinen.fplassistant.rag;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import fi.karivanttinen.fplassistant.tools.FplTool;

/**
 * The strategy-knowledge layer: retrieval over the RAG corpus of FPL strategy documents.
 *
 * <p>Unlike the stat tools there is no {@code SeasonContext.provenance()} prefix here — the
 * corpus is not resolved through {@code SeasonResolver}. Instead every retrieved chunk carries
 * its own attribution: document title, section, kind ({@code reference} or {@code timely}) and
 * the validity window, so the model can weigh dated content by age. That is the RAG-side
 * counterpart of the seasonal-provenance discipline for database rows.
 *
 * <p><b>Expiry filtering.</b> The spec asks for query-time expiry filtering via
 * {@code SearchRequest.filterExpression}. That is not expressible with this library: the
 * pgvector filter converter supports only comparison operators (and/or/eq/ne/gt/gte/lt/lte),
 * and a jsonpath comparison against a missing metadata key evaluates to false — so
 * {@code valid_until >= today} alone silently excludes every reference document (no
 * {@code valid_until}), and there is no supported existence/null operator to OR it with.
 * Verified against the live database before choosing the fallback: the tool instead searches a
 * widened candidate pool ({@code TOP_K_CANDIDATES}) and filters expired chunks in Java,
 * trimming to {@code TOP_K} live results. From the model's point of view the behaviour is the
 * same — expired content never reaches it, and dead previews cannot crowd live material out of
 * the final five as long as fewer than {@code TOP_K_CANDIDATES - TOP_K} of the top matches are
 * expired.
 */
@Component
public class StrategyTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(StrategyTools.class);

    /** Final answer size — the spec's top-k 5, set explicitly. */
    static final int TOP_K = 5;

    /** Widened candidate pool so Java-side expiry filtering cannot starve the result. */
    static final int TOP_K_CANDIDATES = 20;

    private final VectorStore vectorStore;
    private final Clock clock;

    public StrategyTools(VectorStore vectorStore, @Nullable Clock clock) {
        this.vectorStore = vectorStore;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Tool(description = """
            Search the FPL strategy document library for advice on transfers, captaincy, chips
            (wildcard, free hit, bench boost, triple captain), team building, differentials and
            squad structure. Pass a natural-language question about strategy; the matching
            document sections are returned with their source document and validity window.
            Not for live stats lookups (goals, points, fixtures, prices) — use the stats tools
            for those.
            """)
    public String searchStrategyDocs(String query) {
        try {
            List<Document> matches = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(TOP_K_CANDIDATES).build());
            if (matches.isEmpty()) {
                return "No strategy documents are loaded — say so rather than improvising strategy advice.";
            }
            LocalDate today = LocalDate.now(clock);
            List<Document> valid = matches.stream()
                    .filter(doc -> isCurrentlyValid(doc.getMetadata(), today))
                    .limit(TOP_K)
                    .toList();
            if (valid.isEmpty()) {
                return "No strategy documents are currently valid — say so rather than improvising strategy advice.";
            }
            return valid.stream().map(this::formatChunk).collect(Collectors.joining("\n\n---\n\n"));
        } catch (DataAccessException e) {
            log.error("searchStrategyDocs(\"{}\") failed", query, e);
            return "The strategy document library is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * A chunk is live when it has no {@code valid_until} (absent = no expiry, per the spec's
     * metadata table) or its {@code valid_until} is today or later. Exposed as a pure static
     * method so the date logic is testable with fixed dates.
     */
    static boolean isCurrentlyValid(Map<String, Object> metadata, LocalDate today) {
        Object until = metadata.get("valid_until");
        if (until == null || until.toString().isBlank()) {
            return true;
        }
        return !LocalDate.parse(until.toString()).isBefore(today);
    }

    private String formatChunk(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        String title = String.valueOf(metadata.get("title"));
        String section = String.valueOf(metadata.get("section"));
        String kind = String.valueOf(metadata.get("kind"));
        String window = String.valueOf(metadata.get("valid_from"));
        Object until = metadata.get("valid_until");
        window += until == null ? " (no expiry)" : " to " + until;
        return "From \"" + title + "\" > " + section + " (" + kind + ", valid " + window + "):\n" + chunk.getText();
    }
}
