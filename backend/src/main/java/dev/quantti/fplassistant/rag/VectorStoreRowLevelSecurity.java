package dev.quantti.fplassistant.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enables row level security on the corpus table, which nothing else can.
 *
 * <p>Every other table this project owns gets RLS in the DDL that creates it. {@code
 * fpl_embeddings} is created by Spring AI's {@code PgVectorStore} instead — {@code
 * initialize-schema: true} in {@code application.yml} — so it was the one table born open to the
 * anon key, and the guard test could not see it while that test enumerated tables by hand.
 * Production was closed only because {@code scripts/sql/enable-rls.sql} was run against tables
 * that already existed; a fresh Supabase project would have served the whole strategy corpus out
 * of {@code /rest/v1/fpl_embeddings} to anyone holding the key the frontend ships.
 *
 * <p>The {@link VectorStore} dependency is not used — it is what orders this after the store has
 * created its table.
 */
@Component
public class VectorStoreRowLevelSecurity {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreRowLevelSecurity.class);

    private final JdbcTemplate jdbc;
    private final String tableName;

    public VectorStoreRowLevelSecurity(JdbcTemplate jdbc, VectorStore vectorStore,
                                       @Value("${spring.ai.vectorstore.pgvector.table-name}")
                                       String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    @PostConstruct
    void enableRowLevelSecurity() {
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tableName));
        log.debug("row level security ensured on {}", tableName);
    }
}
