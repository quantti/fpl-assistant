package fi.karivanttinen.fplassistant.shortlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists desired shortlist membership, always scoped to the JWT-derived owner. */
@Service
public class ShortlistStore {

    public record Player(UUID catalogueId, String season, int elementId, String webName,
                         String fullName, int teamId, String teamShort, String teamName,
                         int elementType, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final PlayerCatalogueStore catalogues;
    private final String shortlistTable;

    public ShortlistStore(JdbcTemplate jdbc, PlayerCatalogueStore catalogues,
                          @Value("${fpl.shortlist.table-name}") String shortlistTable) {
        this.jdbc = jdbc;
        this.catalogues = catalogues;
        this.shortlistTable = shortlistTable;
    }

    /**
     * Makes the referenced player present. Empty means the catalogue, its season, or its player
     * did not match; no client-supplied display field is accepted or persisted.
     */
    @Transactional
    public Optional<Player> put(UUID userId, UUID catalogueId, String season, int elementId) {
        Optional<PlayerCatalogueStore.Catalogue> found = catalogues.find(catalogueId);
        if (found.isEmpty() || !found.orElseThrow().season().equals(season)) {
            return Optional.empty();
        }
        Optional<PlayerCatalogueStore.Player> authoritative = found.orElseThrow().players().stream()
                .filter(player -> player.elementId() == elementId)
                .findFirst();
        if (authoritative.isEmpty()) {
            return Optional.empty();
        }
        PlayerCatalogueStore.Player player = authoritative.orElseThrow();
        jdbc.update("""
                INSERT INTO %s (user_id, season, element_id, catalogue_id, web_name, full_name,
                                team_id, team_short, team_name, element_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, season, element_id) DO UPDATE SET
                    catalogue_id = EXCLUDED.catalogue_id,
                    web_name = EXCLUDED.web_name,
                    full_name = EXCLUDED.full_name,
                    team_id = EXCLUDED.team_id,
                    team_short = EXCLUDED.team_short,
                    team_name = EXCLUDED.team_name,
                    element_type = EXCLUDED.element_type
                """.formatted(shortlistTable), userId, season, elementId, catalogueId,
                player.webName(), player.fullName(), player.teamId(), player.teamShort(),
                player.teamName(), player.position());
        return find(userId, season, elementId);
    }

    public List<Player> list(UUID userId) {
        return jdbc.query("""
                SELECT catalogue_id, season, element_id, web_name, full_name, team_id,
                       team_short, team_name, element_type, created_at
                FROM %s WHERE user_id = ?
                ORDER BY lower(web_name), season DESC, element_id
                """.formatted(shortlistTable), (rs, row) -> map(rs), userId);
    }

    /** Makes membership absent. Repeating the same deletion is a successful no-op. */
    public void delete(UUID userId, String season, int elementId) {
        jdbc.update("DELETE FROM %s WHERE user_id = ? AND season = ? AND element_id = ?"
                .formatted(shortlistTable), userId, season, elementId);
    }

    private Optional<Player> find(UUID userId, String season, int elementId) {
        return jdbc.query("""
                SELECT catalogue_id, season, element_id, web_name, full_name, team_id,
                       team_short, team_name, element_type, created_at
                FROM %s WHERE user_id = ? AND season = ? AND element_id = ?
                """.formatted(shortlistTable), (rs, row) -> map(rs), userId, season, elementId)
                .stream().findFirst();
    }

    private static Player map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Player(rs.getObject("catalogue_id", UUID.class), rs.getString("season"),
                rs.getInt("element_id"), rs.getString("web_name"), rs.getString("full_name"),
                rs.getInt("team_id"), rs.getString("team_short"), rs.getString("team_name"),
                rs.getInt("element_type"), rs.getTimestamp("created_at").toInstant());
    }
}
