package fi.karivanttinen.fplassistant;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table this project creates must have row level security enabled.
 *
 * <p>Supabase publishes the {@code public} schema as a REST API, and the {@code anon} role it
 * authenticates with holds SELECT/INSERT/UPDATE/DELETE on everything there by default. The anon
 * key is public by design — it ships in the frontend bundle — so RLS with no policies is the only
 * thing standing between that key and the data. On 2026-08-19 it was off across the whole
 * database and the key could read `player`, `fpl_daily_price` and `chip_usage` straight out of
 * /rest/v1; this test is what stops a new table quietly reintroducing that.
 *
 * <p>It costs the application nothing: we connect as a role with {@code rolbypassrls = true}, so
 * RLS never applies to our own queries. That is also why it cannot be the authorization
 * mechanism for user data — see the Phase 5a plan.
 */
@SpringBootTest
@ActiveProfiles("test")
class RowLevelSecurityTest {

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Derived, not listed. A hand-maintained list is green precisely for the table nobody
     * remembered to add — which is how `fpl_embeddings` (created by Spring AI's PgVectorStore,
     * not by any DDL in this project) went uncovered while the test claimed to cover everything.
     * Anything in `public` that the tapas schema dump does not define belongs to this project.
     */
    @Test
    void everyTableThisProjectCreatesHasRowLevelSecurityEnabled() throws Exception {
        Set<String> tapasTables = tablesDefinedBy("db/tapas-schema.sql");

        List<String> unprotected = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT c.relrowsecurity
                ORDER BY c.relname
                """, String.class)
                .stream()
                .filter(table -> !tapasTables.contains(table))
                .toList();

        assertThat(unprotected)
                .as("tables this project creates are exposed to the anon key through PostgREST"
                        + " unless RLS is enabled")
                .isEmpty();
    }

    private Set<String> tablesDefinedBy(String resource) throws Exception {
        String dump = new String(new ClassPathResource(resource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("CREATE TABLE public\\.(\\w+)").matcher(dump);
        Set<String> tables = new HashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        assertThat(tables).as("the tapas schema dump should define the tables we do not own")
                .isNotEmpty();
        return tables;
    }
}
