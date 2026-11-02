package dev.quantti.fplassistant.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Failure;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Demand;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Purpose;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PageReceipt;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Run;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.sync.GameweekFinalityRepository.Progress;

/** Incremental collector; Spring wiring is opt-in and the default constructor remains disabled. */
public final class CoalescedPlayerHistoryCollector implements CoalescedPlayerHistoryImportEntrypoint {

    public static final Duration CYCLE_INTERVAL = Duration.ofHours(1);
    static final Duration PAYLOAD_RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration RENEW_THRESHOLD = Duration.ofMinutes(4);
    private static final int MAX_PLAYER_PAGES = GameweekFinality.MAX_CHECKPOINT_PLAYER_IDS;

    private final Season season;
    private final boolean enabled;
    private final OwnedSyncStore store;
    private final IncrementalPlayerFixturePublisher publisher;
    private final CompatibilityCatalogueWriter compatibilityCatalogue;
    private final GameweekFinalityRepository finality;
    private final Fetch fetch;
    private final Clock clock;
    private final String owner;

    /** The no-flag constructor is deliberately disabled; nothing in Spring constructs this class. */
    public CoalescedPlayerHistoryCollector(Season season, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, OwnedSyncHttpClient http, Clock clock, String owner) {
        this(season, false, store, publisher, null, fencedFetch(store, http), clock, owner);
    }

    CoalescedPlayerHistoryCollector(Season season, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, Fetch fetch, Clock clock, String owner) {
        this(season, false, store, publisher, null, fetch, clock, owner);
    }

    public CoalescedPlayerHistoryCollector(Season season, boolean enabled, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, OwnedSyncHttpClient http, Clock clock, String owner) {
        this(season, enabled, store, publisher, null, fencedFetch(store, http), clock, owner);
    }

    public CoalescedPlayerHistoryCollector(Season season, boolean enabled, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, CompatibilityCatalogueWriter compatibilityCatalogue,
            OwnedSyncHttpClient http, Clock clock, String owner) {
        this(season, enabled, store, publisher, Objects.requireNonNull(compatibilityCatalogue),
                fencedFetch(store, http), clock, owner);
    }

    CoalescedPlayerHistoryCollector(Season season, boolean enabled, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, Fetch fetch, Clock clock, String owner) {
        this(season, enabled, store, publisher, null, fetch, clock, owner);
    }

    CoalescedPlayerHistoryCollector(Season season, boolean enabled, OwnedSyncStore store,
            IncrementalPlayerFixturePublisher publisher, CompatibilityCatalogueWriter compatibilityCatalogue,
            Fetch fetch, Clock clock, String owner) {
        this.season = Objects.requireNonNull(season);
        this.enabled = enabled;
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        this.compatibilityCatalogue = compatibilityCatalogue;
        this.finality = store.gameweekFinalityRepository();
        this.fetch = Objects.requireNonNull(fetch);
        this.clock = Objects.requireNonNull(clock);
        this.owner = Objects.requireNonNull(owner);
    }

    int pruneTerminalPayloads() {
        return store.deleteIncrementalCoreTerminalPayloadsOlderThan(PAYLOAD_RETENTION);
    }

    /** Runs at most one durable season cycle. Callers decide when to invoke this API. */
    @Override
    public CycleResult runCycle() {
        if (!enabled) return CycleResult.disabled();
        Instant now = clock.instant();
        Run run = store.enqueueIncrementalCoreImport(season, now);
        Optional<Lease> acquired = store.acquireIncrementalCoreImport(season.code(), owner, LEASE);
        if (acquired.isEmpty()) {
            return new CycleResult(run.state() == OwnedSyncStore.RunState.LEASED
                    ? State.BUSY : State.DEFERRED, List.of(), List.of(), List.of(), List.of(), run.dueAt());
        }
        return collect(acquired.get());
    }

