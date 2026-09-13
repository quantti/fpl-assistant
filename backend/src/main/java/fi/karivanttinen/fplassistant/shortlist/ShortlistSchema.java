package fi.karivanttinen.fplassistant.shortlist;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent schema for account-owned, season-scoped shortlist membership. */
@Component
@DependsOn("playerCatalogueSchema")
public class ShortlistSchema {

    private final JdbcTemplate jdbc;
    private final String shortlistTable;
    private final String cataloguePlayerTable;

    public ShortlistSchema(
            JdbcTemplate jdbc,
            @Value("${fpl.shortlist.table-name}") String shortlistTable,
            @Value("${fpl.shortlist.catalogue-player-table-name}") String cataloguePlayerTable) {
        this.jdbc = jdbc;
        this.shortlistTable = shortlistTable;
        this.cataloguePlayerTable = cataloguePlayerTable;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    user_id UUID NOT NULL,
                    season VARCHAR(10) NOT NULL,
                    element_id INTEGER NOT NULL,
                    catalogue_id UUID NOT NULL,
                    web_name VARCHAR(100) NOT NULL,
                    full_name VARCHAR(200) NOT NULL,
                    team_id INTEGER NOT NULL,
                    team_short VARCHAR(10) NOT NULL,
                    team_name VARCHAR(100) NOT NULL,
                    element_type SMALLINT NOT NULL CHECK (element_type BETWEEN 1 AND 4),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (user_id, season, element_id),
                    FOREIGN KEY (catalogue_id, element_id)
                        REFERENCES %s(catalogue_id, element_id)
                )
                """.formatted(shortlistTable, cataloguePlayerTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_created ON %s(user_id, created_at)"
                .formatted(shortlistTable, shortlistTable));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(shortlistTable));
    }
}
