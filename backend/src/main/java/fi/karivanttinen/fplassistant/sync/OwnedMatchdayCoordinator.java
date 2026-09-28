package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Input;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Plan;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkKind;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkSlot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;

/** Applies pure planner decisions to the fenced durable queue and dispatches one leased run. */
public final class OwnedMatchdayCoordinator {

    private static final Duration LEASE = Duration.ofMinutes(5);
    // Staged source bodies are evidence only while a run can still use them. Terminal runs keep
    // theirs for a day of diagnosis; without this bound one backlog pass stores ~170 MB and a
    // 500 MB database fills within days.
    static final Duration PAYLOAD_RETENTION = Duration.ofHours(24);
    private static final int MAX_PLAYERS_PER_SLOT = 32;

    private final Season season;
    private final AdaptiveMatchdayPlanner planner;
    private final PlannerInputRepository inputRepository;
    private final PlannerRuntimeRepository runtimeRepository;
    private final OwnedSyncStore store;
    private final CatalogueScheduleDispatcher catalogue;
    private final HistoryFixtureDispatcher histories;
    private final String instanceId;

    public OwnedMatchdayCoordinator(Season season, AdaptiveMatchdayPlanner planner,
                                    PlannerInputRepository inputRepository,
                                    PlannerRuntimeRepository runtimeRepository,
                                    OwnedSyncStore store,
                                    CatalogueScheduleDispatcher catalogue,
                                    HistoryFixtureDispatcher histories,
                                    String instanceId) {
        this.season = Objects.requireNonNull(season);
        this.planner = Objects.requireNonNull(planner);
        this.inputRepository = Objects.requireNonNull(inputRepository);
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository);
        this.store = Objects.requireNonNull(store);
        this.catalogue = Objects.requireNonNull(catalogue);
        this.histories = Objects.requireNonNull(histories);
        this.instanceId = Objects.requireNonNull(instanceId);
    }

    /** Startup catch-up and every short tick use the same persisted slot ledger. */
    public TickResult tick() {
        try {
            PlannerInputRepository.PlannerSnapshot source = inputRepository.load(season);
            Plan plan = planner.plan(new Input(season.code(), source.playerIds().size(), source.fixtures(),
                    source.progress(), List.of()));
            int materialized = 0;
            for (WorkSlot slot : plan.dueSlots()) {
                if ((slot.kind() == WorkKind.FULL_PLAYER_RECONCILE
                        || slot.kind() == WorkKind.AFFECTED_HISTORY)
                        && (!source.ownedDiscovery() || source.playerIds().isEmpty())) continue;
                Set<Integer> players = slot.kind() == WorkKind.FULL_PLAYER_RECONCILE
                        ? source.playerIds() : slot.playerIds();
                WorkSlot expanded = players.equals(slot.playerIds()) ? slot : new WorkSlot(slot.id(), slot.kind(),
                        slot.dueAt(), slot.fixtureIds(), players, slot.reason(), players.size());
                for (WorkSlot bounded : boundedPlayerSlots(expanded)) {
                    if (runtimeRepository.enqueueOnce(season.code(), bounded, this::enqueue)) materialized++;
                }
            }
            runtimeRepository.heartbeat(instanceId);
            return new TickResult(materialized, plan.dueSlots().size(), plan.alerts().size(), plan.nextDueAt().orElse(null));
        } catch (RuntimeException failure) {
            runtimeRepository.recordError(instanceId, bounded(failure));
            throw failure;
        }
    }

    /** Drops staged bodies of finished runs older than the retention; active work is untouched. */
    public int pruneStagedPayloads() {
        return store.deleteTerminalPayloadsOlderThan(PAYLOAD_RETENTION);
    }

    /** Claims and routes one run. Database leases/fences remain authoritative across instances. */
    public boolean dispatchOne(String workerId) {
        Lease lease = store.acquireFairForSeason(season.code(), instanceId + ":" + workerId, LEASE).orElse(null);
        if (lease == null) return false;
        if (!lease.season().equals(season.code())) {
            store.fail(lease, "Owned runtime acquired a run for an unconfigured season");
            return true;
        }
        if (lease.kind() == SyncKind.CATALOGUE_SCHEDULE) {
            catalogue.process(lease, season);
        } else if (lease.kind() == SyncKind.PLAYER_HISTORY
                || lease.kind() == SyncKind.FIXTURE_VERIFICATION) {
            histories.process(lease, season);
        } else {
            store.fail(lease, "Unsupported owned runtime kind: " + lease.kind());
        }
        return true;
    }

    private static List<WorkSlot> boundedPlayerSlots(WorkSlot slot) {
        if ((slot.kind() != WorkKind.FULL_PLAYER_RECONCILE && slot.kind() != WorkKind.AFFECTED_HISTORY)
                || slot.playerIds().size() <= MAX_PLAYERS_PER_SLOT) return List.of(slot);
        // Element-id ranges stay stable when roster membership changes, unlike list-index chunks.
        Map<Integer, Set<Integer>> playersByRange = new LinkedHashMap<>();
        slot.playerIds().stream().sorted().forEach(player -> playersByRange
                .computeIfAbsent(Math.floorDiv(player - 1, MAX_PLAYERS_PER_SLOT),
                        ignored -> new HashSet<>()).add(player));
        return playersByRange.entrySet().stream().map(entry -> new WorkSlot(
                slot.id() + ":player-range=" + entry.getKey(), slot.kind(), slot.dueAt(),
                slot.fixtureIds(), entry.getValue(), slot.reason(), entry.getValue().size())).toList();
    }

    private void enqueue(WorkSlot slot) {
        switch (slot.kind()) {
            case DAILY_CATALOGUE -> catalogue.enqueueCatalogue(season, slot.dueAt());
            case DAILY_SCHEDULE -> catalogue.enqueueSchedule(season, slot.dueAt());
            case MATCHDAY_SOURCE -> catalogue.enqueueCycle(season, slot.dueAt());
            case FULL_PLAYER_RECONCILE, AFFECTED_HISTORY -> slot.playerIds().stream().sorted()
                    .forEach(player -> histories.enqueuePlayerHistory(season, player, slot.dueAt()));
            case FIXTURE_VERIFICATION -> slot.fixtureIds().stream().sorted().forEach(fixture -> {
                if (slot.reason() == AdaptiveMatchdayPlanner.SlotReason.FINISH_OBSERVED) {
                    histories.enqueueFixtureVerification(season, fixture, slot.dueAt());
                } else {
                    histories.enqueuePersistedFixtureRetry(season, fixture, slot.dueAt());
                }
            });
            case FIXTURE_CORRECTION -> slot.fixtureIds().stream().sorted()
                    .forEach(fixture -> histories.enqueueFixtureVerification(season, fixture, slot.dueAt()));
        }
    }

    private static String bounded(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    public record TickResult(int materializedSlots, int dueSlots, int alerts,
                             java.time.Instant nextDueAt) { }
}
