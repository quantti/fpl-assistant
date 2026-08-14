package fi.karivanttinen.fplassistant.rag;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
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
 * <p><b>Expiry filtering.</b> Enforced in the database, as the spec requires, so expired
 * documents never occupy a top-k slot. The naive expression {@code valid_until >= today} cannot
 * be used on its own: a jsonpath comparison against a missing metadata key evaluates to false,
 * so it would silently exclude every reference document (which has no {@code valid_until}), and
 * the pgvector filter converter rejects {@code isNull}/{@code isNotNull} — it implements only
 * and/or/eq/ne/gt/gte/lt/lte. The equivalent expression below uses only supported operators and
 * leans on an invariant {@code IngestionService} enforces: {@code kind: timely} always carries a
 * {@code valid_until}, and {@code kind} is always one of {@code reference}/{@code timely}.
 *
 * <p>{@link #isCurrentlyValid} still runs afterwards as a second pass. It is not redundant: a
 * {@code reference} document is *permitted* to carry a {@code valid_until}, and such a row would
 * pass the SQL filter on its {@code kind} alone. The Java pass also guards hand-edited rows with
 * unparseable dates. A small candidate widening ({@code TOP_K_CANDIDATES}) absorbs whatever that
 * second pass removes.
 */
@Component
public class StrategyTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(StrategyTools.class);

    /** Final answer size — the spec's top-k 5, set explicitly. */
    static final int TOP_K = 5;

    /**
     * Small widening so the second-pass Java filter cannot leave fewer than {@link #TOP_K}
     * results. The database filter already removes expired rows, so this only has to absorb
     * the rare reference-document-with-an-expired-window case.
     */
    static final int TOP_K_CANDIDATES = 10;

    private final VectorStore vectorStore;
    private final Clock clock;

    public StrategyTools(VectorStore vectorStore, ObjectProvider<Clock> clock) {
        this.vectorStore = vectorStore;
        // Expiry is a calendar-day decision about UK football content, so it must roll over at
        // UK midnight — not the container's zone, which on Fly is UTC and would end a
        // valid_until day an hour early during British Summer Time.
        this.clock = clock.getIfAvailable(() -> Clock.system(ZoneId.of("Europe/London")));
    }

    // Keep this description in sync with the "Strategy advice" section of system-prompt.st —
    // the model reads both and they must agree on when the tool applies.
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
            LocalDate today = LocalDate.now(clock);
            List<Document> matches = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(TOP_K_CANDIDATES)
                            .filterExpression(unexpiredFilter(today))
                            .build());
            List<Document> valid = matches.stream()
                    .filter(doc -> isCurrentlyValid(doc.getMetadata(), today))
                    .limit(TOP_K)
                    .toList();
            if (valid.isEmpty()) {
                // The database filter already dropped expired rows, so an empty result is
                // either an unloaded corpus or one where everything has expired. They are
                // different operational problems (failed deploy vs stale content), so probe
                // once to say which — only on this rare path.
                return storeIsEmpty()
                        ? "No strategy documents are loaded — say so rather than improvising strategy advice."
                        : "No strategy documents are currently valid — say so rather than improvising strategy advice.";
            }
            return valid.stream().map(this::formatChunk).collect(Collectors.joining("\n\n---\n\n"));
        } catch (DataAccessException e) {
            log.error("searchStrategyDocs(\"{}\") failed on the vector store", query, e);
            return "The strategy document library is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        } catch (RuntimeException e) {
            // Unlike the stat tools, this one makes an outbound call: the query has to be
            // embedded by OpenAI before the search runs. A 429/5xx there surfaces as a
            // RestClientException/AI exception, which the DataAccessException catch would
            // miss — leaving the model to relay a raw stack-trace message to the user.
            log.error("searchStrategyDocs(\"{}\") failed while embedding the query", query, e);
            return "The strategy document library is temporarily unavailable (embedding error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * {@code kind == "reference" || valid_until >= today}. Equivalent to "not expired" given
     * the ingestion invariant that every {@code timely} document carries a {@code valid_until},
     * and expressible with the operators the pgvector filter converter actually supports.
     */
    static Filter.Expression unexpiredFilter(LocalDate today) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.or(b.eq("kind", "reference"), b.gte("valid_until", today.toString())).build();
    }

    /** Distinguishes an unloaded corpus from one where every match has expired. */
    private boolean storeIsEmpty() {
        return vectorStore.similaritySearch(SearchRequest.builder().query("fpl").topK(1).build())
                .isEmpty();
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
        try {
            return !LocalDate.parse(until.toString()).isBefore(today);
        } catch (DateTimeParseException e) {
            // Undatable content cannot be trusted as live — exclude it rather than crash
            // the stream. Ingestion rejects malformed dates, so this only guards against
            // hand-edited rows.
            return false;
        }
    }

    private String formatChunk(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        // Metadata keys are set by ingestion; the fallbacks only make a hand-edited row
        // visible as <unknown> instead of literal "null" strings inside answers.
        String title = Objects.requireNonNullElse(metadata.get("title"), "<unknown>").toString();
        String section = Objects.requireNonNullElse(metadata.get("section"), "<unknown>").toString();
        String kind = Objects.requireNonNullElse(metadata.get("kind"), "<unknown>").toString();
        String window = Objects.requireNonNullElse(metadata.get("valid_from"), "<unknown>").toString();
        Object until = metadata.get("valid_until");
        window += until == null ? " (no expiry)" : " to " + until;
        return "From \"" + title + "\" > " + section + " (" + kind + ", valid " + window + "):\n" + chunk.getText();
    }
}
