package dev.quantti.fplassistant.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Truncate-and-reload ingestion of the strategy corpus into the vector store.
 *
 * <p>Every document must open with YAML front matter ({@code title}, {@code kind},
 * {@code valid_from}, and {@code valid_until} when {@code kind: timely}) — see the Phase 2
 * spec's Document metadata section. A document that is missing or mis-formats any required
 * field fails the whole run loudly, before a single row is deleted, so a bad addition can
 * never silently wipe the live corpus.
 *
 * <p>Chunking is by markdown section, via Spring AI's {@link MarkdownDocumentReader}: one
 * chunk per heading section. The reader deliberately does <em>not</em> put the heading into
 * the chunk body (it lands in {@code metadata.title}), so this service prefixes each chunk
 * with {@code <doc title> > <section header>} to make every retrieved chunk self-describing.
 * Front matter is stripped before parsing (commonmark would otherwise read the {@code ---}
 * delimiters as thematic breaks and leak the YAML into the first chunk's text). The reader
 * parses with plain commonmark — no GFM tables — so markdown tables stay inside their
 * section as flattened text, which is exactly the point of section chunking: a retrieved
 * chunk always carries its table's header row.
 *
 * <p>Embedding is done by the store itself via the OpenAI {@code EmbeddingModel} bean.
 */
@Component
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|$)", Pattern.DOTALL);

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final MarkdownDocumentReaderConfig READER_CONFIG =
            MarkdownDocumentReaderConfig.builder().build();

    /**
     * SnakeYAML resolves a bare {@code YYYY-MM-DD} scalar into a {@link java.util.Date}
     * object (implicit timestamp resolution), which would turn front matter dates into
     * "Tue Sep 15 02:00:00 CEST 2026" before ISO validation ever sees them. Downgrade
     * only the timestamp tag to a plain string so date fields stay the literal strings
     * the spec writes.
     */
    private static final Yaml YAML = new Yaml(
            new SafeConstructor(new LoaderOptions()),
            new Representer(new DumperOptions()),
            new DumperOptions(),
            new Resolver() {
                @Override
                public Tag resolve(NodeId kind, String value, boolean implicit) {
                    Tag tag = super.resolve(kind, value, implicit);
                    return tag == Tag.TIMESTAMP ? Tag.STR : tag;
                }
            });

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final String tableName;

    public IngestionService(VectorStore vectorStore, JdbcTemplate jdbc,
                            @Value("${spring.ai.vectorstore.pgvector.table-name}") String tableName) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    /** Ingests the production corpus at {@code classpath:docs/*.md}. */
    public void ingest() {
        ingest("classpath:docs/*.md");
    }

    /**
     * Parses and validates every document first — one bad document aborts the run before
     * anything is deleted — then truncates the store and reloads it. Re-running is
     * idempotent: the table ends up holding exactly the current document set.
     */
    public void ingest(String resourcePattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(resourcePattern);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve documents at " + resourcePattern, e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("No documents matched " + resourcePattern);
        }
        // Deterministic ingestion order (the resolver does not guarantee any).
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        List<Document> chunks = new ArrayList<>();
        for (Resource resource : resources) {
            String markdown = read(resource);
            FrontMatter frontMatter = parseFrontMatter(markdown, resource.getFilename());
            List<Document> fileChunks = chunk(stripFrontMatter(markdown), frontMatter, resource.getFilename());
            chunks.addAll(fileChunks);
            log.info("{}: {} chunks", resource.getFilename(), fileChunks.size());
        }

        jdbc.execute("DELETE FROM " + tableName);
        vectorStore.add(chunks);
        log.info("Ingested {} documents as {} chunks into {} (table truncated first, OpenAI embeddings)",
                resources.length, chunks.size(), tableName);
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource.getFilename(), e);
        }
    }

    /**
     * Parses and validates the front matter block. Throws {@link IllegalStateException}
     * naming the file when front matter is absent, not YAML, or misses/breaks a required
     * field — the spec requires a document that cannot be dated to fail ingestion loudly.
     */
    public static FrontMatter parseFrontMatter(String markdown, String fileName) {
        Matcher matcher = FRONT_MATTER.matcher(markdown);
        if (!matcher.find()) {
            throw new IllegalStateException(fileName + ": missing YAML front matter"
                    + " (required: title, kind, valid_from; valid_until required when kind is timely)");
        }
        Map<String, Object> yaml;
        try {
            Object loaded = YAML.load(matcher.group(1));
            yaml = loaded instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
        } catch (RuntimeException e) {
            throw new IllegalStateException(fileName + ": front matter is not valid YAML: " + e.getMessage(), e);
        }

        String title = blankToNull(yaml.get("title"));
        String kind = blankToNull(yaml.get("kind"));
        String validFrom = blankToNull(yaml.get("valid_from"));
        String validUntil = blankToNull(yaml.get("valid_until"));

        if (title == null) {
            throw new IllegalStateException(fileName + ": missing required front matter field 'title'");
        }
        if (kind == null) {
            throw new IllegalStateException(fileName + ": missing required front matter field 'kind'");
        }
        if (!kind.equals("reference") && !kind.equals("timely")) {
            throw new IllegalStateException(fileName + ": front matter 'kind' must be 'reference' or 'timely', was '" + kind + "'");
        }
        if (validFrom == null) {
            throw new IllegalStateException(fileName + ": missing required front matter field 'valid_from'");
        }
        if (!ISO_DATE.matcher(validFrom).matches()) {
            throw new IllegalStateException(fileName + ": 'valid_from' must be an ISO date (YYYY-MM-DD), was '" + validFrom + "'");
        }
        if (kind.equals("timely") && validUntil == null) {
            throw new IllegalStateException(fileName + ": timely document requires 'valid_until' —"
                    + " content that never expires must not enter the corpus");
        }
        if (validUntil != null && !ISO_DATE.matcher(validUntil).matches()) {
            throw new IllegalStateException(fileName + ": 'valid_until' must be an ISO date (YYYY-MM-DD), was '" + validUntil + "'");
        }
        return new FrontMatter(title, kind, validFrom, validUntil);
    }

    /** Strips the front matter block so the markdown reader never sees it. */
    static String stripFrontMatter(String markdown) {
        return FRONT_MATTER.matcher(markdown).replaceFirst("");
    }

    /**
     * Splits one document into one chunk per markdown section. Each chunk's body is
     * prefixed with {@code <doc title> > <section header>} (the reader stores the header
     * in metadata, not content) and its metadata carries {@code title}, {@code section},
     * {@code kind}, {@code valid_from} and — for timely documents — {@code valid_until}.
     */
    public static List<Document> chunk(String markdown, FrontMatter frontMatter, String fileName) {
        List<Document> sections = new MarkdownDocumentReader(
                List.of(new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8))), READER_CONFIG).get();
        if (sections.isEmpty()) {
            throw new IllegalStateException(fileName + ": no section content found — nothing to embed");
        }
        List<Document> chunks = new ArrayList<>();
        for (Document section : sections) {
            Map<String, Object> metadata = new HashMap<>(section.getMetadata());
            Object heading = metadata.remove("title");
            String sectionHeader = heading != null ? heading.toString() : frontMatter.title();
            metadata.remove("category");
            metadata.put("title", frontMatter.title());
            metadata.put("section", sectionHeader);
            metadata.put("kind", frontMatter.kind());
            metadata.put("valid_from", frontMatter.validFrom());
            if (frontMatter.validUntil() != null) {
                metadata.put("valid_until", frontMatter.validUntil());
            }
            String prefix = frontMatter.title().equals(sectionHeader)
                    ? frontMatter.title()
                    : frontMatter.title() + " > " + sectionHeader;
            chunks.add(new Document(prefix + "\n" + section.getText(), metadata));
        }
        return chunks;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new HashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static String blankToNull(Object value) {
        String s = value == null ? null : String.valueOf(value).trim();
        return s == null || s.isEmpty() ? null : s;
    }

    /** Validated front matter of one strategy document. */
    public record FrontMatter(String title, String kind, String validFrom, String validUntil) {
    }
}
