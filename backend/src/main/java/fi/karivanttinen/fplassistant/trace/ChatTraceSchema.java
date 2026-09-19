package fi.karivanttinen.fplassistant.trace;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent schema for short-lived, administrator-only chat traces. */
@Component
public class ChatTraceSchema {

    private final JdbcTemplate jdbc;
    private final String table;

    public ChatTraceSchema(JdbcTemplate jdbc,
                           @Value("${fpl.trace.table-name}") String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID NOT NULL,
                    conversation_id UUID,
                    payload JSONB NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    completed_at TIMESTAMPTZ
                )
                """.formatted(table));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_created ON %s(created_at DESC)"
                .formatted(table, table));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table));
    }
}
