package fi.karivanttinen.fplassistant.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.Decision;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Failure;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Response;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.PayloadRetentionLimitException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Run;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StagedPayload;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;

/**
 * Explicit network dispatcher for player-history and fixture-verification runs.
 * It is deliberately inert: construction does not register a bean, job, or schedule.
 */
public final class HistoryFixtureDispatcher {

    private static final Duration NETWORK_RENEW_THRESHOLD = Duration.ofSeconds(45);
    private static final Duration NETWORK_LEASE = Duration.ofMinutes(5);
    private static final Duration RETRY = Duration.ofSeconds(30);
    private static final int PUBLIC_PAIR_LIMIT = 20;
    public static final int MAX_FIXTURE_PLAYERS = 80;

    private final OwnedSyncStore store;
    private final FixtureSyncPublisher publisher;
    private final Fetch fetch;
    private final Clock clock;
    private final boolean publicationEnabled;

    public HistoryFixtureDispatcher(OwnedSyncStore store, FixtureSyncPublisher publisher,
                                    OwnedSyncHttpClient http, Clock clock) {
        this(store, publisher, (endpoint, attempt) -> withNextDue(
                http.get(endpoint, attempt), http.nextAdmissionHint()), clock, true);
    }

    HistoryFixtureDispatcher(OwnedSyncStore store, FixtureSyncPublisher publisher,
                             Fetch fetch, Clock clock) {
        this(store, publisher, fetch, clock, true);
    }

    HistoryFixtureDispatcher(OwnedSyncStore store, FixtureSyncPublisher publisher,
                             Fetch fetch, Clock clock, boolean publicationEnabled) {
        this.store = Objects.requireNonNull(store);
        this.publisher = publisher;
        this.fetch = Objects.requireNonNull(fetch);
        this.clock = Objects.requireNonNull(clock);
        this.publicationEnabled = publicationEnabled;
        if (publicationEnabled) Objects.requireNonNull(publisher);
    }

    static HistoryFixtureDispatcher staging(OwnedSyncStore store, OwnedSyncHttpClient http,
                                             Clock clock) {
        return new HistoryFixtureDispatcher(store, null, (endpoint, attempt) -> withNextDue(
                http.get(endpoint, attempt), http.nextAdmissionHint()), clock, false);
    }

    public Run enqueuePlayerHistory(Season season, int playerId, Instant dueAt) {
        String key = playerHistoryPublicationKey(playerId);
        return store.enqueue(SyncKind.PLAYER_HISTORY, season.code(), key, key, dueAt);
    }

