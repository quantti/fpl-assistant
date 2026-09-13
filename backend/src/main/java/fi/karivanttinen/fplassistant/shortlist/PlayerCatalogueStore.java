package fi.karivanttinen.fplassistant.shortlist;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates and reads immutable identity catalogues from coherent {@code fpl_daily_price} captures. */
@Component
public class PlayerCatalogueStore {

    public record Player(int elementId, String webName, String fullName, int teamId,
                         String teamShort, String teamName, int position, List<String> aliases) {
        public Player {
            aliases = List.copyOf(aliases);
        }
    }

    public record Catalogue(UUID id, String season, LocalDate sourcePriceDate,
                            List<Player> players) {
        public Catalogue {
            players = List.copyOf(players);
        }
    }

    private record Capture(String season, LocalDate date, Instant capturedAt) {
    }

    private record SourcePlayer(int elementId, String webName, String fullName, int teamId,
                                String teamShort, String teamName, int position,
                                Instant capturedAt) {
    }

    private record Alias(int elementId, String value, String key) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String dailyTable;
    private final String catalogueTable;
    private final String playerTable;
    private final String aliasTable;

    public PlayerCatalogueStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${fpl.daily-price.table-name}") String dailyTable,
            @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable,
            @Value("${fpl.shortlist.catalogue-player-table-name}") String playerTable,
            @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliasTable) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.dailyTable = dailyTable;
        this.catalogueTable = catalogueTable;
        this.playerTable = playerTable;
        this.aliasTable = aliasTable;
    }

    /** Returns a catalogue for the newest capture, or empty when it is absent or incoherent. */
    public Optional<Catalogue> latest() {
        return transaction.execute(status -> latestCapture().flatMap(this::createOrReuse));
    }

    /** Returns a catalogue for one exact daily capture; useful when a caller has already selected it. */
    public Optional<Catalogue> forCapture(String season, LocalDate date) {
        return transaction.execute(status -> capture(season, date).flatMap(this::createOrReuse));
    }

    /** Reads a persisted catalogue without consulting the mutable daily capture. */
    public Optional<Catalogue> find(UUID id) {
        List<Map<String, Object>> headers = jdbc.queryForList(
                "SELECT season, source_price_date FROM %s WHERE id = ?".formatted(catalogueTable), id);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> header = headers.getFirst();
        return Optional.of(new Catalogue(id, (String) header.get("season"),
                ((Date) header.get("source_price_date")).toLocalDate(), loadPlayers(id)));
    }

    private Optional<Capture> capture(String season, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT MAX(captured_at) AS captured_at FROM %s WHERE season = ? AND price_date = ?
                """.formatted(dailyTable), season, date);
        Object capturedAt = rows.getFirst().get("captured_at");
        return capturedAt == null ? Optional.empty()
                : Optional.of(new Capture(season, date, instant(capturedAt)));
    }

    private Optional<Capture> latestCapture() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT season, price_date, MAX(captured_at) AS captured_at
                FROM %s GROUP BY season, price_date
                ORDER BY price_date DESC, MAX(captured_at) DESC, season DESC LIMIT 1
                """.formatted(dailyTable));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new Capture((String) row.get("season"),
                ((Date) row.get("price_date")).toLocalDate(), instant(row.get("captured_at"))));
    }

    private Optional<Catalogue> createOrReuse(Capture capture) {
        List<SourcePlayer> players = jdbc.query("""
                SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                       element_type, captured_at
                FROM %s WHERE season = ? AND price_date = ? ORDER BY element_id
                """.formatted(dailyTable), (rs, rowNum) -> new SourcePlayer(
                rs.getInt("element_id"), rs.getString("web_name"), rs.getString("full_name"),
                rs.getInt("team_id"), rs.getString("team_short"), rs.getString("team_name"),
                rs.getInt("element_type"), rs.getObject("captured_at", OffsetDateTime.class).toInstant()),
                capture.season(), capture.date());
        if (!valid(players, capture.capturedAt())) {
            return Optional.empty();
        }

        List<Alias> aliases = aliases(players);
        String fingerprint = fingerprint(capture.season(), players, aliases);
        UUID proposed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s (id, season, source_price_date, fingerprint) VALUES (?, ?, ?, ?)
                ON CONFLICT (season, fingerprint) DO NOTHING
                """.formatted(catalogueTable), proposed, capture.season(), capture.date(), fingerprint);
        UUID id = jdbc.queryForObject(
                "SELECT id FROM %s WHERE season = ? AND fingerprint = ?".formatted(catalogueTable),
                UUID.class, capture.season(), fingerprint);

        if (proposed.equals(id)) {
            jdbc.batchUpdate("""
                    INSERT INTO %s (catalogue_id, element_id, web_name, full_name, team_id,
                                    team_short, team_name, element_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.formatted(playerTable), players.stream().map(player -> new Object[] {
                    id, player.elementId(), player.webName(), player.fullName(), player.teamId(),
                    player.teamShort(), player.teamName(), player.position()
            }).toList());
            jdbc.batchUpdate("""
                    INSERT INTO %s (catalogue_id, element_id, alias, alias_key) VALUES (?, ?, ?, ?)
                    """.formatted(aliasTable), aliases.stream().map(alias -> new Object[] {
                    id, alias.elementId(), alias.value(), alias.key()
            }).toList());
        }
        return find(id);
    }

    private static boolean valid(List<SourcePlayer> players, Instant capturedAt) {
        if (players.isEmpty()) {
            return false;
        }
        Map<Integer, String> clubs = new HashMap<>();
        for (SourcePlayer player : players) {
            if (player.elementId() <= 0 || player.teamId() <= 0
                    || player.position() < 1 || player.position() > 4
                    || blank(player.webName()) || blank(player.fullName())
                    || blank(player.teamShort()) || blank(player.teamName())
                    || !capturedAt.equals(player.capturedAt())) {
                return false;
            }
            String identity = player.teamShort().strip() + "\0" + player.teamName().strip();
            String previous = clubs.putIfAbsent(player.teamId(), identity);
            if (previous != null && !previous.equals(identity)) {
                return false;
            }
        }
        return true;
    }

    private static List<Alias> aliases(List<SourcePlayer> players) {
        Map<String, List<Alias>> byKey = new HashMap<>();
        for (SourcePlayer player : players) {
            Map<String, String> own = new LinkedHashMap<>();
            own.put(aliasKey(player.fullName()), player.fullName().strip());
            own.putIfAbsent(aliasKey(player.webName()), player.webName().strip());
            for (Map.Entry<String, String> entry : own.entrySet()) {
                byKey.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new Alias(player.elementId(), entry.getValue(), entry.getKey()));
            }
        }
        return byKey.values().stream()
                .filter(matches -> matches.size() == 1)
                .map(List::getFirst)
                .sorted(Comparator.comparingInt((Alias alias) -> alias.value().length()).reversed()
                        .thenComparing(Alias::key).thenComparingInt(Alias::elementId))
                .toList();
    }

    private List<Player> loadPlayers(UUID id) {
        Map<Integer, List<String>> aliases = new HashMap<>();
        List<Map<String, Object>> aliasRows = jdbc.queryForList("""
                SELECT element_id, alias FROM %s WHERE catalogue_id = ?
                ORDER BY length(alias) DESC, alias_key, element_id
                """.formatted(aliasTable), id);
        for (Map<String, Object> row : aliasRows) {
            aliases.computeIfAbsent(((Number) row.get("element_id")).intValue(),
                    ignored -> new ArrayList<>()).add((String) row.get("alias"));
        }
        return jdbc.query("""
                SELECT element_id, web_name, full_name, team_id, team_short, team_name, element_type
                FROM %s WHERE catalogue_id = ? ORDER BY element_id
                """.formatted(playerTable), (rs, rowNum) -> new Player(
                rs.getInt("element_id"), rs.getString("web_name"), rs.getString("full_name"),
                rs.getInt("team_id"), rs.getString("team_short"), rs.getString("team_name"),
                rs.getInt("element_type"), aliases.getOrDefault(rs.getInt("element_id"), List.of())), id);
    }

    private static String aliasKey(String alias) {
        return alias.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String fingerprint(String season, List<SourcePlayer> players, List<Alias> aliases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, season);
            for (SourcePlayer player : players) {
                add(digest, Integer.toString(player.elementId()));
                add(digest, player.webName().strip());
                add(digest, player.fullName().strip());
                add(digest, Integer.toString(player.teamId()));
                add(digest, player.teamShort().strip());
                add(digest, player.teamName().strip());
                add(digest, Integer.toString(player.position()));
            }
            for (Alias alias : aliases) {
                add(digest, Integer.toString(alias.elementId()));
                add(digest, alias.value());
                add(digest, alias.key());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalStateException("Unsupported captured_at value: " + value);
        };
    }
}
