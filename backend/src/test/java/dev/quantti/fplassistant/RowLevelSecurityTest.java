package dev.quantti.fplassistant;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Value("${fpl.historical.table-name}")
    String historicalTable;

    @Value("${fpl.price.table-name}")
    String priceTable;

    @Value("${fpl.daily-price.table-name}")
    String dailyPriceTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.user.message-table-name}")
    String messageTable;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Test
    void everyTableThisProjectCreatesHasRowLevelSecurityEnabled() {
        for (String table : List.of(historicalTable, priceTable, dailyPriceTable,
                profileTable, conversationTable, messageTable, usageTable)) {
            Boolean enabled = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                            + " WHERE n.nspname = 'public' AND c.relname = ?", Boolean.class, table);

            assertThat(enabled)
                    .as("%s is exposed to the anon key through PostgREST unless RLS is enabled", table)
                    .isTrue();
        }
    }
}