    public Run enqueueFixtureVerification(Season season, int fixtureId, Instant dueAt) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        return store.enqueue(SyncKind.FIXTURE_VERIFICATION, season.code(), key, key, dueAt);
    }

    public Run enqueuePersistedFixtureRetry(Season season, int fixtureId, Instant dueAt) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        return store.enqueuePreservingActiveDemand(
                SyncKind.FIXTURE_VERIFICATION, season.code(), key, key, dueAt);
    }

    public DispatchResult process(Lease initialLease, Season season) {
        Objects.requireNonNull(initialLease);
        Objects.requireNonNull(season);
        try {
            return switch (initialLease.kind()) {
                case PLAYER_HISTORY -> processHistory(initialLease, season);
                case FIXTURE_VERIFICATION -> processFixture(initialLease, season);
                default -> throw new IllegalArgumentException("Lease is not a history/fixture job");
            };
        } catch (Stopped stopped) {
            return stopped.result;
        } catch (OwnedSyncStore.LeaseLostException lost) {
            throw lost;
        } catch (IllegalArgumentException invalid) {
            store.fail(initialLease, bounded(invalid.getMessage()));
            return DispatchResult.failed(FailureType.INVALID_SOURCE);
        } catch (PayloadRetentionLimitException oversized) {
            store.fail(initialLease, bounded(oversized.getMessage()));
            return DispatchResult.failed(FailureType.PAYLOAD_CAP);
        } catch (RuntimeException transientFailure) {
            retry(initialLease, clock.instant().plus(RETRY), bounded(message(transientFailure)));
            return DispatchResult.retry(FailureType.PUBLICATION);
        }
    }

    private DispatchResult processHistory(Lease lease, Season season) {
        int playerId = requireHistoryLease(lease, season);
        CanonicalPair canonical = canonicalSnapshot(season).orElseGet(() -> {
            queueParentRefresh(season);
            retry(lease, clock.instant().plus(RETRY), "Canonical schedule is not available");
            throw new Stopped(DispatchResult.retry(FailureType.MISSING_CANONICAL_SCHEDULE));
        });
        Instant floor = store.requestedSourceAfter(lease);
        Endpoint endpoint = Endpoint.elementSummary(playerId);
        StagedPayload payload = store.latestStagedPayload(lease, endpoint)
                .filter(row -> row.validationState() != ValidationState.INVALID)
                .filter(row -> !row.read().startedAt().isBefore(floor))
                .orElse(null);
        if (payload == null) payload = fetchAndStage(lease, endpoint);
        SourceDocument history = document(payload);
        HistoryParser.HistoryBatch batch;
        try {
            batch = HistoryParser.parse(season, playerId, history, canonical.snapshot.fixtures().stream()
                    .map(HistoryParser::canonicalFixture).toList());
        } catch (HistoryParser.ParentContradictionException contradiction) {
            if (!canonical.currentFor(history.readInterval())) {
                queueParentRefresh(season);
                retry(lease, clock.instant().plus(RETRY),
                        "History contradicts an older catalogue/schedule parent; refreshing parent");
                throw new Stopped(DispatchResult.retry(FailureType.STALE_PARENT));
            }
            String badSource = bounded("Fresh catalogue/schedule parent still contradicts history source: "
                    + contradiction.getMessage());
            store.validateStagedPayloads(lease, List.of(payload.id()), Validation.invalid(badSource));
            throw new IllegalArgumentException(badSource, contradiction);
        } catch (IllegalArgumentException invalid) {
            store.validateStagedPayloads(lease, List.of(payload.id()),
                    Validation.invalid(bounded(invalid.getMessage())));
            throw invalid;
        }
        store.validateStagedPayloads(lease, List.of(payload.id()), Validation.valid());
        SourceRead read = payload.read();
        if (publicationEnabled) {
            store.publish(lease, read, List.of(read), () -> {
                publisher.reconcileHistoryFingerprints(batch).stream().sorted()
                        .forEach(fixture -> enqueueFixtureVerification(season, fixture, read.finishedAt()));
                return null;
            });
        } else store.completeStaged(lease, read, List.of(read));
        return DispatchResult.completed();
    }

    private DispatchResult processFixture(Lease lease, Season season) {
        int fixtureId = requireFixtureLease(lease, season);
        if (publicationEnabled) {
            FixtureSyncRepository.AttemptAdmission admission = store.withinRuntimeFence(
                    () -> publisher.admitAttempt(season, fixtureId));
            if (!admission.admitted()) {
                store.retry(lease, admission.nextDueAt(), "Fixture verification is daily-only after retry cutoff");
                return DispatchResult.retry(FailureType.HTTP_DEFERRED);
            }
        }
        Instant floor = store.requestedSourceAfter(lease);
        PayloadPair pair = ownCanonicalPair(lease, season, floor);
        if (pair.fetched) queueParentRefresh(season);
        Snapshot snapshot = pair.snapshot;
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElse(null);
        if (fixture == null) {
            invalidatePair(lease, pair, "Target fixture is absent from canonical schedule");
            queueParentRefresh(season);
            retry(lease, clock.instant().plus(RETRY), "Target fixture is absent from canonical schedule");
            throw new Stopped(DispatchResult.retry(FailureType.TARGET_CHANGED));
        }
        if (fixture.gameweek() == null) {
            invalidatePair(lease, pair, "Target fixture has no event id");
            queueParentRefresh(season);
            retry(lease, clock.instant().plus(RETRY), "Target fixture has no event id");
            throw new Stopped(DispatchResult.retry(FailureType.INCOMPLETE_FIXTURE));
        }

        Endpoint liveEndpoint = Endpoint.eventLive(fixture.gameweek());
        Instant liveFloor = later(floor, pair.fixtures.read().finishedAt().plusMillis(1));
        StagedPayload livePayload = usableOwn(lease, liveEndpoint, liveFloor).orElse(null);
        if (livePayload == null) livePayload = fetchAndStage(lease, liveEndpoint);
        SourceDocument live = document(livePayload);

        Set<Integer> players = new LinkedHashSet<>();
        snapshot.players().stream()
                .filter(player -> player.teamId() == fixture.homeTeamId()
                        || player.teamId() == fixture.awayTeamId())
                .map(CatalogueScheduleParser.PlayerRow::id).forEach(players::add);
        try {
            players.addAll(FixtureVerification.participantIds(fixture, live));
        } catch (IllegalArgumentException invalid) {
            store.validateStagedPayloads(lease, List.of(livePayload.id()),
                    Validation.invalid(bounded(invalid.getMessage())));
            throw invalid;
        }
        if (players.size() > MAX_FIXTURE_PLAYERS) {
            throw new IllegalArgumentException("Fixture requires " + players.size()
                    + " histories; maximum is " + MAX_FIXTURE_PLAYERS);
        }
        store.validateStagedPayloads(lease, List.of(livePayload.id()), Validation.valid());

        Instant historyFloor = pair.fixtures.read().finishedAt().plusMillis(1);
        Map<Integer, SourceDocument> histories = new LinkedHashMap<>();
        for (int playerId : players) {
            Optional<StagedPayload> evidence = store.latestValidPayload(season.code(),
                    Endpoint.elementSummary(playerId), historyFloor);
            if (evidence.isEmpty()) {
                enqueuePlayerHistory(season, playerId, historyFloor);
            } else {
                histories.put(playerId, document(evidence.get()));
            }
        }
        if (histories.size() != players.size()) {
            retryForDependency(lease, clock.instant().plus(Duration.ofMinutes(2)),
                    "Waiting for fresh player histories");
            throw new Stopped(DispatchResult.waiting());
        }

        // Re-read both public endpoints after the slow fan-out. Successful endpoint reads are
        // checkpointed so a durable admission deferral cannot restart this pair indefinitely.
        Instant historiesFinished = histories.values().stream()
                .map(document -> document.readInterval().completedAt()).max(Instant::compareTo).orElseThrow();
        PayloadPair recheck = recheckPair(lease, season, historiesFinished.plusMillis(1));
        Snapshot rechecked = recheck.snapshot;
        var finalFixture = rechecked.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElse(null);
        if (finalFixture == null
                || !FixtureVerification.verifiedHeader(fixture).equals(
                        FixtureVerification.verifiedHeader(finalFixture))
                || !FixtureStatsFingerprint.of(fixture).equals(FixtureStatsFingerprint.of(finalFixture))) {
            store.validateStagedPayloads(lease, List.of(pair.bootstrap.id(), pair.fixtures.id()),
                    Validation.invalid("Target fixture changed during history fetch"));
            store.validateStagedPayloads(lease, List.of(recheck.bootstrap.id(), recheck.fixtures.id()),
                    Validation.valid());
            store.validateStagedPayloads(lease, List.of(livePayload.id()),
                    Validation.invalid("Live evidence predates corrected fixture"));
            queueParentRefresh(season);
            retry(lease, clock.instant().plus(RETRY), "Target fixture changed during history fetch");
            throw new Stopped(DispatchResult.retry(FailureType.TARGET_CHANGED));
        }

        if (!publicationEnabled) {
            List<SourceRead> reads = new ArrayList<>();
            reads.add(pair.bootstrap.read());
            reads.add(pair.fixtures.read());
            reads.add(livePayload.read());
            histories.values().forEach(document -> reads.add(new SourceRead(
                    document.readInterval().startedAt(), document.readInterval().completedAt())));
            Instant started = reads.stream().map(SourceRead::startedAt).min(Instant::compareTo).orElseThrow();
            Instant finished = reads.stream().map(SourceRead::finishedAt).max(Instant::compareTo).orElseThrow();
            store.completeStaged(lease, new SourceRead(started, finished), reads);
            return DispatchResult.staged();
        }

        Decision decision = publisher.publish(lease, season, fixtureId, pair.bootstrapDocument(),
                pair.fixturesDocument(), live, Map.copyOf(histories));
        if (decision.state() == FixtureVerification.State.PENDING) {
            if (decision.reasons().contains(FixtureVerification.Reason.DATABASE_FIXTURE_MISMATCH)
                    || decision.reasons().contains(FixtureVerification.Reason.MISSING_DATABASE_FIXTURE)) {
                queueScheduleRefresh(season);
            }
            if (decision.reasons().contains(FixtureVerification.Reason.UNKNOWN_DATABASE_PLAYER)) {
                queueCatalogueRefresh(season);
            }
            return DispatchResult.pending(decision);
        }
        return DispatchResult.published(decision);
    }

    private void invalidatePair(Lease lease, PayloadPair pair, String reason) {
        store.validateStagedPayloads(lease, List.of(pair.bootstrap.id(), pair.fixtures.id()),
                Validation.invalid(reason));
    }

    private PayloadPair recheckPair(Lease lease, Season season, Instant floor) {
        return obtainOwnPair(lease, season, floor, false, true);
    }

    private PayloadPair ownCanonicalPair(Lease lease, Season season, Instant floor) {
        return obtainOwnPair(lease, season, floor, true, true);
    }

    private PayloadPair obtainOwnPair(Lease lease, Season season, Instant floor,
                                      boolean allowValid, boolean allowCheckpoint) {
        StagedPayload bootstrap = allowCheckpoint
                ? (allowValid ? usableOwn(lease, Endpoint.bootstrap(), floor)
                        : uncheckedOwn(lease, Endpoint.bootstrap(), floor)).orElse(null) : null;
        StagedPayload fixtures = allowCheckpoint
                ? (allowValid ? usableOwn(lease, Endpoint.fixtures(), floor)
                        : uncheckedOwn(lease, Endpoint.fixtures(), floor)).orElse(null) : null;
        boolean resumed = bootstrap != null || fixtures != null;
        boolean fetched = false;
        if (bootstrap == null) {
            bootstrap = fetchAndStage(lease, Endpoint.bootstrap());
            fetched = true;
        }
        if (fixtures == null) {
            fixtures = fetchAndStage(lease, Endpoint.fixtures());
            fetched = true;
        }
        try {
            Snapshot parsed = CatalogueScheduleParser.parse(season, document(bootstrap), document(fixtures));
            store.validateStagedPayloads(lease, List.of(bootstrap.id(), fixtures.id()), Validation.valid());
            return new PayloadPair(bootstrap, fixtures, parsed, fetched);
        } catch (IllegalArgumentException invalid) {
            store.validateStagedPayloads(lease, List.of(bootstrap.id(), fixtures.id()),
                    Validation.invalid(bounded(invalid.getMessage())));
            if (resumed) return obtainOwnPair(lease, season, floor, allowValid, false);
            throw invalid;
        }
    }

    private Optional<StagedPayload> usableOwn(Lease lease, Endpoint endpoint, Instant floor) {
        Optional<StagedPayload> valid = store.earliestValidStagedPayload(lease, endpoint, floor);
        return valid.isPresent() ? valid : uncheckedOwn(lease, endpoint, floor);
    }

    private Optional<StagedPayload> uncheckedOwn(Lease lease, Endpoint endpoint, Instant floor) {
        return store.latestStagedPayload(lease, endpoint, ValidationState.UNCHECKED)
                .filter(payload -> !payload.read().startedAt().isBefore(floor));
    }

    private Optional<CanonicalPair> canonicalSnapshot(Season season) {
        return store.findInLatestValidPublicPayloadPairs(season.code(), PUBLIC_PAIR_LIMIT, pair -> {
            try {
                return Optional.of(new CanonicalPair(pair.bootstrap(), pair.fixtures(),
                        CatalogueScheduleParser.parse(season,
                                document(pair.bootstrap()), document(pair.fixtures()))));
            } catch (IllegalArgumentException ignored) {
                // VALID staging metadata never bypasses strict parsing in this dispatcher.
                return Optional.empty();
            }
        });
    }

    private StagedPayload fetchAndStage(Lease lease, Endpoint endpoint) {
        Lease renewed = renewBeforeNetwork(lease);
        Response response = requireResponse(renewed, endpoint);
        long id = store.stage(renewed, endpoint, response.read(), Validation.unchecked(), response.body());
        return new StagedPayload(id, renewed.generation(), endpoint.kind(), response.read(),
                ValidationState.UNCHECKED, null, response.body());
    }

    private Response requireResponse(Lease lease, Endpoint endpoint) {
        OwnedSyncHttpClient.Result result = fetch.get(endpoint, Math.max(1, lease.attemptCount()));
        if (result instanceof DeferredResult deferred) {
            defer(lease, future(deferred.nextDue()), "HTTP admission deferred: " + deferred.reason());
            throw new Stopped(DispatchResult.retry(FailureType.HTTP_DEFERRED));
        }
        if (result instanceof Failure failure) {
            FailureType type = failure.kind() == FailureKind.OVERSIZE
                    ? FailureType.PAYLOAD_CAP : FailureType.HTTP_FAILURE;
            if (failure.kind() == FailureKind.OVERSIZE) {
                store.failFetch(lease, bounded(failure.message()));
                throw new Stopped(DispatchResult.failed(type));
            }
            retryFetchFailure(lease, future(failure.nextDue()), bounded(failure.message()));
            throw new Stopped(DispatchResult.retry(type));
        }
        Response response = (Response) result;
        if (response.statusCode() != 200) {
            store.stage(lease, endpoint, response.read(),
                    Validation.invalid("HTTP " + response.statusCode()), response.body());
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                retryFetchFailure(lease, future(response.nextDue()), "Transient HTTP " + response.statusCode());
                throw new Stopped(DispatchResult.retry(FailureType.HTTP_FAILURE));
            }
            store.failFetch(lease, "Terminal HTTP " + response.statusCode());
            throw new Stopped(DispatchResult.failed(FailureType.HTTP_FAILURE));
        }
        store.recordFetchSuccess(lease);
        return response;
    }

    private Lease renewBeforeNetwork(Lease lease) {
        return Duration.between(clock.instant(), lease.expiresAt()).compareTo(NETWORK_RENEW_THRESHOLD) <= 0
                ? store.renew(lease, NETWORK_LEASE) : lease;
    }

    private void queueParentRefresh(Season season) {
        queueCatalogueRefresh(season);
        queueScheduleRefresh(season);
    }

    private void queueCatalogueRefresh(Season season) {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
    }

    private void queueScheduleRefresh(Season season) {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
    }

    private void defer(Lease lease, Instant dueAt, String reason) {
        Instant selected = dueAt.isAfter(clock.instant()) ? dueAt : clock.instant().plusSeconds(1);
        store.retry(lease, selected, bounded(reason));
    }

    private void retry(Lease lease, Instant dueAt, String error) {
        store.retry(lease, recordRetryDue(lease, dueAt, error), bounded(error));
    }

    private void retryForDependency(Lease lease, Instant dueAt, String error) {
        store.retryForDependency(lease, recordRetryDue(lease, dueAt, error), bounded(error));
    }

    private Instant recordRetryDue(Lease lease, Instant dueAt, String error) {
        Instant selected = dueAt.isAfter(clock.instant()) ? dueAt : clock.instant().plusSeconds(1);
        if (publicationEnabled && lease.kind() == SyncKind.FIXTURE_VERIFICATION) {
            int fixtureId = fixtureId(lease.publicationKey());
            Instant minimum = selected;
            Instant adaptive = store.withinRuntimeFence(
                    () -> publisher.recordFailedAttempt(lease.season(), fixtureId, minimum, error));
            if (adaptive.isAfter(selected)) selected = adaptive;
        }
        return selected;
    }

    private void retryFetchFailure(Lease lease, Instant dueAt, String error) {
        Instant selected = dueAt.isAfter(clock.instant()) ? dueAt : clock.instant().plusSeconds(1);
        if (publicationEnabled && lease.kind() == SyncKind.FIXTURE_VERIFICATION) {
            int fixtureId = fixtureId(lease.publicationKey());
            Instant minimum = selected;
            Instant adaptive = store.withinRuntimeFence(
                    () -> publisher.recordFailedAttempt(lease.season(), fixtureId, minimum, error));
            if (adaptive.isAfter(selected)) selected = adaptive;
        }
        store.retryFetchFailure(lease, selected, bounded(error));
    }

    private static int fixtureId(String publicationKey) {
        return Integer.parseInt(publicationKey.substring("fixture:".length()));
    }

    private Instant future(Instant hint) {
        return hint == null ? clock.instant().plus(RETRY)
                : hint.isAfter(clock.instant()) ? hint : clock.instant().plusSeconds(1);
    }

    private static int requireHistoryLease(Lease lease, Season season) {
        if (!lease.season().equals(season.code())) throw new IllegalArgumentException("Lease season mismatch");
        String prefix = "player-history:";
        if (!lease.publicationKey().startsWith(prefix)
                || !lease.publicationKey().equals(lease.coalescingKey())) {
            throw new IllegalArgumentException("Non-canonical player-history key");
        }
        try {
            int id = Integer.parseInt(lease.publicationKey().substring(prefix.length()));
            if (id <= 0 || !lease.publicationKey().equals(playerHistoryPublicationKey(id))) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Non-canonical player-history key");
        }
    }

    private static int requireFixtureLease(Lease lease, Season season) {
        if (!lease.season().equals(season.code())
                || !lease.publicationKey().equals(lease.coalescingKey())) {
            throw new IllegalArgumentException("Non-canonical fixture key");
        }
        String prefix = "fixture:";
        try {
            if (!lease.publicationKey().startsWith(prefix)) throw new NumberFormatException();
            int id = Integer.parseInt(lease.publicationKey().substring(prefix.length()));
            if (id <= 0 || !lease.publicationKey().equals(FixtureSyncPublisher.publicationKey(id))) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Non-canonical fixture key");
        }
    }

    public static String playerHistoryPublicationKey(int playerId) {
        if (playerId <= 0) throw new IllegalArgumentException("player id must be positive");
        return "player-history:" + playerId;
    }

    private static SourceDocument document(StagedPayload payload) {
        return new SourceDocument(payload.body(), new CatalogueScheduleParser.ReadInterval(
                payload.read().startedAt(), payload.read().finishedAt()));
    }

    private static OwnedSyncHttpClient.Result withNextDue(OwnedSyncHttpClient.Result result,
                                                           Optional<Instant> hint) {
        Instant due = hint.orElse(null);
        if (result instanceof Response response
                && (response.statusCode() == 429 || response.statusCode() >= 500)) {
            return new Response(response.statusCode(), response.body(), response.retryAfter(),
                    response.read(), due);
        }
        if (result instanceof Failure failure && (failure.kind() == FailureKind.TIMEOUT
                || failure.statusCode() != null
                && (failure.statusCode() == 429 || failure.statusCode() >= 500))) {
            return new Failure(failure.kind(), failure.message(), failure.statusCode(),
                    failure.retryAfter(), failure.read(), due);
        }
        return result;
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= OwnedSyncStore.MAX_ERROR_CHARS
                ? value : value.substring(0, OwnedSyncStore.MAX_ERROR_CHARS);
    }

    @FunctionalInterface
    interface Fetch {
        OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt);
    }

    public enum Outcome { COMPLETED, STAGED, WAITING_FOR_HISTORIES, PENDING, PUBLISHED, RETRY_SCHEDULED, FAILED }
    public enum FailureType {
        NONE, MISSING_CANONICAL_SCHEDULE, INCOMPLETE_FIXTURE, TARGET_CHANGED, STALE_PARENT,
        HTTP_DEFERRED, HTTP_FAILURE, PAYLOAD_CAP, INVALID_SOURCE, PUBLICATION
    }

    public record DispatchResult(Outcome outcome, FailureType failure, Decision decision) {
        public DispatchResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(failure);
        }
        static DispatchResult completed() { return new DispatchResult(Outcome.COMPLETED, FailureType.NONE, null); }
        static DispatchResult staged() { return new DispatchResult(Outcome.STAGED, FailureType.NONE, null); }
        static DispatchResult waiting() { return new DispatchResult(Outcome.WAITING_FOR_HISTORIES, FailureType.NONE, null); }
        static DispatchResult pending(Decision decision) { return new DispatchResult(Outcome.PENDING, FailureType.NONE, decision); }
        static DispatchResult published(Decision decision) { return new DispatchResult(Outcome.PUBLISHED, FailureType.NONE, decision); }
        static DispatchResult retry(FailureType failure) { return new DispatchResult(Outcome.RETRY_SCHEDULED, failure, null); }
        static DispatchResult failed(FailureType failure) { return new DispatchResult(Outcome.FAILED, failure, null); }
    }

    private record PayloadPair(StagedPayload bootstrap, StagedPayload fixtures, Snapshot snapshot,
                               boolean fetched) {
        SourceDocument bootstrapDocument() { return document(bootstrap); }
        SourceDocument fixturesDocument() { return document(fixtures); }
    }

    private record CanonicalPair(StagedPayload bootstrap, StagedPayload fixtures, Snapshot snapshot) {
        boolean currentFor(CatalogueScheduleParser.ReadInterval child) {
            return !bootstrap.read().startedAt().isBefore(child.completedAt())
                    && !fixtures.read().startedAt().isBefore(child.completedAt());
        }
    }

    private static final class Stopped extends RuntimeException {
        private final DispatchResult result;
        private Stopped(DispatchResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }
}
