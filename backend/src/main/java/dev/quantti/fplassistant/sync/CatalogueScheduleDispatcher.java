package dev.quantti.fplassistant.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.CatalogueScheduleWriter.PublicationDeferredException;
import dev.quantti.fplassistant.sync.CatalogueScheduleWriter.SourceRegressionException;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Failure;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PayloadRetentionLimitException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Run;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceDemandAdvancedException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.StagedPayload;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.sync.OwnedSyncStore.ValidationState;

/**
 * Explicit, inert catalogue/schedule dispatch over the durable owned-sync queue.
 *
 * <p>Nothing in this class is a bean, scheduler or CLI entry point. Existing legacy jobs are not
 * disabled here; that is reserved for the reviewed Task 9 single-writer handover. Network calls
 * occur only through {@link #process(Lease, Season)} and never inside a database transaction.
 */
public final class CatalogueScheduleDispatcher {

    public static final String CATALOGUE_PRICE_COALESCING_KEY = "catalogue-price";
    public static final String SCHEDULE_COALESCING_KEY = "schedule";

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final LocalTime PRICE_CUTOVER = LocalTime.of(2, 0);
    private static final LocalTime SCHEDULE_CUTOVER = LocalTime.of(3, 0);
    private static final Duration NETWORK_RENEW_THRESHOLD = Duration.ofMinutes(4);
    private static final Duration NETWORK_LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final int REUSABLE_PAIR_LIMIT = 20;

    private final OwnedSyncStore store;
    private final CatalogueScheduleWriter writer;
    private final Fetch fetch;
    private final Clock clock;
    private final boolean publicationEnabled;

    /** Production construction uses exactly the bounded Task 2 HTTP GET. */
    public CatalogueScheduleDispatcher(OwnedSyncStore store, CatalogueScheduleWriter writer,
                                       OwnedSyncHttpClient http, Clock clock) {
        this(store, writer, fencedFetch(store, http), clock, true);
    }

    /** Minimal test seam; callers supply raw transport results, not a second queue or transport. */
    CatalogueScheduleDispatcher(OwnedSyncStore store, CatalogueScheduleWriter writer,
                                Fetch fetch, Clock clock) {
        this(store, writer, fetch, clock, true);
    }

    CatalogueScheduleDispatcher(OwnedSyncStore store, CatalogueScheduleWriter writer,
                                Fetch fetch, Clock clock, boolean publicationEnabled) {
        this.store = Objects.requireNonNull(store);
        this.writer = writer;
        this.fetch = Objects.requireNonNull(fetch);
        this.clock = Objects.requireNonNull(clock);
        this.publicationEnabled = publicationEnabled;
        if (publicationEnabled) Objects.requireNonNull(writer);
    }

    static CatalogueScheduleDispatcher staging(OwnedSyncStore store, OwnedSyncHttpClient http,
                                                Clock clock) {
        return new CatalogueScheduleDispatcher(store, null, fencedFetch(store, http), clock, false);
    }

