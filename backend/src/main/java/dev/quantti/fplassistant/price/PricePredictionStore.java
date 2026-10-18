package dev.quantti.fplassistant.price;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.price.PricePredictionSchema.Tables;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.*;

public final class PricePredictionStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final Tables tables;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public record Lease(long token, Instant expiresAt) { }
    public enum PublicationResult { REPLACED, CHECKED, REJECTED }
    public record Publication(long generation, Instant checkedAt, Instant changedAt, String fingerprint) { }
    public record ForecastView(PricePredictionSnapshot snapshot, Publication publication,
                               Instant previousTarget, int readyCount) { }
    public record NamedChange(Change change, String webName, String fullName, Team eventTeam) { }
    public record RecentView(RecentChanges snapshot, Publication publication, List<NamedChange> namedChanges) {
        public RecentView {
            namedChanges = List.copyOf(namedChanges);
        }
    }

    public PricePredictionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, Tables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tables = Objects.requireNonNull(tables);
        write = transaction(transactions, false);
        read = transaction(transactions, true);
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        template.setTimeout(10);
        return template;
    }

    public void enablePublishing() {
        write.executeWithoutResult(status -> jdbc.update("UPDATE " + tables.forecastCapture()
                + " SET enabled = true WHERE id = 1 AND NOT enabled"));
    }

    public void revokeAndDisable() {
        write.executeWithoutResult(status -> jdbc.update("UPDATE " + tables.forecastCapture()
                + " SET enabled = false, fence = fence + 1, lease_until = NULL WHERE id = 1"));
    }

    public Optional<Lease> acquireLease(Duration ttl) {
        Objects.requireNonNull(ttl);
        if (ttl.compareTo(Duration.ofMillis(1)) < 0 || ttl.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("Lease TTL must be between 1ms and five minutes");
        }
        return write.execute(status -> jdbc.query("UPDATE " + tables.forecastCapture() + """
                 SET fence = fence + 1, lease_until = clock_timestamp() + (? * interval '1 millisecond')
                 WHERE id = 1 AND enabled AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                 RETURNING fence, lease_until
                """, (rs, row) -> new Lease(rs.getLong("fence"), instant(rs, "lease_until")),
                ttl.toMillis()).stream().findFirst());
    }

    public void release(Lease lease) {
        write.executeWithoutResult(status -> jdbc.update("UPDATE " + tables.forecastCapture()
                + " SET lease_until = NULL WHERE id = 1 AND fence = ?", lease.token()));
    }

    public PublicationResult publishForecast(Lease lease, String fingerprint,
                                             PricePredictionSnapshot snapshot, BooleanSupplier permitted) {
        fingerprint(fingerprint);
        validate(snapshot);
        return publish(lease, permitted, () -> {
            var previous = forecastMetadata();
            if (!newer(snapshot.fetchedAt(), previous.publication().checkedAt())) return PublicationResult.REJECTED;
            if (fingerprint.equals(previous.publication().fingerprint())
                    && snapshot.season().equals(previous.season())) {
                jdbc.update("UPDATE " + tables.forecastCapture() + " SET checked_at = ? WHERE id = 1",
                        timestamp(snapshot.fetchedAt()));
                return PublicationResult.CHECKED;
            }
            long generation = previous.publication().generation() + 1;
            jdbc.update("DELETE FROM " + tables.forecastPlayer());
            jdbc.batchUpdate("INSERT INTO " + tables.forecastPlayer() + """
                     (season, element_id, generation, team_id, web_name, full_name, team_name, team_short,
                      position, current_price, state, progress, projected_progress, likelihood, signal, locked_until)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.players().values(), 250, (ps, player) -> {
                Team team = snapshot.teams().get(player.teamId());
                Forecast forecast = player.forecast();
                Object[] values = {snapshot.season(), player.elementId(), generation, team.id(), player.webName(),
                        player.fullName(), team.name(), team.shortName(), player.position(), player.currentPrice(),
                        forecast.state().name(), forecast.progress(), forecast.projectedProgress(),
                        forecast.likelihood(), forecast.signal() == null ? null : forecast.signal().name(),
                        timestamp(forecast.lockedUntil())};
                for (int index = 0; index < values.length; index++) ps.setObject(index + 1, values[index]);
            });
            Instant previousTarget = Objects.equals(previous.target(), snapshot.target())
                    ? previous.previousTarget() : previous.target();
            int ready = (int) snapshot.players().values().stream()
                    .filter(player -> player.forecast().state() == State.READY).count();
            jdbc.update("UPDATE " + tables.forecastCapture() + """
                     SET generation = ?, season = ?, fetched_at = ?, checked_at = ?, source_updated_at = ?,
                         target = ?, previous_target = ?, fingerprint = ?, changed_at = clock_timestamp(),
                         player_count = ?, ready_count = ?, teams = ?::jsonb WHERE id = 1
                    """, generation, snapshot.season(), timestamp(snapshot.fetchedAt()), timestamp(snapshot.fetchedAt()),
                    timestamp(snapshot.sourceUpdatedAt()), timestamp(snapshot.target()), timestamp(previousTarget),
                    fingerprint, snapshot.players().size(), ready, encodeTeams(snapshot.teams()));
            return PublicationResult.REPLACED;
        });
    }

    public PublicationResult publishRecentChanges(Lease lease, String fingerprint, RecentChanges changes,
                                                  PricePredictionSnapshot identity, BooleanSupplier permitted) {
        fingerprint(fingerprint);
        validate(identity);
        if (!changes.season().equals(identity.season()) || !changes.identityFetchedAt().equals(identity.fetchedAt())
                || changes.fetchedAt().isBefore(identity.fetchedAt())
                || Duration.between(identity.fetchedAt(), changes.fetchedAt()).compareTo(Duration.ofMinutes(10)) > 0
                || changes.changes().size() > 10000) {
            throw new IllegalArgumentException("History requires its bounded companion identity snapshot");
        }
        for (Change change : changes.changes()) {
            if (!identity.players().containsKey(change.elementId()) || !identity.teams().containsKey(change.teamId())
                    || change.changedAt().isAfter(changes.fetchedAt())) {
                throw new IllegalArgumentException("History identity or event time is inconsistent");
            }
        }
        return publish(lease, permitted, () -> {
            var previous = historyMetadata();
            if (!newer(changes.fetchedAt(), previous.publication().checkedAt())) return PublicationResult.REJECTED;
            if (fingerprint.equals(previous.publication().fingerprint()) && changes.season().equals(previous.season())) {
                jdbc.update("UPDATE " + tables.changeCapture() + " SET checked_at = ? WHERE id = 1",
                        timestamp(changes.fetchedAt()));
                return PublicationResult.CHECKED;
            }
            long generation = previous.publication().generation() + 1;
            jdbc.update("DELETE FROM " + tables.changeEvent());
            jdbc.batchUpdate("INSERT INTO " + tables.changeEvent() + """
                     (season, element_id, generation, team_id, web_name, full_name, team_name, team_short,
                      gameweek, changed_at, price_before, price_after) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, changes.changes(), 250, (ps, change) -> {
                Player player = identity.players().get(change.elementId());
                Team team = identity.teams().get(change.teamId());
                Object[] values = {changes.season(), change.elementId(), generation, change.teamId(),
                        player.webName(), player.fullName(), team.name(), team.shortName(), change.gameweek(),
                        timestamp(change.changedAt()), change.priceBefore(), change.priceAfter()};
                for (int index = 0; index < values.length; index++) ps.setObject(index + 1, values[index]);
            });
            Instant first = changes.changes().stream().map(Change::changedAt).min(Instant::compareTo).orElse(null);
            Instant last = changes.changes().stream().map(Change::changedAt).max(Instant::compareTo).orElse(null);
            jdbc.update("UPDATE " + tables.changeCapture() + """
                     SET generation = ?, season = ?, fetched_at = ?, checked_at = ?, identity_fetched_at = ?,
                         fingerprint = ?, changed_at = clock_timestamp(), event_count = ?, earliest_event = ?,
                         latest_event = ? WHERE id = 1
                    """, generation, changes.season(), timestamp(changes.fetchedAt()), timestamp(changes.fetchedAt()),
                    timestamp(changes.identityFetchedAt()), fingerprint, changes.changes().size(), timestamp(first), timestamp(last));
            return PublicationResult.REPLACED;
        });
    }

    private PublicationResult publish(Lease lease, BooleanSupplier permitted, Supplier<PublicationResult> action) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(permitted);
        return write.execute(status -> {
            jdbc.queryForObject("SELECT fence FROM " + tables.forecastCapture() + " WHERE id = 1 FOR UPDATE", Long.class);
            if (!live(lease) || !permitted.getAsBoolean()) return PublicationResult.REJECTED;
            PublicationResult result = action.get();
            if (!live(lease) || !permitted.getAsBoolean()) {
                status.setRollbackOnly();
                return PublicationResult.REJECTED;
            }
            return result;
        });
    }

    private boolean live(Lease lease) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled AND fence = ?"
                + " AND lease_until > clock_timestamp() FROM " + tables.forecastCapture() + " WHERE id = 1",
                Boolean.class, lease.token()));
    }

    public boolean leaseIsLive(Lease lease) {
        return live(lease);
    }

    public boolean publishingEnabled() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM " + tables.forecastCapture()
                + " WHERE id = 1", Boolean.class));
    }

    public Optional<PricePredictionSnapshot> readForecast() {
        return readForecastView().map(ForecastView::snapshot);
    }

    public Optional<ForecastView> readForecastView() {
        return readForecastView(Function.identity());
    }

    public <Result> Result readForecastView(Function<Optional<ForecastView>, Result> reader) {
        return read.execute(status -> reader.apply(forecastView()));
    }

    private Optional<ForecastView> forecastView() {
        var meta = forecastMetadata();
        if (meta.publication().generation() == 0) return Optional.empty();
        Map<Integer, Player> players = new HashMap<>();
        jdbc.query("SELECT * FROM " + tables.forecastPlayer() + " WHERE generation = ? AND season = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String signal = rs.getString("signal");
                    var forecast = new Forecast(State.valueOf(rs.getString("state")), rs.getBigDecimal("progress"),
                            rs.getBigDecimal("projected_progress"), rs.getObject("likelihood", Integer.class),
                            signal == null ? null : Signal.valueOf(signal), instant(rs, "locked_until"));
                    var player = new Player(rs.getInt("element_id"), rs.getInt("team_id"), rs.getInt("position"),
                            rs.getString("web_name"), rs.getString("full_name"), rs.getInt("current_price"), forecast);
                    players.put(player.elementId(), player);
                }, meta.publication().generation(), meta.season());
        if (players.size() != meta.count()) throw new IllegalStateException("Incomplete forecast generation");
        var snapshot = new PricePredictionSnapshot(meta.season(), meta.fetchedAt(), meta.sourceUpdatedAt(),
                meta.target(), decodeTeams(meta.teams()), players);
        return Optional.of(new ForecastView(snapshot, meta.publication(), meta.previousTarget(), meta.readyCount()));
    }

    public Optional<RecentChanges> readRecentChanges() {
        return readRecentView().map(RecentView::snapshot);
    }

    public Optional<RecentView> readRecentView() {
        return readRecentView(Function.identity());
    }

    public <Result> Result readRecentView(Function<Optional<RecentView>, Result> reader) {
        return read.execute(status -> reader.apply(recentView()));
    }

    private Optional<RecentView> recentView() {
        var meta = historyMetadata();
        if (meta.publication().generation() == 0) return Optional.empty();
        List<NamedChange> rows = jdbc.query("SELECT * FROM " + tables.changeEvent()
                + " WHERE generation = ? AND season = ? ORDER BY changed_at, element_id", (rs, row) -> {
                    var change = new Change(rs.getInt("element_id"), rs.getInt("team_id"), rs.getInt("gameweek"),
                            instant(rs, "changed_at"), rs.getInt("price_before"), rs.getInt("price_after"));
                    return new NamedChange(change, rs.getString("web_name"), rs.getString("full_name"),
                            new Team(change.teamId(), rs.getString("team_name"), rs.getString("team_short")));
                }, meta.publication().generation(), meta.season());
        if (rows.size() != meta.count()) throw new IllegalStateException("Incomplete recent-change generation");
        var snapshot = new RecentChanges(meta.season(), meta.fetchedAt(), meta.identityFetchedAt(),
                rows.stream().map(NamedChange::change).toList());
        return Optional.of(new RecentView(snapshot, meta.publication(), rows));
    }

    private record ForecastMetadata(Publication publication, String season, Instant fetchedAt, Instant sourceUpdatedAt,
                                    Instant target, Instant previousTarget, int count, int readyCount, String teams) { }
    private record HistoryMetadata(Publication publication, String season, Instant fetchedAt,
                                  Instant identityFetchedAt, int count) { }

    private ForecastMetadata forecastMetadata() {
        return jdbc.queryForObject("SELECT * FROM " + tables.forecastCapture() + " WHERE id = 1", (rs, row) ->
                new ForecastMetadata(publication(rs), rs.getString("season"), instant(rs, "fetched_at"),
                        instant(rs, "source_updated_at"), instant(rs, "target"), instant(rs, "previous_target"),
                        rs.getInt("player_count"), rs.getInt("ready_count"), rs.getString("teams")));
    }

    private HistoryMetadata historyMetadata() {
        return jdbc.queryForObject("SELECT * FROM " + tables.changeCapture() + " WHERE id = 1", (rs, row) ->
                new HistoryMetadata(publication(rs), rs.getString("season"), instant(rs, "fetched_at"),
                        instant(rs, "identity_fetched_at"), rs.getInt("event_count")));
    }

    private static Publication publication(ResultSet rs) throws SQLException {
        return new Publication(rs.getLong("generation"), instant(rs, "checked_at"),
                instant(rs, "changed_at"), rs.getString("fingerprint"));
    }

    private static boolean newer(Instant next, Instant previous) {
        return previous == null || next.isAfter(previous);
    }

    private static void fingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected lowercase SHA-256 payload fingerprint");
        }
    }

    private static void validate(PricePredictionSnapshot snapshot) {
        Objects.requireNonNull(snapshot.fetchedAt());
        if (!snapshot.season().matches("[0-9]{4}-[0-9]{2}") || snapshot.teams().size() != 20
                || snapshot.players().isEmpty() || snapshot.players().size() > 5000) {
            throw new IllegalArgumentException("Invalid bounded forecast snapshot");
        }
        snapshot.teams().forEach((id, team) -> {
            if (id != team.id()) throw new IllegalArgumentException("Inconsistent team identity");
        });
        snapshot.players().forEach((id, player) -> {
            if (id != player.elementId() || !snapshot.teams().containsKey(player.teamId())) {
                throw new IllegalArgumentException("Inconsistent player identity");
            }
        });
    }

    private static String encodeTeams(Map<Integer, Team> teams) {
        try {
            return JSON.writeValueAsString(teams.values());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot encode teams", exception);
        }
    }

    private static Map<Integer, Team> decodeTeams(String teams) {
        try {
            return Arrays.stream(JSON.readValue(teams, Team[].class))
                    .collect(Collectors.toUnmodifiableMap(Team::id, team -> team));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored teams", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