    private CycleResult collect(Lease initialLease) {
        Lease lease = initialLease;
        List<SourceRead> reads = new ArrayList<>();
        List<Integer> requestedPlayers = new ArrayList<>();
        List<Integer> committedPlayers = new ArrayList<>();
        List<Integer> failedPlayers = new ArrayList<>();
        List<Integer> finalPendingEvents = new ArrayList<>();
        Set<Integer> reopenedFinalizedEvents = new HashSet<>();
        List<Integer> missingLiveEvents = new ArrayList<>();
        List<Integer> failedLiveEvents = new ArrayList<>();
        Instant deferredUntil = null;
        DeferredReason deferredReason = null;
        String deferredError = null;
        int deferredPriority = Integer.MAX_VALUE;

        Instant cycleFloor;
        try {
            cycleFloor = store.requestedSourceAfter(lease);
        } catch (RuntimeException failure) {
            return fail(lease, failure.getMessage(), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        OwnedSyncStore.IncrementalCompatibilitySnapshot persistedSnapshot = null;
        SourceDocument bootstrap;
        SourceDocument fixturesDocument;
        if (compatibilityCatalogue != null) {
            try {
                persistedSnapshot = store.incrementalCompatibilitySnapshot(lease).orElse(null);
            } catch (RuntimeException failure) {
                return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                        finalPendingEvents, null);
            }
        }
        boolean validationPairUsed = false;
        if (persistedSnapshot != null) {
            CanonicalPairRequest freshPair = requestCanonicalPair(lease, cycleFloor);
            lease = freshPair.lease();
            if (freshPair.failure() != null) return stop(freshPair.failure(), requestedPlayers, committedPlayers,
                    failedPlayers, finalPendingEvents);
            reads.add(read(freshPair.bootstrap()));
            reads.add(read(freshPair.fixtures()));
            boolean freshPairValid = true;
            try {
                CatalogueScheduleParser.parse(season, freshPair.bootstrap(), freshPair.fixtures());
            } catch (RuntimeException invalidPair) {
                freshPairValid = false;
                validationPairUsed = true;
                Instant validationFloor = max(freshPair.bootstrap().readInterval().completedAt(),
                        freshPair.fixtures().readInterval().completedAt()).plusMillis(1);
                CanonicalPairRequest validationPair = requestCanonicalPair(lease, later(cycleFloor, validationFloor));
                lease = validationPair.lease();
                if (validationPair.failure() != null) return stop(validationPair.failure(), requestedPlayers,
                        committedPlayers, failedPlayers, finalPendingEvents);
                reads.add(read(validationPair.bootstrap()));
                reads.add(read(validationPair.fixtures()));
                try {
                    CatalogueScheduleParser.parse(season, validationPair.bootstrap(), validationPair.fixtures());
                } catch (RuntimeException stillInvalid) {
                    return retryInvalidSourcePair(lease, requestedPlayers, committedPlayers, failedPlayers,
                            finalPendingEvents);
                }
                if (!sameSourceBodies(persistedSnapshot, validationPair.bootstrap(), validationPair.fixtures())) {
                    return supersedeForSourceChange(lease, requestedPlayers, committedPlayers, failedPlayers,
                            finalPendingEvents);
                }
            }
            if (freshPairValid && !sameSourceBodies(persistedSnapshot,
                    freshPair.bootstrap(), freshPair.fixtures())) {
                return supersedeForSourceChange(lease, requestedPlayers, committedPlayers, failedPlayers,
                        finalPendingEvents);
            }
            bootstrap = new SourceDocument(persistedSnapshot.bootstrap().body(),
                    new ReadInterval(persistedSnapshot.bootstrap().read().startedAt(),
                            persistedSnapshot.bootstrap().read().finishedAt()));
            fixturesDocument = new SourceDocument(persistedSnapshot.fixtures().body(),
                    new ReadInterval(persistedSnapshot.fixtures().read().startedAt(),
                            persistedSnapshot.fixtures().read().finishedAt()));
        } else {
            RequestResult bootstrapResult = request(lease, Endpoint.bootstrap(), cycleFloor);
            lease = bootstrapResult.lease();
            if (!bootstrapResult.succeeded()) return stop(bootstrapResult, requestedPlayers, committedPlayers,
                    failedPlayers, finalPendingEvents);
            bootstrap = bootstrapResult.document();

            RequestResult fixturesResult = request(lease, Endpoint.fixtures(), cycleFloor);
            lease = fixturesResult.lease();
            if (!fixturesResult.succeeded()) return stop(fixturesResult, requestedPlayers, committedPlayers,
                    failedPlayers, finalPendingEvents);
            fixturesDocument = fixturesResult.document();
        }
        reads.add(read(bootstrap));
        reads.add(read(fixturesDocument));

        Snapshot snapshot;
        try {
            snapshot = CatalogueScheduleParser.parse(season, bootstrap, fixturesDocument);
        } catch (RuntimeException invalid) {
            return fail(lease, bounded(invalid.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        String selectedSourceFingerprint = sourceBodyFingerprint(bootstrap, fixturesDocument);
        Instant snapshotStart = min(bootstrap.readInterval().startedAt(), fixturesDocument.readInterval().startedAt());
        Instant snapshotEnd = max(bootstrap.readInterval().completedAt(), fixturesDocument.readInterval().completedAt());
        SourceRead snapshotRead = new SourceRead(snapshotStart, snapshotEnd);
        OwnedSyncStore.IncrementalDiscovery discovery;
        Set<Integer> changedCompatibilityFixtures = Set.of();
        try {
            String fingerprint = selectedSourceFingerprint;
            if (compatibilityCatalogue == null) {
                discovery = store.observeIncrementalSnapshot(lease, snapshot);
                discovery.events().values().stream().filter(OwnedSyncStore.IncrementalEvent::finalPending)
                        .map(OwnedSyncStore.IncrementalEvent::gameweek).forEach(finalPendingEvents::add);
                if (!store.recordIncrementalSourceSnapshot(lease, fingerprint, snapshotRead)) {
                    return fail(lease, "Source snapshot changed while resuming this import; start a fresh cycle",
                            requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
                }
                store.recordIncrementalFixtureSourceRevisions(lease, snapshot.fixtures(),
                        read(snapshot.fixturesRead()));
            } else {
                if (!store.recordIncrementalSourceSnapshot(lease, fingerprint, snapshotRead)) {
                    return fail(lease, "Source snapshot changed while resuming this import; start a fresh cycle",
                            requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
                }
                store.recordIncrementalFixtureSourceRevisions(lease, snapshot.fixtures(),
                        read(snapshot.fixturesRead()));
                if (persistedSnapshot != null) {
                    discovery = store.observeIncrementalSnapshot(lease, snapshot);
                } else {
                    long bootstrapPayload = store.stage(lease, Endpoint.bootstrap(), read(bootstrap),
                            Validation.unchecked(), bootstrap.bytes());
                    long fixturePayload = store.stage(lease, Endpoint.fixtures(), read(fixturesDocument),
                            Validation.unchecked(), fixturesDocument.bytes());
                    store.validateStagedPayloads(lease, List.of(bootstrapPayload, fixturePayload),
                            Validation.valid());
                    CompatibilityCatalogueWriter.Publication publication = compatibilityCatalogue.publish(
                            lease, season, bootstrap, fixturesDocument);
                    discovery = publication.discovery();
                    changedCompatibilityFixtures = Set.copyOf(publication.changedFixtureIds());
                }
                discovery.events().values().stream().filter(OwnedSyncStore.IncrementalEvent::finalPending)
                        .map(OwnedSyncStore.IncrementalEvent::gameweek).forEach(finalPendingEvents::add);
            }
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        Set<Integer> changedFixtureIds = changedCompatibilityFixtures;
        Set<Integer> finalizedCandidates = discovery.events().values().stream()
                .filter(OwnedSyncStore.IncrementalEvent::finalized)
                .map(OwnedSyncStore.IncrementalEvent::gameweek)
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> currentlyFinalized;
        try {
            currentlyFinalized = finality.currentFinalizedGameweeks(lease, season, snapshot, finalizedCandidates);
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }
        reopenedFinalizedEvents.addAll(finalizedCandidates);
        reopenedFinalizedEvents.removeAll(currentlyFinalized);
        finalPendingEvents.addAll(reopenedFinalizedEvents);

        List<FixtureRow> active = snapshot.fixtures().stream()
                .filter(fixture -> GameweekFinality.fixtureProgress(fixture) == GameweekFinality.FixtureProgress.OPEN
                        && (fixture.started()
                        || fixture.kickoff() != null && !fixture.kickoff().isAfter(clock.instant())))
                .toList();
        List<FixtureRow> finished = snapshot.fixtures().stream()
                .filter(fixture -> GameweekFinality.fixtureProgress(fixture) != GameweekFinality.FixtureProgress.OPEN)
                .filter(fixture -> {
                    OwnedSyncStore.IncrementalEvent event = fixture.gameweek() == null ? null
                            : discovery.events().get(fixture.gameweek());
                    return event == null || !currentlyFinalized.contains(event.gameweek());
                }).toList();
        List<FixtureRow> targets = new ArrayList<>(active.size() + finished.size());
        targets.addAll(active);
        targets.addAll(finished);
        Set<Integer> targetIds = targets.stream().map(FixtureRow::id)
                .collect(java.util.stream.Collectors.toSet());
        snapshot.fixtures().stream().filter(fixture -> changedFixtureIds.contains(fixture.id()))
                .filter(FixtureRow::started).filter(fixture -> targetIds.add(fixture.id())).forEach(targets::add);
        if (targets.isEmpty() && finalPendingEvents.isEmpty()) {
            return complete(lease, reads, requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents);
        }

        Map<Integer, Set<Integer>> candidates = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> cataloguePlayers = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> sourceParticipants = new LinkedHashMap<>();
        Map<Integer, FixtureVerification.FixtureEvidence> participantEvidence = new LinkedHashMap<>();
        Map<Integer, String> discoveryFingerprints = discoveryFingerprints(snapshot, targets);
        Map<Integer, Set<Integer>> discovered;
        Map<Integer, Set<Integer>> known;
        Map<Integer, Set<Integer>> knownLedger;
        Map<Integer, Set<Integer>> durableParticipants;
        Set<Integer> needsDiscovery;
        try {
            needsDiscovery = store.incrementalFixturesNeedingDiscovery(lease, discoveryFingerprints);
            List<Integer> targetFixtureIds = targets.stream().map(FixtureRow::id).toList();
            discovered = store.incrementalDiscoveredPlayerIds(lease, targetFixtureIds);
            durableParticipants = store.incrementalParticipantPlayerIds(lease, targetFixtureIds);
            knownLedger = store.incrementalKnownPlayerIds(lease, targetFixtureIds);
            List<Integer> hydrationFixtures = targets.stream().map(FixtureRow::id)
                    .filter(needsDiscovery::contains).toList();
            Map<Integer, Set<Integer>> hydratedKnown = store.knownPlayerIds(lease, season.id(), hydrationFixtures);
            known = new LinkedHashMap<>();
            for (FixtureRow fixture : targets) {
                Set<Integer> catalogue = new LinkedHashSet<>();
                snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                        || player.teamId() == fixture.awayTeamId()).map(PlayerRow::id).forEach(catalogue::add);
                Set<Integer> participants = new LinkedHashSet<>(
                        FixtureVerification.fixtureStatParticipantIds(fixture));
                Set<Integer> knownIds = new LinkedHashSet<>(knownLedger.getOrDefault(fixture.id(), Set.of()));
                if (needsDiscovery.contains(fixture.id())) {
                    knownIds.addAll(hydratedKnown.getOrDefault(fixture.id(), Set.of()));
                }
                knownIds.removeAll(catalogue);
                knownIds.removeAll(participants);
                knownIds.removeAll(durableParticipants.getOrDefault(fixture.id(), Set.of()));
                known.put(fixture.id(), Set.copyOf(knownIds));
                Set<Integer> ids = new LinkedHashSet<>(catalogue);
                ids.addAll(participants);
                ids.addAll(durableParticipants.getOrDefault(fixture.id(), Set.of()));
                ids.addAll(knownIds);
                cataloguePlayers.put(fixture.id(), catalogue);
                sourceParticipants.put(fixture.id(), participants);
                candidates.put(fixture.id(), ids);
            }
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }
        Set<Integer> basePlayerIds = allPlayers(candidates);
        if (basePlayerIds.size() > MAX_PLAYER_PAGES) {
            return fail(lease, "Core-import cycle exceeds the bounded player-page limit",
                    requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
        }

        Map<Integer, PageReceipt> pageReceipts = store.incrementalPageReceipts(lease, basePlayerIds);
        Map<Integer, Instant> fixtureFloors = new HashMap<>();
        Map<Integer, Integer> priorities = new HashMap<>();
        for (FixtureRow fixture : active) {
            fixtureFloors.put(fixture.id(), cycleFloor);
            priorities.put(fixture.id(), 2);
        }
        for (FixtureRow fixture : targets) {
            if (changedFixtureIds.contains(fixture.id())
                    && !fixtureFloors.containsKey(fixture.id())) {
                fixtureFloors.put(fixture.id(), snapshotRead.finishedAt().plusMillis(1));
                priorities.put(fixture.id(), 1);
            }
        }
        try {
            store.incrementalAppearanceStates(lease, targetIds).forEach((fixtureId, appearanceState) ->
                    fixtureFloors.merge(fixtureId, appearanceState.invalidationFloor(),
                            CoalescedPlayerHistoryCollector::later));
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        for (FixtureRow fixture : finished) {
            Instant observed = discovery.postMatchObservationTimes().get(fixture.id());
            if (observed == null) {
                return fail(lease, "Post-match fixture has no durable first-observed source marker",
                        requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
            }
            Instant floor = later(fixtureFloors.get(fixture.id()), observed.plusMillis(1));
            int priority = priorities.getOrDefault(fixture.id(), 1);
            OwnedSyncStore.IncrementalEvent event = fixture.gameweek() == null ? null
                    : discovery.events().get(fixture.gameweek());
            if (event != null && (event.finalPending() || reopenedFinalizedEvents.contains(event.gameweek()))) {
                if (event.firstCheckedAt() == null) {
                    return fail(lease, "FINAL_PENDING event has no durable checked-source observation",
                            requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
                }
                floor = later(floor, event.firstCheckedAt().plusMillis(1));
                priority = 0;
                finalPendingEvents.add(event.gameweek());
            }
            fixtureFloors.put(fixture.id(), floor);
            priorities.put(fixture.id(), priority);
        }

        Map<Integer, PageReceipt> initialPageReceipts = pageReceipts;
        Set<Integer> targetEvents = targets.stream().map(FixtureRow::gameweek).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, PageReceipt> liveReceipts = store.incrementalLiveEventReceipts(lease, targetEvents);
        Set<Integer> refreshedLiveEvents = new HashSet<>();
        Map<Integer, List<FixtureRow>> liveByEvent = new LinkedHashMap<>();
        for (FixtureRow fixture : targets) {
            if (fixture.gameweek() == null) continue;
            Instant floor = fixtureFloors.get(fixture.id());
            boolean always = GameweekFinality.fixtureProgress(fixture) == GameweekFinality.FixtureProgress.OPEN
                    || finalPendingEvents.contains(fixture.gameweek());
            Set<Integer> participantIds = new LinkedHashSet<>(sourceParticipants.getOrDefault(fixture.id(), Set.of()));
            participantIds.addAll(durableParticipants.getOrDefault(fixture.id(), Set.of()));
            boolean missingParticipantPages = participantIds.stream().anyMatch(player ->
                    !pageFresh(initialPageReceipts.get(player), floor));
            boolean missingLive = !sourceFresh(liveReceipts.get(fixture.gameweek()), floor);
            if (always || needsDiscovery.contains(fixture.id()) || missingParticipantPages || missingLive) {
                liveByEvent.computeIfAbsent(fixture.gameweek(), ignored -> new ArrayList<>()).add(fixture);
            }
        }

        // shortcut: a first omitted-player page proves its side next cycle; revisit for immediate cold finality.
        Map<Integer, Map<Integer, FixtureVerification.Side>> historySides;
        try {
            historySides = store.incrementalHistorySides(lease, season.id(),
                    liveByEvent.values().stream().flatMap(List::stream).toList());
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers,
                    failedPlayers, finalPendingEvents, null);
        }
        var orderedEvents = liveByEvent.entrySet().stream().sorted(Comparator.comparingInt(entry ->
                entry.getValue().stream().mapToInt(fixture -> priorities.get(fixture.id())).min().orElseThrow())).toList();
        for (Map.Entry<Integer, List<FixtureRow>> entry : orderedEvents) {
            int eventId = entry.getKey();
            Instant eventFloor = entry.getValue().stream().map(fixture -> fixtureFloors.get(fixture.id()))
                    .reduce(CoalescedPlayerHistoryCollector::later).map(floor -> later(cycleFloor, floor))
                    .orElse(cycleFloor);
            int priority = entry.getValue().stream().mapToInt(fixture -> priorities.get(fixture.id()))
                    .min().orElseThrow();
            RequestResult liveResult = request(lease, Endpoint.eventLive(eventId), eventFloor,
                    demand(priority, Math.max(snapshot.players().size(), basePlayerIds.size())));
            lease = liveResult.lease();
            if (liveResult.deferred()) {
                if (!liveResult.deferral().expectedWait()) {
                    return stop(liveResult, requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents);
                }
                missingLiveEvents.add(eventId);
                deferredUntil = liveResult.nextDue();
                if (priority < deferredPriority) {
                    deferredPriority = priority;
                    deferredReason = liveResult.deferral();
                    deferredError = liveResult.error();
                }
                continue;
            }
            if (!liveResult.succeeded()) {
                missingLiveEvents.add(eventId);
                failedLiveEvents.add(eventId);
                continue;
            }
            SourceDocument live = liveResult.document();
            reads.add(read(live));
            try {
                Map<Integer, FixtureVerification.FixtureEvidence> evidence =
                        FixtureVerification.fixtureEvidenceByFixture(entry.getValue(), live, historySides);
                Map<Integer, Set<Integer>> participants = new LinkedHashMap<>();
                evidence.forEach((fixtureId, fixtureEvidence) -> {
                    participantEvidence.put(fixtureId, fixtureEvidence);
                    Set<Integer> ids = new LinkedHashSet<>(FixtureVerification.fixtureStatParticipantIds(
                            entry.getValue().stream().filter(fixture -> fixture.id() == fixtureId)
                                    .findFirst().orElseThrow()));
                    ids.addAll(fixtureEvidence.positivePlayerIds());
                    participants.put(fixtureId, Set.copyOf(ids));
                    candidates.get(fixtureId).addAll(ids);
                    sourceParticipants.computeIfAbsent(fixtureId, ignored -> new LinkedHashSet<>()).addAll(ids);
                    Set<Integer> knownIds = new LinkedHashSet<>(known.getOrDefault(fixtureId, Set.of()));
                    knownIds.removeAll(ids);
                    known.put(fixtureId, Set.copyOf(knownIds));
                });
                SourceRead liveRead = read(live);
                OwnedSyncStore.IncrementalEvent sourceEvent = discovery.events().get(eventId);
                if (sourceEvent == null) {
                    throw new IllegalStateException("Live event has no observed gameweek source revision");
                }
                Set<Integer> revalidationFixtures = store.recordIncrementalLiveEvent(
                        lease, eventId, sourceEvent.sourceSignature(), participants, entry.getValue(), evidence, liveRead);
                for (int fixtureId : revalidationFixtures) {
                    fixtureFloors.merge(fixtureId, liveRead.finishedAt(),
                            CoalescedPlayerHistoryCollector::later);
                }
                refreshedLiveEvents.add(eventId);
            } catch (RuntimeException invalidLive) {
                missingLiveEvents.add(eventId);
                failedLiveEvents.add(eventId);
            }
        }

        try {
            store.incrementalAppearanceStates(lease, targetIds).forEach((fixtureId, appearanceState) ->
                    fixtureFloors.merge(fixtureId, appearanceState.invalidationFloor(),
                            CoalescedPlayerHistoryCollector::later));
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        Map<Integer, Set<Integer>> checkpointPlayers = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> checkpointCandidates = new LinkedHashMap<>();
        Map<Integer, String> checkpointFingerprints = new LinkedHashMap<>(discoveryFingerprints);
        for (FixtureRow fixture : targets) {
            boolean liveFresh = fixture.gameweek() == null || refreshedLiveEvents.contains(fixture.gameweek())
                    || sourceFresh(liveReceipts.get(fixture.gameweek()), fixtureFloors.get(fixture.id()));
            if (liveFresh) {
                checkpointPlayers.put(fixture.id(),
                        new LinkedHashSet<>(sourceParticipants.getOrDefault(fixture.id(), Set.of())));
            }
            checkpointCandidates.put(fixture.id(), Set.copyOf(candidates.get(fixture.id())));
        }
        if (!checkpointCandidates.isEmpty()) {
            try {
                store.recordIncrementalPopulation(lease, checkpointPlayers, checkpointCandidates, discovered,
                        known, knownLedger, checkpointFingerprints, snapshotRead);
            } catch (RuntimeException failure) {
                return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                        finalPendingEvents, null);
            }
        }

        Map<Integer, Progress> finalCheckpoints =
                new LinkedHashMap<>();
        try {
            for (int gameweek : finalPendingEvents.stream().distinct().sorted().toList()) {
                OwnedSyncStore.IncrementalEvent event = discovery.events().get(gameweek);
                if (event == null || missingLiveEvents.contains(gameweek)
                        || !event.finalPending() && !reopenedFinalizedEvents.contains(gameweek)) continue;
                dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot finalSource =
                        dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(snapshot, gameweek);
                Set<Integer> fixtureIds = finalSource.assignedFixtures().stream().map(FixtureRow::id)
                        .collect(java.util.stream.Collectors.toSet());
                var population = new dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation(
                        catalogueFingerprint(snapshot, finalSource.assignedFixtures()),
                        subset(cataloguePlayers, fixtureIds),
                        subset(sourceParticipants, fixtureIds), subset(known, fixtureIds),
                        subsetEvidence(participantEvidence, fixtureIds), compatibilityCatalogue != null);
                var progress = finality.beginOrResume(lease, season, finalSource, population, snapshotRead);
                if (progress.state() == dev.quantti.fplassistant.sync.GameweekFinality.State.FINAL_PENDING) {
                    finalCheckpoints.put(gameweek, progress);
                    for (FixtureRow fixture : finalSource.assignedFixtures()) {
                        fixtureFloors.merge(fixture.id(), progress.checkpoint().sourceFloor(),
                                CoalescedPlayerHistoryCollector::later);
                        priorities.put(fixture.id(), 0);
                    }
                }
            }
        } catch (RuntimeException failure) {
            return fail(lease, bounded(failure.getMessage()), requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents, null);
        }

        Set<Integer> allCandidateIds = allPlayers(candidates);
        finalCheckpoints.values().stream().map(progress -> progress.checkpoint().requiredPlayerIds())
                .forEach(allCandidateIds::addAll);
        if (allCandidateIds.size() > MAX_PLAYER_PAGES) {
            return fail(lease, "Core-import cycle exceeds the bounded player-page limit",
                    requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
        }
        pageReceipts = store.incrementalPageReceipts(lease, allCandidateIds);
        for (Progress progress : finalCheckpoints.values()) {
            for (int playerId : progress.checkpoint().outstandingPlayerIds()) {
                PageReceipt receipt = pageReceipts.get(playerId);
                if (receipt != null && receipt.generation() == lease.generation()) {
                    return restartAppearanceGeneration(lease, requestedPlayers, committedPlayers, failedPlayers,
                            finalPendingEvents);
                }
            }
        }
        Map<Integer, PageNeed> needed = new HashMap<>();
        for (FixtureRow fixture : targets) {
            Instant floor = fixtureFloors.get(fixture.id());
            int priority = priorities.get(fixture.id());
            boolean activeFixture = GameweekFinality.fixtureProgress(fixture) == GameweekFinality.FixtureProgress.OPEN;
            for (int playerId : candidates.get(fixture.id())) {
                PageReceipt receipt = pageReceipts.get(playerId);
                boolean required = activeFixture
                        ? receipt == null || receipt.generation() != lease.generation() || !pageFresh(receipt, floor)
                        : !pageFresh(receipt, floor);
                if (!required) continue;
                PageNeed prior = needed.get(playerId);
                needed.put(playerId, prior == null ? new PageNeed(floor, priority)
                        : new PageNeed(later(prior.floor(), floor), Math.min(prior.priority(), priority)));
            }
        }
        finalCheckpoints.forEach((gameweek, progress) -> progress.checkpoint().outstandingPlayerIds()
                .forEach(playerId -> {
                    Instant floor = progress.checkpoint().sourceFloor();
                    PageNeed prior = needed.get(playerId);
                    needed.put(playerId, prior == null ? new PageNeed(floor, 0)
                            : new PageNeed(later(prior.floor(), floor), Math.min(prior.priority(), 0)));
                }));
        if (needed.size() > MAX_PLAYER_PAGES) {
            return fail(lease, "Core-import cycle exceeds the bounded player-page limit",
                    requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents, null);
        }
        long currentGeneration = lease.generation();
        boolean sameGenerationPageInvalidated = false;
        for (Map.Entry<Integer, PageNeed> entry : needed.entrySet()) {
            PageReceipt receipt = pageReceipts.get(entry.getKey());
            if (receipt != null && receipt.generation() == currentGeneration
                    && !pageFresh(receipt, entry.getValue().floor())) {
                sameGenerationPageInvalidated = true;
                break;
            }
        }
        if (sameGenerationPageInvalidated) {
            return restartAppearanceGeneration(lease, requestedPlayers, committedPlayers, failedPlayers,
                    finalPendingEvents);
        }

        List<Integer> orderedPlayers = needed.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, PageNeed>>comparingInt(entry -> entry.getValue().priority())
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey).toList();
        requestedPlayers.addAll(orderedPlayers);
        for (int playerId : orderedPlayers) {
            PageNeed need = needed.get(playerId);
            RequestResult pageResult = request(lease, Endpoint.elementSummary(playerId),
                    later(cycleFloor, need.floor()),
                    demand(need.priority(), Math.max(snapshot.players().size(), allCandidateIds.size())));
            lease = pageResult.lease();
            if (pageResult.deferred()) {
                deferredUntil = pageResult.nextDue();
                if (need.priority() <= deferredPriority || !pageResult.deferral().expectedWait()) {
                    deferredPriority = need.priority();
                    deferredReason = pageResult.deferral();
                    deferredError = pageResult.error();
                }
                break;
            }
            if (!pageResult.succeeded()) {
                failedPlayers.add(playerId);
                if (pageResult.nextDue() != null && pageResult.nextDue().isAfter(clock.instant())) {
                    deferredUntil = pageResult.nextDue();
                    break;
                }
                continue;
            }
            SourceDocument page = pageResult.document();
            reads.add(read(page));
            try {
                publisher.publish(lease, season, playerId, page,
                        snapshot.fixtures().stream().map(HistoryParser::canonicalFixture).toList());
                for (Map.Entry<Integer, Progress> checkpoint
                        : finalCheckpoints.entrySet()) {
                    if (checkpoint.getValue().checkpoint().requiredPlayerIds().contains(playerId)) {
                        finalCheckpoints.put(checkpoint.getKey(), finality.recordCompletedPage(
                                lease, season, checkpoint.getKey(), playerId));
                    }
                }
                committedPlayers.add(playerId);
            } catch (RuntimeException invalidPage) {
                failedPlayers.add(playerId);
                if (compatibilityCatalogue != null && !validationPairUsed
                        && invalidPage instanceof HistoryParser.ParentContradictionException) {
                    validationPairUsed = true;
                    Instant validationFloor = later(cycleFloor, page.readInterval().completedAt().plusMillis(1));
                    CanonicalPairRequest validationPair = requestCanonicalPair(lease, validationFloor);
                    lease = validationPair.lease();
                    if (validationPair.failure() != null) {
                        return stop(validationPair.failure(), requestedPlayers, committedPlayers, failedPlayers,
                                finalPendingEvents);
                    }
                    reads.add(read(validationPair.bootstrap()));
                    reads.add(read(validationPair.fixtures()));
                    try {
                        CatalogueScheduleParser.parse(season, validationPair.bootstrap(), validationPair.fixtures());
                        String validatedFingerprint = sourceBodyFingerprint(
                                validationPair.bootstrap(), validationPair.fixtures());
                        if (!selectedSourceFingerprint.equals(validatedFingerprint)) {
                            return supersedeForSourceChange(lease, requestedPlayers, committedPlayers, failedPlayers,
                                    finalPendingEvents);
                        }
                    } catch (RuntimeException invalidPair) {
                        // An invalid or incoherent pair is not evidence of a source revision.
                    }
                }
            }
        }

        boolean sourceFailure = !failedPlayers.isEmpty() || !failedLiveEvents.isEmpty();
        String retryError = sourceFailure ? failureSummary(failedPlayers, failedLiveEvents) : deferredError;
        Instant retryDue = deferredUntil != null || !failedPlayers.isEmpty() || !missingLiveEvents.isEmpty()
                ? deferredReason != null ? clock.instant().plus(CYCLE_INTERVAL)
                        : later(clock.instant().plus(CYCLE_INTERVAL), deferredUntil) : null;
        if (retryDue != null) {
            Set<Integer> unfinishedPages = new HashSet<>(needed.keySet());
            unfinishedPages.removeAll(committedPlayers);
            finalCheckpoints.entrySet().removeIf(entry ->
                    missingLiveEvents.contains(entry.getKey())
                    || entry.getValue().checkpoint().requiredPlayerIds().isEmpty()
                    || !entry.getValue().checkpoint().outstandingPlayerIds().isEmpty()
                    || entry.getValue().checkpoint().requiredPlayerIds().stream().anyMatch(unfinishedPages::contains));
            if (finalCheckpoints.isEmpty()) {
                if (sourceFailure) {
                    store.retryFetchFailure(lease, retryDue, retryError);
                } else {
                    store.retry(lease, retryDue, deferredReason == null
                            ? failureSummary(failedPlayers, missingLiveEvents) : deferredError);
                }
                return result(State.RETRY_SCHEDULED, requestedPlayers, committedPlayers, failedPlayers,
                        finalPendingEvents, retryDue);
            }
        }
        if (!finalCheckpoints.isEmpty()) {
            return finishFinalSweeps(lease, snapshot, reads, finalCheckpoints,
                    sourceParticipants, participantEvidence, known, requestedPlayers, committedPlayers,
                    failedPlayers, finalPendingEvents, retryDue, retryError, sourceFailure);
        }
        return complete(lease, reads, requestedPlayers, committedPlayers, failedPlayers, finalPendingEvents);
    }

    private CycleResult finishFinalSweeps(Lease lease, Snapshot initialSnapshot, List<SourceRead> reads,
                                          Map<Integer, Progress> checkpoints,
                                          Map<Integer, Set<Integer>> sourceParticipants,
                                          Map<Integer, FixtureVerification.FixtureEvidence> participantEvidence,
                                          Map<Integer, Set<Integer>> known,
                                          List<Integer> requested, List<Integer> committed,
                                          List<Integer> failed, List<Integer> finalPending,
                                          Instant retryDue, String retryError, boolean sourceFailure) {
        Instant priorFinish = max(initialSnapshot.bootstrapRead().completedAt(),
                initialSnapshot.fixturesRead().completedAt());
        for (var progress : checkpoints.values()) {
            if (progress.sourceReadInterval() != null) {
                priorFinish = max(priorFinish, progress.sourceReadInterval().finishedAt());
            }
            if (progress.pageReadInterval() != null) {
                priorFinish = max(priorFinish, progress.pageReadInterval().finishedAt());
            }
        }
        Instant endFloor = priorFinish.plusMillis(1);
        RequestResult endBootstrapResult = request(lease, Endpoint.bootstrap(), endFloor);
        lease = endBootstrapResult.lease();
        if (!endBootstrapResult.succeeded()) {
            return stop(endBootstrapResult, requested, committed, failed, finalPending,
                    sourceFailure ? retryError : null);
        }
        SourceDocument endBootstrap = endBootstrapResult.document();
        reads.add(read(endBootstrap));
        Instant fixturesFloor = later(endFloor, endBootstrap.readInterval().completedAt().plusMillis(1));
        RequestResult endFixturesResult = request(lease, Endpoint.fixtures(), fixturesFloor);
        lease = endFixturesResult.lease();
        if (!endFixturesResult.succeeded()) {
            return stop(endFixturesResult, requested, committed, failed, finalPending,
                    sourceFailure ? retryError : null);
        }
        SourceDocument endFixtures = endFixturesResult.document();
        reads.add(read(endFixtures));
        SourceRead endRead = new SourceRead(
                min(endBootstrap.readInterval().startedAt(), endFixtures.readInterval().startedAt()),
                max(endBootstrap.readInterval().completedAt(), endFixtures.readInterval().completedAt()));
        store.includeIncrementalSourceRead(lease, endRead);

        Snapshot endSnapshot;
        try {
            endSnapshot = CatalogueScheduleParser.parse(season, endBootstrap, endFixtures);
        } catch (RuntimeException invalid) {
            Instant due = clock.instant().plus(CYCLE_INTERVAL);
            store.retry(lease, due, bounded("Final source re-read was invalid: " + invalid.getMessage()));
            return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
        }
        try {
            store.observeIncrementalSnapshot(lease, endSnapshot);
        } catch (RuntimeException invalid) {
            return fail(lease, bounded(invalid.getMessage()), requested, committed, failed, finalPending, null);
        }

        boolean sourceChanged = false;
        boolean catalogueChanged = false;
        for (int gameweek : checkpoints.keySet()) {
            var initialSource = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot
                    .from(initialSnapshot, gameweek);
            var terminalSource = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot
                    .from(endSnapshot, gameweek);
            if (!terminalSource.finalSweepEligible() || !initialSource.signature().equals(terminalSource.signature())) {
                sourceChanged = true;
                continue;
            }
            if (!catalogueFingerprint(initialSnapshot, initialSource.assignedFixtures())
                    .equals(catalogueFingerprint(endSnapshot, terminalSource.assignedFixtures()))) {
                catalogueChanged = true;
            }
        }
        if (sourceChanged || catalogueChanged) {
            String error = sourceChanged ? "Checked-gameweek source changed during final sweep"
                    : "Current catalogue population changed during final sweep";
            return restartFinalSweepGeneration(lease, requested, committed, failed, finalPending, error);
        }

        Map<Integer, Set<Integer>> endCatalogue = cataloguePlayers(endSnapshot, endSnapshot.fixtures());
        boolean blocked = false;
        boolean finalizedAny = false;
        String blockedReason = "Checked-gameweek final sweep remains blocked";
        for (Map.Entry<Integer, Progress> entry
                : checkpoints.entrySet()) {
            int gameweek = entry.getKey();
            var source = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot
                    .from(endSnapshot, gameweek);
            Set<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id)
                    .collect(java.util.stream.Collectors.toSet());
            var population = new dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation(
                    catalogueFingerprint(endSnapshot, source.assignedFixtures()), subset(endCatalogue, fixtureIds),
                    subset(sourceParticipants, fixtureIds), subset(known, fixtureIds),
                    subsetEvidence(participantEvidence, fixtureIds), compatibilityCatalogue != null);
            try {
                finality.finalizeSweep(lease, season, source, population, endRead);
                finalizedAny = true;
            } catch (RuntimeException finalizationFailure) {
                try {
                    var refreshed = finality.beginOrResume(lease, season, source, population, endRead);
                    boolean advanced = refreshed.checkpoint() != null
                            && (refreshed.checkpoint().invalidationRevision()
                                    != entry.getValue().checkpoint().invalidationRevision()
                                || !refreshed.checkpoint().sourceFloor()
                                        .equals(entry.getValue().checkpoint().sourceFloor())
                                || !refreshed.checkpoint().populationFingerprint()
                                        .equals(entry.getValue().checkpoint().populationFingerprint())
                            || !refreshed.checkpoint().completedPlayerIds()
                                        .equals(entry.getValue().checkpoint().completedPlayerIds())
                            || !refreshed.checkpoint().outstandingPlayerIds()
                                        .equals(entry.getValue().checkpoint().outstandingPlayerIds()));
                    if (advanced) {
                        return restartFinalSweepGeneration(lease, requested, committed, failed, finalPending,
                                "Final-sweep evidence changed; retry pages in a fresh producer generation");
                    } else {
                        blocked = true;
                        blockedReason = bounded(finalizationFailure.getMessage());
                    }
                } catch (RuntimeException reconciliationFailure) {
                    blocked = true;
                    blockedReason = bounded(reconciliationFailure.getMessage());
                }
            }
        }
        if (blocked) {
            Instant due = later(clock.instant().plus(CYCLE_INTERVAL), retryDue);
            if (finalizedAny) {
                return completeAndRequeue(lease, reads, requested, committed, failed, finalPending, due,
                        sourceFailure ? retryError + "; " + blockedReason : blockedReason, sourceFailure);
            }
            if (sourceFailure) store.retryFetchFailure(lease, due, bounded(retryError + "; " + blockedReason));
            else store.retry(lease, due, blockedReason);
            return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
        }
        if (retryDue != null) {
            return completeAndRequeue(lease, reads, requested, committed, failed, finalPending, retryDue,
                    retryError, sourceFailure);
        }
        return complete(lease, reads, requested, committed, failed, finalPending);
    }

    private CycleResult completeAndRequeue(Lease lease, List<SourceRead> reads, List<Integer> requested,
                                           List<Integer> committed, List<Integer> failed,
                                           List<Integer> finalPending, Instant due, String error, boolean sourceFailure) {
        store.completeIncrementalPassAndRetry(lease, season, store.incrementalSourceInterval(lease), reads,
                due, error, sourceFailure);
        return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
    }

    private CycleResult restartFinalSweepGeneration(Lease lease, List<Integer> requested,
                                                    List<Integer> committed, List<Integer> failed,
                                                    List<Integer> finalPending, String error) {
        Instant due = clock.instant().plus(CYCLE_INTERVAL);
        store.fail(lease, bounded(error));
        store.enqueueIncrementalCoreImport(season, due);
        return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
    }

    private CycleResult restartAppearanceGeneration(Lease lease, List<Integer> requested,
                                                     List<Integer> committed, List<Integer> failed,
                                                     List<Integer> finalPending) {
        Instant due = clock.instant().plus(CYCLE_INTERVAL);
        store.supersedeIncrementalCoreImport(lease,
                OwnedSyncStore.IncrementalSupersedeReason.APPEARANCE_EVIDENCE_ADVANCED);
        store.enqueueIncrementalCoreImport(season, due);
        return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
    }

    private CanonicalPairRequest requestCanonicalPair(Lease lease, Instant floor) {
        RequestResult bootstrapResult = request(lease, Endpoint.bootstrap(), floor);
        lease = bootstrapResult.lease();
        if (!bootstrapResult.succeeded()) {
            return new CanonicalPairRequest(lease, null, null, bootstrapResult);
        }
        Instant fixturesFloor = later(floor, bootstrapResult.document().readInterval().completedAt().plusMillis(1));
        RequestResult fixturesResult = request(lease, Endpoint.fixtures(), fixturesFloor);
        lease = fixturesResult.lease();
        if (!fixturesResult.succeeded()) {
            return new CanonicalPairRequest(lease, bootstrapResult.document(), null, fixturesResult);
        }
        return new CanonicalPairRequest(lease, bootstrapResult.document(), fixturesResult.document(), null);
    }

    private CycleResult retryInvalidSourcePair(Lease lease, List<Integer> requested, List<Integer> committed,
                                               List<Integer> failed, List<Integer> finalPending) {
        Instant due = clock.instant().plus(CYCLE_INTERVAL);
        store.retry(lease, due, "Canonical source pair is invalid or incoherent; no revision was accepted");
        return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
    }

    private CycleResult supersedeForSourceChange(Lease lease, List<Integer> requested, List<Integer> committed,
                                                 List<Integer> failed, List<Integer> finalPending) {
        store.supersedeIncrementalCoreImport(lease);
        Instant due = clock.instant().plus(CYCLE_INTERVAL);
        return result(State.SUPERSEDED, requested, committed, failed, finalPending, due);
    }

    private static boolean sameSourceBodies(OwnedSyncStore.IncrementalCompatibilitySnapshot prior,
                                            SourceDocument bootstrap, SourceDocument fixtures) {
        return prior.pair().bootstrap().sha256().equals(OwnedSyncStore.sha256(bootstrap.bytes()))
                && prior.pair().fixtures().sha256().equals(OwnedSyncStore.sha256(fixtures.bytes()));
    }

    private static String sourceBodyFingerprint(SourceDocument bootstrap, SourceDocument fixtures) {
        return OwnedSyncStore.sha256(OwnedSyncStore.sha256(bootstrap.bytes()) + "|"
                + OwnedSyncStore.sha256(fixtures.bytes()));
    }

    private static Demand demand(int priority, int players) {
        return new Demand(switch (priority) {
            case 0 -> Purpose.FINAL;
            case 1 -> Purpose.POST_MATCH;
            case 2 -> Purpose.LIVE;
            default -> throw new IllegalArgumentException("Unknown collection priority");
        }, players);
    }

    private RequestResult request(Lease lease, Endpoint endpoint, Instant floor) {
        return request(lease, endpoint, floor, Demand.DISCOVERY);
    }

    private RequestResult request(Lease lease, Endpoint endpoint, Instant floor, Demand demand) {
        if (lease.expiresAt().isBefore(clock.instant().plus(RENEW_THRESHOLD))) {
            lease = store.renew(lease, LEASE);
        }
        OwnedSyncHttpClient.Result result = fetch.get(lease, endpoint, Math.max(1, lease.attemptCount()), floor, demand);
        if (result instanceof DeferredResult deferred) {
            return new RequestResult(lease, null, deferred.nextDue(), deferred.reason().durableMessage(demand.purpose()), deferred.reason());
        }
        if (result instanceof Failure failure) {
            return new RequestResult(lease, null, failure.nextDue(), failure.message(), null);
        }
        Response response = (Response) result;
        if (response.statusCode() != 200) {
            return new RequestResult(lease, null, response.nextDue(), "HTTP " + response.statusCode(), null);
        }
        SourceRead sourceRead = response.read();
        if (sourceRead.startedAt().isBefore(floor) || sourceRead.finishedAt().isAfter(clock.instant())) {
            return new RequestResult(lease, null, null, "Source read violates its required floor or clock", null);
        }
        SourceDocument document = new SourceDocument(response.body(),
                new ReadInterval(sourceRead.startedAt(), sourceRead.finishedAt()));
        return new RequestResult(lease, document, null, null, null);
    }

    private CycleResult stop(RequestResult result, List<Integer> requested, List<Integer> committed,
                             List<Integer> failed, List<Integer> finalPending) {
        return stop(result, requested, committed, failed, finalPending,
                failed.isEmpty() ? null : failureSummary(failed, List.of()));
    }

    private CycleResult stop(RequestResult result, List<Integer> requested, List<Integer> committed,
                             List<Integer> failed, List<Integer> finalPending, String sourceFailure) {
        Instant due = result.deferred() ? clock.instant().plus(CYCLE_INTERVAL)
                : later(clock.instant().plus(CYCLE_INTERVAL), result.nextDue());
        if (sourceFailure != null) {
            store.retryFetchFailure(result.lease(), due, bounded(sourceFailure + "; " + result.error()));
        } else if (result.deferred()) {
            store.retry(result.lease(), due, bounded(result.error()));
        } else {
            store.retryFetchFailure(result.lease(), due, bounded(result.error()));
        }
        return result(State.RETRY_SCHEDULED, requested, committed, failed, finalPending, due);
    }

    private CycleResult complete(Lease lease, List<SourceRead> reads, List<Integer> requested,
                                 List<Integer> committed, List<Integer> failed, List<Integer> finalPending) {
        SourceRead interval = store.incrementalSourceInterval(lease);
        store.completeStaged(lease, interval, reads);
        return result(State.COMPLETED, requested, committed, failed, finalPending, null);
    }

    private CycleResult fail(Lease lease, String error, List<Integer> requested, List<Integer> committed,
                             List<Integer> failed, List<Integer> finalPending, Instant due) {
        store.fail(lease, bounded(error));
        return result(State.FAILED, requested, committed, failed, finalPending, due);
    }

    private static CycleResult result(State state, List<Integer> requested, List<Integer> committed,
                                      List<Integer> failed, List<Integer> finalPending, Instant due) {
        return new CycleResult(state, requested, committed, failed,
                finalPending.stream().distinct().sorted().toList(), due);
    }

    private static Map<Integer, Set<Integer>> cataloguePlayers(Snapshot snapshot, List<FixtureRow> fixtures) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        for (FixtureRow fixture : fixtures) {
            Set<Integer> ids = new LinkedHashSet<>();
            snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                    || player.teamId() == fixture.awayTeamId()).map(PlayerRow::id).forEach(ids::add);
            result.put(fixture.id(), ids);
        }
        return result;
    }

    private static String catalogueFingerprint(Snapshot snapshot, List<FixtureRow> fixtures) {
        return GameweekFinality.catalogueFingerprint(snapshot, fixtures);
    }

    private static Map<Integer, Set<Integer>> subset(Map<Integer, Set<Integer>> source, Set<Integer> fixtures) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        source.forEach((fixture, players) -> {
            if (fixtures.contains(fixture)) result.put(fixture, Set.copyOf(players));
        });
        return Map.copyOf(result);
    }

    private static Map<Integer, String> discoveryFingerprints(Snapshot snapshot, List<FixtureRow> fixtures) {
        Map<Integer, String> fingerprints = new LinkedHashMap<>();
        for (FixtureRow fixture : fixtures) {
            Set<Integer> sourcePlayers = new LinkedHashSet<>();
            snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                    || player.teamId() == fixture.awayTeamId()).map(PlayerRow::id).forEach(sourcePlayers::add);
            sourcePlayers.addAll(FixtureVerification.fixtureStatParticipantIds(fixture));
            String playerIds = sourcePlayers.stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            fingerprints.put(fixture.id(), OwnedSyncStore.sha256(
                    IncrementalAppearanceReceipt.fixtureRevisionFingerprint(fixture, fixture.gameweek())
                            + "|" + playerIds));
        }
        return Map.copyOf(fingerprints);
    }

    private static Map<Integer, FixtureVerification.FixtureEvidence> subsetEvidence(
            Map<Integer, FixtureVerification.FixtureEvidence> source, Set<Integer> fixtures) {
        Map<Integer, FixtureVerification.FixtureEvidence> result = new LinkedHashMap<>();
        source.forEach((fixture, evidence) -> {
            if (fixtures.contains(fixture)) result.put(fixture, evidence);
        });
        return Map.copyOf(result);
    }

    private static Set<Integer> allPlayers(Map<Integer, Set<Integer>> candidates) {
        Set<Integer> result = new HashSet<>();
        candidates.values().forEach(result::addAll);
        return result;
    }

    private static boolean pageFresh(PageReceipt receipt, Instant floor) {
        return receipt != null && !receipt.read().startedAt().isBefore(floor);
    }

    private static boolean sourceFresh(PageReceipt receipt, Instant floor) {
        // A live capture establishes the appearance floor at its own finish time.
        return receipt != null && !receipt.read().finishedAt().isBefore(floor);
    }

    private static SourceRead read(SourceDocument document) {
        return new SourceRead(document.readInterval().startedAt(), document.readInterval().completedAt());
    }

    private static SourceRead read(CatalogueScheduleParser.ReadInterval interval) {
        return new SourceRead(interval.startedAt(), interval.completedAt());
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant later(Instant first, Instant second) {
        return first == null || second != null && second.isAfter(first) ? second : first;
    }

    private static String failureSummary(List<Integer> failedPlayers, List<Integer> missingLiveEvents) {
        StringBuilder message = new StringBuilder();
        if (!failedPlayers.isEmpty()) message.append("Unresolved player pages: ").append(failedPlayers.size());
        if (!missingLiveEvents.isEmpty()) {
            if (!message.isEmpty()) message.append("; ");
            message.append("Unresolved event-live sources: ").append(missingLiveEvents.stream().distinct().count());
        }
        return bounded(message.toString());
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Incremental core-import cycle failed";
        return value.length() <= OwnedSyncStore.MAX_ERROR_CHARS
                ? value : value.substring(0, OwnedSyncStore.MAX_ERROR_CHARS);
    }

    private static Fetch fencedFetch(OwnedSyncStore store, OwnedSyncHttpClient http) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(http);
        return new Fetch() {
            @Override
            public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int attempt, Instant floor) {
                return get(lease, endpoint, attempt, floor, Demand.DISCOVERY);
            }

            @Override
            public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int attempt, Instant floor,
                                                  Demand demand) {
                return http.get(endpoint, attempt, () -> store.httpFence(lease, endpoint, floor), demand);
            }
        };
    }

    @FunctionalInterface
    interface Fetch {
        OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt, Instant floor);

        default OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int attempt, Instant floor,
                                              Demand demand) {
            return get(lease, endpoint, attempt, floor);
        }
    }

    public enum State { DISABLED, BUSY, DEFERRED, COMPLETED, RETRY_SCHEDULED, SUPERSEDED, FAILED }

    public record CycleResult(State state, List<Integer> selectedPlayerIds,
                              List<Integer> committedPlayerIds, List<Integer> failedPlayerIds,
                              List<Integer> finalPendingGameweeks, Instant nextDue) {
        public CycleResult {
            Objects.requireNonNull(state);
            selectedPlayerIds = List.copyOf(selectedPlayerIds);
            committedPlayerIds = List.copyOf(committedPlayerIds);
            failedPlayerIds = List.copyOf(failedPlayerIds);
            finalPendingGameweeks = List.copyOf(finalPendingGameweeks);
        }

        private static CycleResult disabled() {
            return new CycleResult(State.DISABLED, List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    private record CanonicalPairRequest(Lease lease, SourceDocument bootstrap, SourceDocument fixtures,
                                        RequestResult failure) { }

    private record RequestResult(Lease lease, SourceDocument document, Instant nextDue,
                                 String error, DeferredReason deferral) {
        boolean succeeded() { return document != null; }
        boolean deferred() { return deferral != null; }
    }

    private record PageNeed(Instant floor, int priority) { }
}
