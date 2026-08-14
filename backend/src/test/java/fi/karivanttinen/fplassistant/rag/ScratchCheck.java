package fi.karivanttinen.fplassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ScratchCheck {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void dump() {
        String[] queries = {
            "Q3 negated-missing|SELECT count(*) FROM fpl_embeddings WHERE metadata::jsonb @@ '!($.valid_until)'::jsonpath",
            "Q4 or-missing-gte|SELECT count(*) FROM fpl_embeddings WHERE metadata::jsonb @@ '(!($.valid_until)) || ($.valid_until >= \"2026-08-14\")'::jsonpath",
            "Q5 eq-empty|SELECT count(*) FROM fpl_embeddings WHERE metadata::jsonb @@ '$.valid_until == \"\"'::jsonpath",
            "Q6 exists-gte|SELECT count(*) FROM fpl_embeddings WHERE metadata::jsonb @@ 'exists($.valid_until) && ($.valid_until >= \"2026-08-14\")'::jsonpath",
        };
        for (String q : queries) {
            String[] parts = q.split("\\|", 2);
            try {
                Integer n = jdbc.queryForObject(parts[1], Integer.class);
                System.out.println(parts[0] + " -> " + n);
            } catch (Exception e) {
                System.out.println(parts[0] + " -> EXCEPTION: " + e.getMessage());
            }
        }
    }
}
