package fi.karivanttinen.fplassistant.price;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fi.karivanttinen.fplassistant.price.PricePredictionStore.*;

public final class PricePredictionWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PricePredictionWorker.class);
    private final PricePredictionStore store;
    private final PricePredictionPermission permission;
    private final PricePredictionHttp.Fetcher http;
    private final Clock clock;
    private final String season;
    private final URI bootstrap;
    private final URI history;
    private final ScheduledExecutorService scheduler;
    private Instant consumedSlot;
    private Instant cooldown = Instant.MIN;
    private ForecastView view;
    private Pending pending;
    private boolean revoked;
    private EndpointStatus forecastStatus = new EndpointStatus(null, null, null, 0);
    private EndpointStatus historyStatus = new EndpointStatus(null, null, null, 0);

    public record EndpointStatus(Instant lastAttempt, Instant lastSuccess, String error, int consecutiveFailures) { }
    public record Status(EndpointStatus forecast, EndpointStatus history, PricePredictionCadence.Phase phase,
                         Instant checkedAt, Instant fetchedAt, Instant sourceUpdatedAt, int usablePlayers,
                         Instant cooldownUntil, boolean reconciliationRequired, boolean permitted) { }
    private record Pending(boolean forecast, String fingerprint, Instant checkedAt) { }

    public PricePredictionWorker(PricePredictionStore store, PricePredictionPermission permission,
                                 PricePredictionHttp.Fetcher http, Clock clock, String season,
                                 URI bootstrap, URI history) {
        this.store = store;
        this.permission = permission;
        this.http = http;
        this.clock = clock;
        this.season = season;
        this.bootstrap = bootstrap;
        this.history = history;
        consumedSlot = PricePredictionCadence.slot(clock.instant());
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "price-prediction-capture");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException exception) {
            LOG.warn("Price prediction worker control failure: {}", exception.getClass().getSimpleName());
        }
    }

    public synchronized void tick() {
        if (!permission.permitted()) {
            revoke();
            return;
        }
        Instant now = clock.instant();
        Instant slot = PricePredictionCadence.slot(now);
        if (!slot.isAfter(consumedSlot) || now.isBefore(cooldown)
                || !PricePredictionCadence.due(now, PricePredictionCadence.Phase.FREQUENT)) return;
        consumedSlot = slot;
        try {
            reconcile();
            view = store.readForecastView().orElse(null);
            if (!PricePredictionCadence.due(now, PricePredictionCadence.phase(now, view))) return;
            var lease = store.acquireLease(Duration.ofMinutes(2));
            if (lease.isEmpty()) return;
            try {
                capture(lease.get());
                if (pending == null) view = store.readForecastView().orElse(null);
            } finally {
                try {
                    if (!permission.permitted()) revoke();
                } finally {
                    store.release(lease.get());
                }
            }
        } catch (RuntimeException exception) {
            failure(pending == null || pending.forecast(), "DATABASE", Duration.ofMinutes(10));
        }
    }

    private void capture(Lease lease) {
        PricePredictionSnapshot identity;
        try {
            byte[] bytes = request(lease, true, bootstrap);
            if (bytes == null) return;
            identity = PricePredictionParser.parse(bytes, season, clock.instant());
            pending = new Pending(true, fingerprint(bytes), identity.fetchedAt());
            var result = store.publishForecast(lease, pending.fingerprint(), identity, permission::permitted);
            pending = null;
            if (result == PublicationResult.REJECTED) {
                failure(true, "PUBLICATION_REJECTED", Duration.ofMinutes(10));
                return;
            }
            success(true, identity.fetchedAt());
        } catch (IllegalArgumentException exception) {
            failure(true, "INVALID_BOOTSTRAP", Duration.ofMinutes(10));
            return;
        } catch (RuntimeException exception) {
            failure(true, "PUBLICATION_UNCERTAIN", Duration.ofMinutes(10));
            return;
        }
        try {
            byte[] bytes = request(lease, false, history);
            if (bytes == null) return;
            var changes = PricePredictionParser.parseRecentChanges(bytes, identity, clock.instant());
            pending = new Pending(false, fingerprint(bytes), changes.fetchedAt());
            var result = store.publishRecentChanges(lease, pending.fingerprint(), changes, identity,
                    permission::permitted);
            pending = null;
            if (result == PublicationResult.REJECTED) failure(false, "PUBLICATION_REJECTED", Duration.ofMinutes(10));
            else success(false, changes.fetchedAt());
        } catch (IllegalArgumentException exception) {
            failure(false, "INVALID_HISTORY", Duration.ofMinutes(10));
        } catch (RuntimeException exception) {
            failure(false, "PUBLICATION_UNCERTAIN", Duration.ofMinutes(10));
        }
    }

    private byte[] request(Lease lease, boolean forecast, URI uri) {
        if (!permission.permitted() || !store.leaseIsLive(lease)) return null;
        attempt(forecast);
        try {
            var response = http.get(uri);
            if (!permission.permitted() || !store.leaseIsLive(lease)) return null;
            if (response.status() != 200) {
                failure(forecast, "HTTP_" + response.status(), retryDelay(response.retryAfter(), clock.instant()));
                return null;
            }
            return response.body();
        } catch (Exception exception) {
            failure(forecast, "HTTP_FAILURE", Duration.ofMinutes(10));
            return null;
        }
    }

    private void reconcile() {
        if (pending == null) return;
        Publication publication = pending.forecast()
                ? store.readForecastView().map(ForecastView::publication).orElse(null)
                : store.readRecentView().map(RecentView::publication).orElse(null);
        if (publication != null && pending.fingerprint().equals(publication.fingerprint())
                && !publication.checkedAt().isBefore(pending.checkedAt())) {
            success(pending.forecast(), publication.checkedAt());
        }
        pending = null;
    }

    private void attempt(boolean forecast) {
        var previous = forecast ? forecastStatus : historyStatus;
        var next = new EndpointStatus(clock.instant(), previous.lastSuccess(), previous.error(), previous.consecutiveFailures());
        if (forecast) forecastStatus = next;
        else historyStatus = next;
    }

    private void success(boolean forecast, Instant checkedAt) {
        var previous = forecast ? forecastStatus : historyStatus;
        var next = new EndpointStatus(previous.lastAttempt(), checkedAt, null, 0);
        if (forecast) forecastStatus = next;
        else historyStatus = next;
    }

    private void failure(boolean forecast, String error, Duration delay) {
        var previous = forecast ? forecastStatus : historyStatus;
        var next = new EndpointStatus(previous.lastAttempt(), previous.lastSuccess(), error,
                Math.min(1000000, previous.consecutiveFailures() + 1));
        if (forecast) forecastStatus = next;
        else historyStatus = next;
        Instant until = clock.instant().plus(delay);
        if (until.isAfter(cooldown)) cooldown = until;
        LOG.warn("Price prediction {} failure: {}", forecast ? "forecast" : "history", error);
    }

    public synchronized Status status() {
        Instant now = clock.instant();
        var phase = PricePredictionCadence.phase(now, view);
        int usable = view == null ? 0 : (int) view.snapshot().players().keySet().stream()
                .filter(id -> PricePredictionEvidence.assess(view.snapshot(), view.publication().checkedAt(),
                        id, now, phase == PricePredictionCadence.Phase.FREQUENT).forecastUsable()).count();
        return new Status(forecastStatus, historyStatus, phase,
                view == null ? null : view.publication().checkedAt(),
                view == null ? null : view.snapshot().fetchedAt(),
                view == null ? null : view.snapshot().sourceUpdatedAt(), usable, cooldown,
                pending != null, permission.permitted());
    }

    static Duration retryDelay(String header, Instant now) {
        Duration delay = Duration.ofMinutes(10);
        if (header != null) {
            try {
                delay = Duration.ofSeconds(Long.parseLong(header.trim()));
            } catch (RuntimeException exception) {
                try {
                    delay = Duration.between(now, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                } catch (RuntimeException ignored) {
                    delay = Duration.ofMinutes(10);
                }
            }
        }
        if (delay.compareTo(Duration.ofMinutes(10)) < 0) return Duration.ofMinutes(10);
        return delay.compareTo(Duration.ofHours(6)) > 0 ? Duration.ofHours(6) : delay;
    }

    static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void revoke() {
        permission.stop();
        if (!revoked) {
            store.revokeAndDisable();
            revoked = true;
        }
    }

    @Override
    public void close() {
        permission.stop();
        scheduler.shutdown();
        boolean durableRevoked = false;
        try {
            store.revokeAndDisable();
            durableRevoked = true;
        } finally {
            synchronized (this) {
                revoked |= durableRevoked;
            }
        }
    }
}