    public Run enqueueCatalogue(Season season, Instant dueAt) {
        return store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season.code(),
                CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, dueAt);
    }

    public Run enqueueSchedule(Season season, Instant dueAt) {
        return store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season.code(),
                SCHEDULE_COALESCING_KEY, CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, dueAt);
    }

    /** Enqueues the two independently fenced publication groups for one explicit cycle. */
    public Cycle enqueueCycle(Season season, Instant dueAt) {
        Objects.requireNonNull(season);
        Objects.requireNonNull(dueAt);
        Run catalogue = enqueueCatalogue(season, dueAt);
        Run schedule = enqueueSchedule(season, dueAt);
        return new Cycle(catalogue, schedule);
    }

    /** Processes one already-claimed canonical job and performs no acquisition or activation. */
    public Outcome process(Lease initialLease, Season configuredSeason) {
        requireCanonical(initialLease, configuredSeason);
        Lease lease = initialLease;
        for (int sourceAttempt = 0; sourceAttempt < 2; sourceAttempt++) {
            PairLease pair;
            try {
                pair = obtainPair(lease, configuredSeason, sourceAttempt == 0);
                lease = pair.lease;
                return publish(lease, configuredSeason, pair.documents);
            } catch (Stopped stopped) {
                return stopped.outcome;
            } catch (OwnedSyncStore.LeaseLostException lost) {
                throw lost;
            } catch (SourceRegressionException | SourceDemandAdvancedException advanced) {
                if (sourceAttempt == 0) continue;
                retry(lease, backoff(lease.attemptCount()),
                        bounded("Source chronology advanced: " + advanced.getMessage()));
                return Outcome.RETRY_SCHEDULED;
            } catch (PublicationDeferredException deferred) {
                store.retry(lease, cutoff(lease.publicationKey()), bounded(deferred.getMessage()));
                return Outcome.RETRY_SCHEDULED;
            } catch (IllegalArgumentException terminal) {
                store.fail(lease, bounded(terminal.getMessage()));
                return Outcome.FAILED;
            } catch (IllegalStateException terminal) {
                store.fail(lease, bounded(terminal.getMessage()));
                return Outcome.FAILED;
            } catch (RuntimeException transientFailure) {
                retry(lease, backoff(lease.attemptCount()),
                        bounded("Publication failed: " + message(transientFailure)));
                return Outcome.RETRY_SCHEDULED;
            }
        }
        throw new IllegalStateException("unreachable source-attempt loop");
    }

    private Outcome publish(Lease lease, Season season, Documents documents) {
        if (!publicationEnabled) {
            ReadInterval bootstrap = documents.bootstrap.readInterval();
            ReadInterval fixtures = documents.fixtures.readInterval();
            SourceRead combined = new SourceRead(
                    bootstrap.startedAt().isBefore(fixtures.startedAt())
                            ? bootstrap.startedAt() : fixtures.startedAt(),
                    bootstrap.completedAt().isAfter(fixtures.completedAt())
                            ? bootstrap.completedAt() : fixtures.completedAt());
            store.completeStaged(lease, combined, List.of(
                    new SourceRead(bootstrap.startedAt(), bootstrap.completedAt()),
                    new SourceRead(fixtures.startedAt(), fixtures.completedAt())));
            return Outcome.STAGED;
        }
        if (CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(lease.publicationKey())) {
            writer.publishCataloguePriceForDemand(lease, season, documents.bootstrap, documents.fixtures);
        } else {
            writer.publishScheduleForDemand(lease, season, documents.bootstrap, documents.fixtures);
        }
        return Outcome.PUBLISHED;
    }

    private PairLease obtainPair(Lease lease, Season season, boolean allowReusable) {
        Instant requestedAfter = store.requestedSourceAfter(lease);
        if (requestedAfter.isAfter(clock.instant())) {
            store.retry(lease, requestedAfter, "Source demand is not due yet");
            throw new Stopped(Outcome.RETRY_SCHEDULED);
        }
        if (allowReusable) {
            Optional<Documents> reusable = reusablePair(season, requestedAfter);
            if (reusable.isPresent()) return new PairLease(lease, reusable.get());
        }

        StagedPayload bootstrap = allowReusable
                ? usableOwn(lease, Endpoint.bootstrap(), requestedAfter).orElse(null) : null;
        StagedPayload fixtures = allowReusable
                ? usableOwn(lease, Endpoint.fixtures(), requestedAfter).orElse(null) : null;
        boolean resumedPayloads = bootstrap != null || fixtures != null;
        List<Long> ownedIds = new ArrayList<>(2);
        if (bootstrap != null) ownedIds.add(bootstrap.id());
        if (fixtures != null) ownedIds.add(fixtures.id());

        try {
            if (bootstrap == null) {
                FetchResult result = fetchAndStage(lease, Endpoint.bootstrap(), requestedAfter);
                lease = result.lease;
                bootstrap = result.payload;
                ownedIds.add(bootstrap.id());
            }
            if (fixtures == null) {
                FetchResult result = fetchAndStage(lease, Endpoint.fixtures(), requestedAfter);
                lease = result.lease;
                fixtures = result.payload;
                ownedIds.add(fixtures.id());
            }
        } catch (PayloadRetentionLimitException cap) {
            store.fail(lease, bounded(cap.getMessage()));
            throw new Stopped(Outcome.FAILED);
        }

        Documents documents = documents(bootstrap, fixtures);
        if (resumedPayloads && !withinSourceWindow(documents)) {
            store.validateStagedPayloads(lease, ownedIds,
                    Validation.invalid("Staged public pair exceeded the bounded source window"));
            return obtainPair(lease, season, false);
        }
        if (!isToday(documents) || !startsAtOrAfter(documents, requestedAfter)
                || regressesReceipts(season.code(), documents)) {
            store.validateStagedPayloads(lease, ownedIds,
                    Validation.invalid("Staged public pair is obsolete for today's source streams"));
            retry(lease, Duration.ofSeconds(1), "Staged public pair became obsolete; refreshing");
            throw new Stopped(Outcome.RETRY_SCHEDULED);
        }
        try {
            CatalogueScheduleParser.parse(season, documents.bootstrap, documents.fixtures);
        } catch (IllegalArgumentException invalid) {
            store.validateStagedPayloads(lease, ownedIds, Validation.invalid(bounded(invalid.getMessage())));
            store.fail(lease, bounded(invalid.getMessage()));
            throw new Stopped(Outcome.FAILED);
        }
        store.validateStagedPayloads(lease, ownedIds, Validation.valid());
        return new PairLease(lease, documents);
    }

    private Optional<Documents> reusablePair(Season season, Instant requestedAfter) {
        Instant dayStart = date(clock.instant()).atStartOfDay(UK).toInstant();
        Instant minimumReadStart = requestedAfter.isAfter(dayStart) ? requestedAfter : dayStart;
        return store.findInLatestValidPublicPayloadPairs(season.code(), REUSABLE_PAIR_LIMIT, minimumReadStart, pair -> {
            Documents documents = documents(pair.bootstrap(), pair.fixtures());
            if (!isToday(documents) || !startsAtOrAfter(documents, requestedAfter)
                    || regressesReceipts(season.code(), documents)) return Optional.empty();
            try {
                CatalogueScheduleParser.parse(season, documents.bootstrap, documents.fixtures);
                return Optional.of(documents);
            } catch (IllegalArgumentException ignored) {
                // VALID is staging metadata, not permission to bypass the strict pair parser.
                return Optional.empty();
            }
        });
    }

    private Optional<StagedPayload> usableOwn(Lease lease, Endpoint endpoint, Instant requestedAfter) {
        return store.latestStagedPublicPayload(lease, endpoint)
                .filter(payload -> payload.validationState() != ValidationState.INVALID)
                .filter(payload -> isToday(payload.read()))
                .filter(payload -> !payload.read().startedAt().isBefore(requestedAfter));
    }

    private FetchResult fetchAndStage(Lease lease, Endpoint endpoint, Instant sourceFloor) {
        lease = renewBeforeNetwork(lease);
        OwnedSyncHttpClient.Result result = fetch.get(lease, endpoint,
                Math.max(1, lease.attemptCount()), sourceFloor);
        if (result instanceof DeferredResult deferred) {
            Instant due = strictlyFuture(deferred.nextDue());
            store.retry(lease, due, "HTTP budget deferred: " + deferred.reason());
            throw new Stopped(Outcome.RETRY_SCHEDULED);
        }
        if (result instanceof Failure failure) {
            if (failure.kind() == FailureKind.OVERSIZE) {
                store.failFetch(lease, bounded(failure.message()));
                throw new Stopped(Outcome.FAILED);
            }
            Instant due = failure.nextDue() == null
                    ? clock.instant().plus(backoff(lease.attemptCount())) : failure.nextDue();
            store.retryFetchFailure(lease, strictlyFuture(due), bounded(failure.message()));
            throw new Stopped(Outcome.RETRY_SCHEDULED);
        }

        Response response = (Response) result;
        if (response.statusCode() != 200) {
            Validation invalid = Validation.invalid("HTTP " + response.statusCode());
            store.stage(lease, endpoint, response.read(), invalid, response.body());
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                Instant due = response.nextDue() == null
                        ? clock.instant().plus(backoff(lease.attemptCount())) : response.nextDue();
                store.retryFetchFailure(lease, strictlyFuture(due), "Transient HTTP " + response.statusCode());
                throw new Stopped(Outcome.RETRY_SCHEDULED);
            }
            store.failFetch(lease, "Terminal HTTP " + response.statusCode());
            throw new Stopped(Outcome.FAILED);
        }
        store.recordFetchSuccess(lease);
        long id = store.stage(lease, endpoint, response.read(), Validation.unchecked(), response.body());
        return new FetchResult(lease, new StagedPayload(id, lease.generation(), endpoint.kind(),
                response.read(), ValidationState.UNCHECKED, null, response.body()));
    }

    private Lease renewBeforeNetwork(Lease lease) {
        Instant now = clock.instant();
        if (!lease.expiresAt().isAfter(now)) return lease;
        Duration remaining = Duration.between(now, lease.expiresAt());
        return remaining.compareTo(NETWORK_RENEW_THRESHOLD) <= 0
                ? store.renew(lease, NETWORK_LEASE) : lease;
    }

    private boolean regressesReceipts(String season, Documents documents) {
        Optional<SourceRead> bootstrapReceipt = store.sourceReceipt(
                season, CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY);
        Optional<SourceRead> fixtureReceipt = store.sourceReceipt(
                season, CatalogueScheduleWriter.FIXTURE_SOURCE_RECEIPT_KEY);
        return bootstrapReceipt.filter(receipt -> receipt.finishedAt().isAfter(
                        documents.bootstrap.readInterval().completedAt())).isPresent()
                || fixtureReceipt.filter(receipt -> receipt.finishedAt().isAfter(
                        documents.fixtures.readInterval().completedAt())).isPresent();
    }

    private boolean isToday(Documents documents) {
        return isToday(documents.bootstrap.readInterval()) && isToday(documents.fixtures.readInterval());
    }

    private static boolean startsAtOrAfter(Documents documents, Instant floor) {
        return !documents.bootstrap.readInterval().startedAt().isBefore(floor)
                && !documents.fixtures.readInterval().startedAt().isBefore(floor);
    }

    private static boolean withinSourceWindow(Documents documents) {
        Instant start = documents.bootstrap.readInterval().startedAt().isBefore(
                documents.fixtures.readInterval().startedAt())
                ? documents.bootstrap.readInterval().startedAt()
                : documents.fixtures.readInterval().startedAt();
        Instant end = documents.bootstrap.readInterval().completedAt().isAfter(
                documents.fixtures.readInterval().completedAt())
                ? documents.bootstrap.readInterval().completedAt()
                : documents.fixtures.readInterval().completedAt();
        return ChronoUnit.MINUTES.between(start, end) <= 10;
    }

    private boolean isToday(SourceRead read) {
        return date(read.startedAt()).equals(date(clock.instant()))
                && date(read.finishedAt()).equals(date(clock.instant()));
    }

    private boolean isToday(ReadInterval read) {
        return date(read.startedAt()).equals(date(clock.instant()))
                && date(read.completedAt()).equals(date(clock.instant()));
    }

    private Instant cutoff(String publicationKey) {
        LocalTime time = CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(publicationKey)
                ? PRICE_CUTOVER : SCHEDULE_CUTOVER;
        Instant value = date(clock.instant()).atTime(time).atZone(UK).toInstant();
        return strictlyFuture(value);
    }

    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt, 1) - 1, 20);
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 2L << exponent);
        return Duration.ofSeconds(seconds);
    }

    private void retry(Lease lease, Duration delay, String error) {
        store.retry(lease, clock.instant().plus(delay), bounded(error));
    }

    private Instant strictlyFuture(Instant candidate) {
        Instant now = clock.instant();
        return candidate.isAfter(now) ? candidate : now.plusSeconds(1);
    }

    private static Fetch fencedFetch(OwnedSyncStore store, OwnedSyncHttpClient http) {
        return new Fetch() {
            @Override
            public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
                throw new IllegalStateException("Owned-sync HTTP fetch requires its caller lease");
            }

            @Override
            public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt,
                                                  Instant sourceFloor) {
                return http.get(endpoint, failureAttempt,
                        () -> store.httpFence(lease, endpoint, sourceFloor));
            }
        };
    }

    private static Documents documents(StagedPayload bootstrap, StagedPayload fixtures) {
        return new Documents(document(bootstrap), document(fixtures));
    }

    private static SourceDocument document(StagedPayload payload) {
        return new SourceDocument(payload.body(),
                new ReadInterval(payload.read().startedAt(), payload.read().finishedAt()));
    }

    private static void requireCanonical(Lease lease, Season season) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(season);
        boolean price = CATALOGUE_PRICE_COALESCING_KEY.equals(lease.coalescingKey())
                && CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY.equals(lease.publicationKey());
        boolean schedule = SCHEDULE_COALESCING_KEY.equals(lease.coalescingKey())
                && CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY.equals(lease.publicationKey());
        if (lease.kind() != SyncKind.CATALOGUE_SCHEDULE || !lease.season().equals(season.code())
                || !(price || schedule)) {
            throw new IllegalArgumentException("Lease is not a canonical catalogue/schedule job");
        }
    }

    private LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, UK);
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
    interface Fetch extends OwnedSyncHttpClient.DispatcherFetch { }

    public enum Outcome { STAGED, PUBLISHED, RETRY_SCHEDULED, FAILED }

    public record Cycle(Run cataloguePrice, Run schedule) {
        public Cycle {
            Objects.requireNonNull(cataloguePrice);
            Objects.requireNonNull(schedule);
        }
    }

    private record Documents(SourceDocument bootstrap, SourceDocument fixtures) { }
    private record PairLease(Lease lease, Documents documents) { }
    private record FetchResult(Lease lease, StagedPayload payload) { }

    private static final class Stopped extends RuntimeException {
        private final Outcome outcome;
        private Stopped(Outcome outcome) {
            super(null, null, false, false);
            this.outcome = outcome;
        }
    }
}
