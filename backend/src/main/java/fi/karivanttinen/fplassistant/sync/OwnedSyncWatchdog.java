package fi.karivanttinen.fplassistant.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable five-minute sync watchdog and separately drainable notification outbox. */
public final class OwnedSyncWatchdog {

    static final Duration HEARTBEAT_LIMIT = Duration.ofMinutes(10);
    static final Duration DUE_GRACE = Duration.ofMinutes(5);
    static final Duration DELIVERY_LEASE = Duration.ofSeconds(15);
    static final int MAX_DELIVERY_ATTEMPTS = 3;
    static final int MAX_DELIVERIES_PER_DRAIN = 10;
    static final int MAX_WEBHOOK_BYTES = 16_384;
    private static final ZoneId UK = ZoneId.of("Europe/London");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OwnedSyncTables tables;
    private final ObjectMapper json;
    private final Notification notification;
    private final AlertSender sender;

    public OwnedSyncWatchdog(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
                             OwnedSyncTables tables, Notification notification,
                             AlertSender sender) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
        this.notification = Objects.requireNonNull(notification);
        this.sender = Objects.requireNonNull(sender);
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    /** Reads durable metadata only, then atomically opens incidents and verified recoveries. */
    public CheckResult check(String season) {
        Objects.requireNonNull(season);
        Instant now = clock.instant();
        Map<String, Signal> current = new LinkedHashMap<>();
        inspectRuntime(season, now, current);
        inspectRuns(season, now, current);
        inspectFixtures(season, now, current);
        inspectCaptures(season, now, current);
        inspectBudget(season, now, current);

        CheckResult result = tx.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                    (ResultSetExtractor<Void>) rs -> null, "fpl-sync-watchdog:" + season);
            Map<String, OpenIncident> open = openIncidents(season);
            Map<String, Integer> nextOccurrences = nextOccurrences(season);
            List<AlertEvent> events = new ArrayList<>();
            for (Signal signal : current.values()) {
                if (!open.containsKey(signal.key())) {
                    events.add(new AlertEvent(signal,
                            nextOccurrences.getOrDefault(signal.key(), 1), "INCIDENT"));
                }
            }
            int incidents = events.size();
            RecoveryEvidence recoveryEvidence = recoveryEvidence(season);
            for (OpenIncident incident : open.values()) {
                if (!current.containsKey(incident.key()) && verifiedRecovery(incident, recoveryEvidence)) {
                    Signal recovery = new Signal(incident.key(), incident.category(), "INFO",
                            incident.season(), incident.fixtureId(), Map.of(
                            "incidentCreatedAt", incident.createdAt().toString(),
                            "recoveredAt", now.toString(),
                            "nextAction", "No operator action; continue monitoring"));
                    events.add(new AlertEvent(recovery, incident.occurrence(), "RECOVERY"));
                }
            }
            insert(events, now);
            return new CheckResult(current.size(), incidents, events.size() - incidents);
        });
        subsystemHeartbeat("watchdog:" + season, now);
        return result;
    }

    /** Claims and sends a bounded batch. Delivery failures never roll back or block check(). */
    public int deliver() {
        // A restart with delivery disabled/unconfigured must not consume an older queued outbox.
        if (!"PENDING".equals(notification.state())) return 0;
        redriveUndeliverableOpenIncidents();
        int handled = 0;
        for (int i = 0; i < MAX_DELIVERIES_PER_DRAIN; i++) {
            Claimed claimed = claim();
            if (claimed == null) break;
            try {
                sender.send(claimed.payload());
                finishDelivery(claimed, null);
            } catch (Exception failure) {
                String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                finishDelivery(claimed, bounded(message));
            }
            handled++;
        }
        subsystemHeartbeat("delivery", clock.instant());
        return handled;
    }

    private void inspectRuntime(String season, Instant now, Map<String, Signal> signals) {
        Timestamp heartbeat = jdbc.queryForObject("SELECT max(heartbeat_at) FROM " + tables.plannerRuntime()
                + " WHERE instance_id NOT LIKE 'mode:%' AND instance_id NOT LIKE 'watchdog:%' "
                + "AND instance_id NOT LIKE 'delivery%'", Timestamp.class);
        if (heartbeat == null || heartbeat.toInstant().isBefore(now.minus(HEARTBEAT_LIMIT))) {
            put(signals, new Signal("runtime:" + season, "RUNTIME_STALLED", "CRITICAL", season,
                    null, Map.of("lastSuccessfulHeartbeat", heartbeat == null ? "never" : heartbeat.toInstant().toString(),
                    "thresholdMinutes", HEARTBEAT_LIMIT.toMinutes(),
                    "nextAction", "Inspect the owned scheduler process and durable queue")));
        }
    }

    private void inspectRuns(String season, Instant now, Map<String, Signal> signals) {
        jdbc.query("SELECT generation, kind, state, due_at, lease_expires_at, attempt_count, last_error "
                        + "FROM " + tables.run() + " WHERE season=? AND ((state='PENDING' AND due_at<=?) "
                        + "OR (state='LEASED' AND lease_expires_at<=?))",
                rs -> {
                    long generation = rs.getLong("generation");
                    boolean expired = "LEASED".equals(rs.getString("state"));
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("generation", generation);
                    details.put("kind", rs.getString("kind"));
                    details.put("dueAt", rs.getTimestamp("due_at").toInstant().toString());
                    details.put("retryCount", rs.getInt("attempt_count"));
                    details.put("lastError", Objects.toString(rs.getString("last_error"), "none"));
                    details.put("nextAction", expired ? "Allow fenced lease takeover and inspect the hung worker"
                            : "Inspect scheduler admission and request budget");
                    put(signals, new Signal((expired ? "expired-lease:" : "due-work:") + generation,
                            expired ? "EXPIRED_LEASE" : "DUE_WORK", expired ? "CRITICAL" : "WARNING",
                            season, null, details));
                }, season, Timestamp.from(now.minus(DUE_GRACE)), Timestamp.from(now));

        jdbc.query("SELECT r.generation,r.kind,r.publication_key,r.consecutive_fetch_failures,r.last_error,"
                        + "p.source_read_finished_at,p.published_at FROM " + tables.run() + " r LEFT JOIN "
                        + tables.receipt() + " p ON p.season=r.season AND p.publication_key=r.publication_key "
                        + "WHERE r.season=? AND r.state IN ('PENDING','FAILED') "
                        + "AND r.consecutive_fetch_failures>=3 AND NOT EXISTS (SELECT 1 FROM "
                        + tables.run() + " newer WHERE newer.season=r.season "
                        + "AND newer.publication_key=r.publication_key AND newer.generation>r.generation "
                        + "AND newer.state='COMPLETED')",
                rs -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("generation", rs.getLong("generation"));
                    details.put("kind", rs.getString("kind"));
                    details.put("publicationKey", rs.getString("publication_key"));
                    details.put("consecutiveFailedFetches", rs.getInt("consecutive_fetch_failures"));
                    details.put("lastError", Objects.toString(rs.getString("last_error"), "none"));
                    details.put("lastSuccessfulSourceRead", rs.getTimestamp("source_read_finished_at") == null
                            ? "never" : rs.getTimestamp("source_read_finished_at").toInstant().toString());
                    details.put("lastSuccessfulPublication", rs.getTimestamp("published_at") == null
                            ? "never" : rs.getTimestamp("published_at").toInstant().toString());
                    details.put("nextAction", "Inspect failure and use one epoch-guarded additive retry");
                    put(signals, new Signal("repeated-failure:" + season + ":" + rs.getString("publication_key"),
                            "REPEATED_FAILURE", "CRITICAL", season, null, details));
                }, season);
    }

    private void inspectFixtures(String season, Instant now, Map<String, Signal> signals) {
        jdbc.query("SELECT f.id, f.kickoff_time, s.state, s.expected_participants, "
                        + "s.received_participants, s.missing_player_ids, s.validation_failures, s.source_read_finished_at, "
                        + "s.attempt_count, s.next_due_at FROM fixture f JOIN season se ON se.id=f.season_id "
                        + "LEFT JOIN " + tables.fixtureSync() + " s ON s.season_id=f.season_id AND s.fixture_id=f.id "
                        + "WHERE se.code=? AND f.kickoff_time IS NOT NULL AND f.kickoff_time<=? "
                        + "AND (s.state IS NULL OR s.state<>'VERIFIED')",
                rs -> {
                    int fixture = rs.getInt("id");
                    Instant kickoff = rs.getTimestamp("kickoff_time").toInstant();
                    boolean escalated = !now.isBefore(kickoff.plus(Duration.ofHours(12)));
                    Integer expected = (Integer) rs.getObject("expected_participants");
                    Integer received = (Integer) rs.getObject("received_participants");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("fixtureId", fixture);
                    details.put("kickoff", kickoff.toString());
                    details.put("status", "verification overdue");
                    details.put("verificationState", Objects.toString(rs.getString("state"), "MISSING"));
                    details.put("expectedParticipants", expected == null ? "unknown" : expected);
                    details.put("receivedParticipants", received == null ? "unknown" : received);
                    details.put("missingCount", expected == null || received == null ? "unknown"
                            : Math.max(0, expected - received));
                    details.put("missingPlayerIds", arrayValues(rs.getArray("missing_player_ids")));
                    details.put("validationFailures", arrayValues(rs.getArray("validation_failures")));
                    details.put("lastSourceRead", rs.getTimestamp("source_read_finished_at") == null ? "never"
                            : rs.getTimestamp("source_read_finished_at").toInstant().toString());
                    details.put("retryCount", rs.getInt("attempt_count"));
                    details.put("nextDueAt", rs.getTimestamp("next_due_at") == null ? "none"
                            : rs.getTimestamp("next_due_at").toInstant().toString());
                    details.put("nextAction", escalated
                            ? "Escalate; retain daily reconciliation and use bounded operator retry"
                            : "Continue pending verification retries; do not mark the live result failed");
                    put(signals, new Signal((escalated ? "fixture-overdue-12h:" : "fixture-overdue:")
                            + season + ":" + fixture,
                            escalated ? "FIXTURE_VERIFICATION_12H" : "FIXTURE_VERIFICATION_OVERDUE",
                            escalated ? "CRITICAL" : "WARNING", season, fixture, details));
                }, season, Timestamp.from(now.minus(Duration.ofHours(3))));
    }

    private void inspectCaptures(String season, Instant now, Map<String, Signal> signals) {
        var local = now.atZone(UK);
        if (local.toLocalTime().isBefore(LocalTime.of(6, 0))) return;
        Instant dayStart = local.toLocalDate().atStartOfDay(UK).toInstant();
        for (String key : List.of(CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)) {
            Timestamp read = jdbc.queryForObject("SELECT max(source_read_finished_at) FROM " + tables.receipt()
                    + " WHERE season=? AND publication_key=?", Timestamp.class, season, key);
            if (read == null || read.toInstant().isBefore(dayStart)) {
                put(signals, new Signal("missed-capture:" + season + ":" + key,
                        "MISSED_DAILY_CAPTURE", "CRITICAL", season, null, Map.of(
                        "publicationKey", key,
                        "lastSourceRead", read == null ? "never" : read.toInstant().toString(),
                        "expectedSince", dayStart.toString(),
                        "nextAction", "Inspect the durable run and source/publication receipts")));
            }
        }
    }

    private void inspectBudget(String season, Instant now, Map<String, Signal> signals) {
        Long attempts = jdbc.queryForObject("SELECT count(*) FROM " + tables.requestAttempt()
                + " WHERE admitted_at>?", Long.class, Timestamp.from(now.minus(Duration.ofHours(24))));
        Timestamp cooldown = jdbc.queryForObject("SELECT cooldown_until FROM " + tables.requestState()
                + " WHERE singleton=true", Timestamp.class);
        Long due = jdbc.queryForObject("SELECT count(*) FROM " + tables.run()
                + " WHERE season=? AND state IN ('PENDING','LEASED') AND due_at<=?", Long.class,
                season, Timestamp.from(now));
        boolean exhausted = attempts != null && attempts >= 5_000;
        boolean blocked = due != null && due > 0 && cooldown != null && cooldown.toInstant().isAfter(now);
        if (exhausted || blocked) {
            put(signals, new Signal("budget:" + season, "REQUEST_BUDGET_BLOCKED",
                    exhausted ? "CRITICAL" : "WARNING", season, null, Map.of(
                    "attemptsLast24Hours", attempts == null ? 0 : attempts,
                    "dueWork", due == null ? 0 : due,
                    "cooldownUntil", cooldown == null ? "none" : cooldown.toInstant().toString(),
                    "nextAction", "Do not bypass admission; inspect delayed work and upstream response")));
        }
    }

    private Map<String, OpenIncident> openIncidents(String season) {
        Map<String, OpenIncident> result = new LinkedHashMap<>();
        jdbc.query("SELECT a.incident_key,a.occurrence,a.category,a.season,a.fixture_id,a.created_at "
                        + "FROM " + tables.alert() + " a WHERE a.event_type='INCIDENT' "
                        + "AND (a.season=? OR a.season IS NULL) AND NOT EXISTS (SELECT 1 FROM "
                        + tables.alert() + " r WHERE r.incident_key=a.incident_key "
                        + "AND r.occurrence=a.occurrence AND r.event_type='RECOVERY')",
                (RowCallbackHandler) rs -> result.put(rs.getString(1), new OpenIncident(
                        rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), rs.getTimestamp(6).toInstant())), season);
        return result;
    }

    private RecoveryEvidence recoveryEvidence(String season) {
        // A run fenced FAILED when its activation ended is settled too: that epoch is over and the
        // work was planned again under the next one, so it can never complete and must not hold a
        // due-work incident (and external health) open for ever. A failure inside the live
        // activation is different and stays open.
        Set<Long> completedRuns = Set.copyOf(jdbc.queryForList("SELECT r.generation FROM " + tables.run()
                + " r WHERE r.season=? AND (r.state='COMPLETED' OR (r.state='FAILED' AND NOT EXISTS ("
                + "SELECT 1 FROM " + tables.plannerRuntime() + " m WHERE m.instance_id=?"
                + " AND m.runtime_mode=r.runtime_mode AND m.runtime_epoch=r.runtime_epoch)))",
                Long.class, season, "mode:" + season));
        Set<Integer> verifiedFixtures = Set.copyOf(jdbc.queryForList("SELECT fixture_id FROM "
                + tables.fixtureSync() + " WHERE season_code=? AND state='VERIFIED' "
                + "AND current_verified_generation IS NOT NULL", Integer.class, season));
        Map<String, Instant> captureReads = new LinkedHashMap<>();
        jdbc.query("SELECT publication_key,max(source_read_finished_at) source_read_finished_at FROM "
                        + tables.receipt() + " WHERE season=? GROUP BY publication_key",
                (RowCallbackHandler) rs -> {
                    Timestamp read = rs.getTimestamp(2);
                    if (read != null) captureReads.put(rs.getString(1), read.toInstant());
                }, season);
        return new RecoveryEvidence(completedRuns, verifiedFixtures, captureReads,
                clock.instant().atZone(UK).toLocalDate().atStartOfDay(UK).toInstant());
    }

    private boolean verifiedRecovery(OpenIncident incident, RecoveryEvidence evidence) {
        if (incident.category().startsWith("FIXTURE_VERIFICATION")) {
            return incident.fixtureId() != null && evidence.verifiedFixtures().contains(incident.fixtureId());
        }
        if (incident.category().equals("MISSED_DAILY_CAPTURE")) {
            String publicationKey = incident.key().substring(incident.key().lastIndexOf(':') + 1);
            Instant read = evidence.captureReads().get(publicationKey);
            return read != null && !read.isBefore(evidence.dayStart());
        }
        if (incident.category().equals("DUE_WORK") || incident.category().equals("EXPIRED_LEASE")) {
            int separator = incident.key().lastIndexOf(':');
            try {
                return evidence.completedRuns().contains(
                        Long.parseLong(incident.key().substring(separator + 1)));
            } catch (RuntimeException invalidKey) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Integer> nextOccurrences(String season) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query("SELECT incident_key,max(occurrence)+1 FROM " + tables.alert()
                        + " WHERE season=? OR season IS NULL GROUP BY incident_key",
                (RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getInt(2)), season);
        return result;
    }

    private void insert(List<AlertEvent> events, Instant now) {
        if (events.isEmpty()) return;
        String state = notification.state();
        Timestamp nextDelivery = "PENDING".equals(state) ? Timestamp.from(now) : null;
        String error = notification.disabledReason();
        List<Object[]> rows = events.stream().map(event -> {
            Signal signal = event.signal();
            String payload = encode(Map.of(
                    "event", event.event().toLowerCase(), "incidentKey", signal.key(),
                    "category", signal.category(), "severity", signal.severity(),
                    "season", Objects.toString(signal.season(), ""),
                    "fixtureId", signal.fixtureId() == null ? "" : signal.fixtureId(),
                    "details", signal.details(), "observedAt", now.toString()));
            return new Object[] { signal.key(), event.occurrence(), event.event(), signal.category(),
                    signal.severity(), signal.season(), signal.fixtureId(), payload, state,
                    nextDelivery, error, Timestamp.from(now) };
        }).toList();
        jdbc.batchUpdate("INSERT INTO " + tables.alert()
                + "(incident_key,occurrence,event_type,category,severity,season,fixture_id,payload,"
                + "delivery_state,next_delivery_at,last_delivery_error,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)", rows);
    }

    /** Additively makes alerts from a repaired configuration deliverable without rewriting evidence. */
    private void redriveUndeliverableOpenIncidents() {
        tx.executeWithoutResult(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                    (ResultSetExtractor<Void>) rs -> null, "fpl-sync-alert-configuration-redrive");
            Instant now = clock.instant();
            List<Long> rows = jdbc.query("SELECT a.id FROM " + tables.alert() + " a "
                            + "WHERE a.event_type='INCIDENT' AND a.delivery_state IN ('UNCONFIGURED','DISABLED','EXTERNAL') "
                            + "AND NOT EXISTS (SELECT 1 FROM " + tables.alert() + " r "
                            + "WHERE r.incident_key=a.incident_key AND r.occurrence=a.occurrence "
                            + "AND r.event_type='RECOVERY') ORDER BY a.id FOR UPDATE",
                    (rs, row) -> rs.getLong(1));
            for (Long id : rows) {
                jdbc.update("UPDATE " + tables.alert() + " SET delivery_state='SUPERSEDED' WHERE id=?", id);
                jdbc.update("INSERT INTO " + tables.alert()
                                + "(incident_key,occurrence,event_type,category,severity,season,fixture_id,payload,"
                                + "delivery_state,next_delivery_at,created_at) SELECT incident_key,occurrence,'REDRIVE',"
                                + "category,severity,season,fixture_id,payload,'PENDING',?,? FROM " + tables.alert()
                                + " WHERE id=? ON CONFLICT DO NOTHING",
                        Timestamp.from(now), Timestamp.from(now), id);
            }
        });
    }

    private Claimed claim() {
        return tx.execute(status -> {
            Instant now = clock.instant();
            jdbc.update("UPDATE " + tables.alert() + " SET delivery_state='FAILED',delivery_token=NULL,"
                    + "delivery_lease_until=NULL,last_delivery_error='Delivery lease expired after final attempt' "
                    + "WHERE delivery_state='DELIVERING' AND delivery_attempts>=? AND delivery_lease_until<=?",
                    MAX_DELIVERY_ATTEMPTS, Timestamp.from(now));
            List<Claimed> rows = jdbc.query("SELECT a.id,a.payload::text,a.delivery_attempts FROM " + tables.alert()
                            + " a WHERE (((a.delivery_state IN ('PENDING','FAILED') AND a.delivery_attempts<? "
                            + "AND (a.next_delivery_at IS NULL OR a.next_delivery_at<=?)) OR "
                            + "(a.delivery_state='DELIVERING' AND a.delivery_attempts<? AND a.delivery_lease_until<=?))) "
                            + "AND NOT EXISTS (SELECT 1 FROM " + tables.alert() + " earlier "
                            + "WHERE earlier.incident_key=a.incident_key AND earlier.occurrence=a.occurrence "
                            + "AND (CASE earlier.event_type WHEN 'INCIDENT' THEN 0 WHEN 'REDRIVE' THEN 1 ELSE 2 END "
                            + "< CASE a.event_type WHEN 'INCIDENT' THEN 0 WHEN 'REDRIVE' THEN 1 ELSE 2 END "
                            + "OR (earlier.event_type=a.event_type AND earlier.id<a.id)) "
                            + "AND earlier.delivery_state NOT IN ('DELIVERED','SUPERSEDED')) "
                            + "ORDER BY a.created_at,a.id LIMIT 1 FOR UPDATE OF a SKIP LOCKED",
                    (rs, row) -> new Claimed(rs.getLong(1), rs.getString(2),
                            rs.getInt(3) + 1, UUID.randomUUID()), MAX_DELIVERY_ATTEMPTS,
                    Timestamp.from(now), MAX_DELIVERY_ATTEMPTS, Timestamp.from(now));
            if (rows.isEmpty()) return null;
            Claimed claimed = rows.getFirst();
            jdbc.update("UPDATE " + tables.alert() + " SET delivery_state='DELIVERING',"
                            + "delivery_attempts=?,delivery_token=?,delivery_lease_until=?,last_delivery_error=NULL WHERE id=?",
                    claimed.attempt(), claimed.token(), Timestamp.from(now.plus(DELIVERY_LEASE)), claimed.id());
            return claimed;
        });
    }

    private void finishDelivery(Claimed claimed, String error) {
        Instant now = clock.instant();
        boolean exhausted = claimed.attempt() >= MAX_DELIVERY_ATTEMPTS;
        String state = error == null ? "DELIVERED" : "FAILED";
        Instant next = error == null || exhausted ? null : now.plus(Duration.ofMinutes(claimed.attempt()));
        jdbc.update("UPDATE " + tables.alert() + " SET delivery_state=?,delivered_at=?,"
                        + "next_delivery_at=?,last_delivery_error=?,delivery_token=NULL,delivery_lease_until=NULL "
                        + "WHERE id=? AND delivery_state='DELIVERING' AND delivery_token=?",
                state, error == null ? Timestamp.from(now) : null,
                next == null ? null : Timestamp.from(next), error, claimed.id(), claimed.token());
    }

    private String encode(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
                throw new IllegalArgumentException("watchdog payload exceeds bound");
            }
            return encoded;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not encode watchdog alert", failure);
        }
    }

    private void subsystemHeartbeat(String instance, Instant now) {
        jdbc.update("INSERT INTO " + tables.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?) ON CONFLICT (instance_id) "
                        + "DO UPDATE SET heartbeat_at=EXCLUDED.heartbeat_at",
                instance, Timestamp.from(now), Timestamp.from(now));
    }

    private static List<Object> arrayValues(Array array) throws SQLException {
        if (array == null) return List.of();
        return List.of((Object[]) array.getArray());
    }

    private static void put(Map<String, Signal> signals, Signal signal) { signals.put(signal.key(), signal); }
    private static String bounded(String value) { return value.substring(0, Math.min(value.length(), 1_000)); }

    public interface AlertSender { void send(String payload) throws Exception; }

    /** Production sender accepts HTTPS only. Tests inject a loopback sender directly. */
    public static final class HttpsWebhookSender implements AlertSender {
        private final HttpClient client;
        private final URI destination;

        public HttpsWebhookSender(String destination) {
            this(URI.create(Objects.requireNonNull(destination)), HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build(), false);
        }

        HttpsWebhookSender(URI destination, HttpClient client, boolean allowLoopbackForTest) {
            boolean loopbackTest = allowLoopbackForTest && "http".equalsIgnoreCase(destination.getScheme())
                    && destination.getHost() != null
                    && (destination.getHost().equals("127.0.0.1") || destination.getHost().equals("localhost"));
            if ((!"https".equalsIgnoreCase(destination.getScheme()) && !loopbackTest)
                    || destination.getHost() == null || destination.getUserInfo() != null
                    || destination.getRawQuery() != null || destination.getRawFragment() != null) {
                throw new IllegalArgumentException("Owner webhook must be an HTTPS URL without userinfo, query or fragment");
            }
            this.destination = destination;
            this.client = Objects.requireNonNull(client);
        }

        @Override public void send(String payload) throws Exception {
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
                throw new IllegalArgumentException("watchdog payload exceeds bound");
            }
            HttpRequest request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Owner webhook returned HTTP " + response.statusCode());
            }
        }
    }

    public record Notification(boolean enabled, boolean configured, boolean externalMonitor) {
        public Notification(boolean enabled, boolean configured) {
            this(enabled, configured, false);
        }
        public Notification {
            if (externalMonitor && enabled) {
                throw new IllegalArgumentException("Choose either webhook or external-monitor notification");
            }
        }
        String state() { return externalMonitor ? "EXTERNAL"
                : !enabled ? "DISABLED" : configured ? "PENDING" : "UNCONFIGURED"; }
        String disabledReason() { return externalMonitor
                ? "External monitor owns notification delivery; application cannot attest delivery"
                : enabled ? configured ? null : "Owner webhook is unconfigured"
                : "Owner webhook delivery is disabled"; }
    }
    public record CheckResult(int activeSignals, int incidentsOpened, int recoveriesOpened) { }
    private record Signal(String key, String category, String severity, String season,
                          Integer fixtureId, Map<String, Object> details) { }
    private record OpenIncident(String key, int occurrence, String category, String season,
                                Integer fixtureId, Instant createdAt) { }
    private record AlertEvent(Signal signal, int occurrence, String event) { }
    private record RecoveryEvidence(Set<Long> completedRuns, Set<Integer> verifiedFixtures,
                                    Map<String, Instant> captureReads, Instant dayStart) { }
    private record Claimed(long id, String payload, int attempt, UUID token) { }
}
