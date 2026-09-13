package fi.karivanttinen.fplassistant.shortlist;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent schema for immutable player-identity catalogues derived from daily captures. */
@Component
public class PlayerCatalogueSchema {

    private final JdbcTemplate jdbc;
    private final String catalogueTable;
    private final String playerTable;
    private final String aliasTable;

    public PlayerCatalogueSchema(
            JdbcTemplate jdbc,
            @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable,
            @Value("${fpl.shortlist.catalogue-player-table-name}") String playerTable,
            @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliasTable) {
        this.jdbc = jdbc;
        this.catalogueTable = catalogueTable;
        this.playerTable = playerTable;
        this.aliasTable = aliasTable;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY,
                    season VARCHAR(10) NOT NULL,
                    source_price_date DATE NOT NULL,
                    fingerprint CHAR(64) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (season, fingerprint)
                )
                """.formatted(catalogueTable));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    catalogue_id UUID NOT NULL REFERENCES %s(id),
                    element_id INTEGER NOT NULL,
                    web_name VARCHAR(100) NOT NULL,
                    full_name VARCHAR(200) NOT NULL,
                    team_id INTEGER NOT NULL,
                    team_short VARCHAR(10) NOT NULL,
                    team_name VARCHAR(100) NOT NULL,
                    element_type SMALLINT NOT NULL CHECK (element_type BETWEEN 1 AND 4),
                    PRIMARY KEY (catalogue_id, element_id)
                )
                """.formatted(playerTable, catalogueTable));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    catalogue_id UUID NOT NULL,
                    element_id INTEGER NOT NULL,
                    alias VARCHAR(200) NOT NULL,
                    alias_key VARCHAR(200) NOT NULL,
                    PRIMARY KEY (catalogue_id, alias_key),
                    FOREIGN KEY (catalogue_id, element_id)
                        REFERENCES %s(catalogue_id, element_id)
                )
                """.formatted(aliasTable, playerTable));

        for (String table : new String[] {catalogueTable, playerTable, aliasTable}) {
            jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table));
        }
    }
}
