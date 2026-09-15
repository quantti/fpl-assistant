package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

/** Local-only full-size probe for durable warm-start progress under realistic fan-out pressure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnedMatchdayWarmStartPressureIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_pressure_daily_price_test", "fpl_pressure_gameweek_test", "fpl_pressure_fixture_test");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final int COMPLETED_FIXTURES = 40;
    private static final int MAX_DISPATCHES = 1_500;
    private static final int SELECTION_ROUNDS = 10;
    private static final int RETAINED_SELECTIONS = 3;
    private static final Duration MAX_VIRTUAL_TIME = Duration.ofHours(1);
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static boolean initialized;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @LocalServerPort
    private int serverPort;

    @BeforeAll
    static void initialize() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required("DATABASE_URL"));
        dataSource.setUsername(required("DB_USER"));
        dataSource.setPassword(required("DB_PASSWORD"));
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);
        jdbc = new JdbcTemplate(dataSource);
        SharedStatsSeed.ensureOwnedSyncCaptureSeason(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        createCapturedTables();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        initialized = true;
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            try {
                // JUnit also invokes cleanup when the production-database guard refused setup.
                if (initialized) resetProbeRows();
            } finally {
                dataSource.close();
            }
        }
    }

    @Test
    void fullSizeWarmStartCompletesCanonicalAndHistoryStagingWithoutStarvation() throws Exception {
        assertThat(applicationContext.isActive()).isTrue();
        assertThat(serverPort).isPositive();
        long wallStarted = System.nanoTime();
        MemoryDiagnostics memory = new MemoryDiagnostics();
        System.out.printf("PRESSURE_JVM_FLAGS %s%n",
                ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.printf("PRESSURE_LIMITATION dispatch=SERIAL ticks=2 scope=warm-start-plus-"
                + "planner-and-two-worker-selection-retention-not-full-runtime-soak "
                + "selectionThreads=3 selectionRounds=%d springWebContext=RANDOM_PORT port=%d%n",
                SELECTION_ROUNDS, serverPort);
        memory.sample(0, "spring-context-ready");
        resetProbeRows();

        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T04:00:00Z"));
        Instant startedAt = clock.instant();
        FakeFullSizeFpl fpl = new FakeFullSizeFpl(clock);
        PlannerRuntimeRepository activator = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        RuntimeFence fence = activator.activate(SEASON.code(), OwnedSyncMode.STAGING);
        OwnedSyncStore store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
        CatalogueScheduleDispatcher catalogue = new CatalogueScheduleDispatcher(store, null, fpl, clock, false);
        HistoryFixtureDispatcher histories = new HistoryFixtureDispatcher(store, null, fpl, clock, false);
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        OwnedMatchdayCoordinator coordinator = new OwnedMatchdayCoordinator(SEASON,
                new AdaptiveMatchdayPlanner(clock), inputs, runtime, store, catalogue, histories, "pressure-probe");

        // The first tick can only discover daily canonical work. The second uses that strict parsed
        // snapshot to materialize the full catalogue reconcile and completed-fixture work.
        coordinator.tick();
        int dispatches = drainDue(coordinator, 0, memory);
        OwnedMatchdayCoordinator.TickResult warmTick = coordinator.tick();
        assertThat(fpl.playerCount()).isEqualTo(657);
        assertThat(warmTick.materializedSlots()).isGreaterThan(20);

        int peakPendingHistories = pendingHistories();
        int idleAdvances = 0;
        while (activeRuns() > 0 && dispatches < MAX_DISPATCHES
                && Duration.between(startedAt, clock.instant()).compareTo(MAX_VIRTUAL_TIME) <= 0) {
            if (coordinator.dispatchOne("probe-" + dispatches)) {
                dispatches++;
                memory.sampleIfDue(dispatches, "history-drain");
                peakPendingHistories = Math.max(peakPendingHistories, pendingHistories());
            } else {
                Timestamp due = jdbc.queryForObject("SELECT min(due_at) FROM " + TABLES.run()
                        + " WHERE season=? AND state='PENDING'", Timestamp.class, SEASON.code());
                if (due == null) break;
                assertThat(due.toInstant()).isAfter(clock.instant());
                clock.set(due.toInstant());
                idleAdvances++;
            }
        }

        retainThreeSelectedSnapshots(store, memory, dispatches);
        memory.sample(dispatches, "serial-drain-and-selection-complete");
        int active = activeRuns();
        Map<String, Integer> completed = completedByKind();
        Duration virtualElapsed = Duration.between(startedAt, clock.instant());
        assertThat(fpl.firstHistory).as("history dispatch must begin").isNotNull();
        System.out.printf("PRESSURE_PROBE players=%d completedFixtures=%d dispatches=%d http=%d "
                        + "bootstrap=%d fixtures=%d live=%d history=%d publicPairFetches=%d "
                        + "catalogueDispatches=%d idleAdvances=%d virtualSeconds=%d "
                        + "peakPendingHistories=%d completed=%s active=%d firstHistorySeconds=%d "
                        + "sampledPeakRssMiB=%.1f%n",
                fpl.playerCount(), COMPLETED_FIXTURES, dispatches, fpl.totalRequests(),
                fpl.requests(EndpointKind.BOOTSTRAP_STATIC), fpl.requests(EndpointKind.FIXTURES),
                fpl.requests(EndpointKind.EVENT_LIVE), fpl.requests(EndpointKind.ELEMENT_SUMMARY),
                fpl.publicPairFetches(), completed.getOrDefault("CATALOGUE_SCHEDULE", 0),
                idleAdvances, virtualElapsed.toSeconds(), peakPendingHistories, completed, active,
                Duration.between(startedAt, fpl.firstHistory).toSeconds(), memory.sampledPeakRssMiB());

        assertThat(active).as("bounded probe must distinguish finite delay from queue starvation").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE published_at IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence(),
                Integer.class)).isZero();
        assertThat(dispatches).isLessThan(MAX_DISPATCHES);
        assertThat(virtualElapsed).isLessThanOrEqualTo(MAX_VIRTUAL_TIME);
        assertThat(completed.getOrDefault("PLAYER_HISTORY", 0)).isGreaterThanOrEqualTo(fpl.playerCount());
        assertThat(completed.getOrDefault("FIXTURE_VERIFICATION", 0)).isEqualTo(COMPLETED_FIXTURES);
        assertThat(peakPendingHistories).isGreaterThanOrEqualTo(fpl.playerCount());
        assertThat(fpl.requests(EndpointKind.ELEMENT_SUMMARY)).isGreaterThanOrEqualTo(fpl.playerCount());

        memory.sampleAfterDiagnosticGc(dispatches);
        System.out.printf("PRESSURE_MEMORY_SUMMARY semantics=sampled-workload-peaks-and-"
                        + "post-release-explicit-gc-retained-estimate peakHeapUsedMiB=%.1f "
                        + "peakHeapCommittedMiB=%.1f sampledPeakRssMiB=%.1f "
                        + "processPeakRssMiB=%.1f retainedHeapUsedMiB=%.1f "
                        + "endHeapCommittedMiB=%.1f endRssMiB=%.1f wallSeconds=%.1f%n",
                memory.sampledPeakHeapUsedMiB(), memory.sampledPeakHeapCommittedMiB(),
                memory.sampledPeakRssMiB(), memory.processPeakRssMiB(), memory.latestHeapUsedMiB(),
                memory.latestHeapCommittedMiB(), memory.latestRssMiB(),
                (System.nanoTime() - wallStarted) / 1_000_000_000.0);
    }

    private static void retainThreeSelectedSnapshots(OwnedSyncStore store, MemoryDiagnostics memory,
            int dispatches) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(RETAINED_SELECTIONS);
        CountDownLatch release = null;
        List<Future<Snapshot>> futures = new ArrayList<>(RETAINED_SELECTIONS);
        try {
            for (int round = 1; round <= SELECTION_ROUNDS; round++) {
                CountDownLatch ready = new CountDownLatch(RETAINED_SELECTIONS);
                release = new CountDownLatch(1);
                CountDownLatch roundRelease = release;
                for (int worker = 0; worker < RETAINED_SELECTIONS; worker++) {
                    futures.add(workers.submit(() -> {
                        Snapshot snapshot = selectCanonicalSnapshot(store);
                        ready.countDown();
                        if (!roundRelease.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out retaining selected snapshot");
                        }
                        return snapshot;
                    }));
                }
                assertThat(ready.await(20, TimeUnit.SECONDS))
                        .as("all three strict parsed snapshots must be retained together in round %s", round)
                        .isTrue();
                memory.sample(dispatches, "selection-three-held-" + round);
                release.countDown();
                for (Future<Snapshot> future : futures) {
                    Snapshot snapshot = future.get(20, TimeUnit.SECONDS);
                    assertThat(snapshot.players()).hasSize(657);
                    assertThat(snapshot.fixtures()).hasSize(380);
                }
                futures.clear();
                release = null;
            }
        } finally {
            if (release != null) release.countDown();
            futures.forEach(future -> future.cancel(true));
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS))
                    .as("selection workers must terminate").isTrue();
        }
    }

    private static Snapshot selectCanonicalSnapshot(OwnedSyncStore store) {
        return store.findInLatestValidPublicPayloadPairs(SEASON.code(), 20, pair -> {
            try {
                return java.util.Optional.of(CatalogueScheduleParser.parse(
                        SEASON, document(pair.bootstrap()), document(pair.fixtures())));
            } catch (IllegalArgumentException ignored) {
                return java.util.Optional.empty();
            }
        }).orElseThrow(() -> new IllegalStateException("no strict valid canonical snapshot"));
    }

    private static SourceDocument document(OwnedSyncStore.StagedPayload payload) {
        return new SourceDocument(payload.body(), new ReadInterval(
                payload.read().startedAt(), payload.read().finishedAt()));
    }

    private static int drainDue(OwnedMatchdayCoordinator coordinator, int initial,
            MemoryDiagnostics memory) throws Exception {
        int dispatches = initial;
        while (dispatches < MAX_DISPATCHES && coordinator.dispatchOne("canonical-" + dispatches)) {
            dispatches++;
            memory.sampleIfDue(dispatches, "canonical-drain");
        }
        return dispatches;
    }

    private static final class MemoryDiagnostics {
        private static final int SAMPLE_INTERVAL = 30;
        private long sampledPeakRssKiB;
        private long processPeakRssKiB;
        private long sampledPeakHeapUsedBytes;
        private long sampledPeakHeapCommittedBytes;
        private long latestRssKiB;
        private long latestHeapUsedBytes;
        private long latestHeapCommittedBytes;

        void sampleIfDue(int dispatches, String stage) throws Exception {
            if (dispatches % SAMPLE_INTERVAL == 0) sample(dispatches, stage);
        }

        void sample(int dispatches, String stage) throws Exception {
            Map<String, Long> status = procStatus();
            long rssKiB = status.getOrDefault("VmRSS", -1L);
            long processPeakKiB = status.getOrDefault("VmHWM", -1L);
            sampledPeakRssKiB = Math.max(sampledPeakRssKiB, rssKiB);
            processPeakRssKiB = Math.max(processPeakRssKiB, processPeakKiB);
            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            long heapCommitted = runtime.totalMemory();
            sampledPeakHeapUsedBytes = Math.max(sampledPeakHeapUsedBytes, heapUsed);
            sampledPeakHeapCommittedBytes = Math.max(sampledPeakHeapCommittedBytes, heapCommitted);
            latestRssKiB = rssKiB;
            latestHeapUsedBytes = heapUsed;
            latestHeapCommittedBytes = heapCommitted;
            MemoryPoolMXBean metaspace = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getName().equals("Metaspace"))
                    .findFirst().orElseThrow();
            System.out.printf("PRESSURE_MEMORY dispatches=%d stage=%s rssMiB=%.1f sampledPeakRssMiB=%.1f "
                            + "processPeakRssMiB=%.1f heapUsedMiB=%.1f heapCommittedMiB=%.1f "
                            + "metaspaceUsedMiB=%.1f metaspaceCommittedMiB=%.1f%n",
                    dispatches, stage, mib(rssKiB), sampledPeakRssMiB(), mib(processPeakKiB),
                    mib(heapUsed / 1024), mib(heapCommitted / 1024),
                    mib(metaspace.getUsage().getUsed() / 1024),
                    mib(metaspace.getUsage().getCommitted() / 1024));
        }

        void sampleAfterDiagnosticGc(int dispatches) throws Exception {
            long collections = collectionCount();
            System.gc(); // Diagnostic test only: estimate retained heap after selected snapshots are released.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (collectionCount() == collections && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            sample(dispatches, "end-after-diagnostic-explicit-gc");
        }

        double sampledPeakRssMiB() { return mib(sampledPeakRssKiB); }
        double processPeakRssMiB() { return mib(processPeakRssKiB); }
        double sampledPeakHeapUsedMiB() { return bytesToMiB(sampledPeakHeapUsedBytes); }
        double sampledPeakHeapCommittedMiB() { return bytesToMiB(sampledPeakHeapCommittedBytes); }
        double latestRssMiB() { return mib(latestRssKiB); }
        double latestHeapUsedMiB() { return bytesToMiB(latestHeapUsedBytes); }
        double latestHeapCommittedMiB() { return bytesToMiB(latestHeapCommittedBytes); }

        private static long collectionCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                    .filter(count -> count >= 0).sum();
        }

        private static Map<String, Long> procStatus() throws Exception {
            Map<String, Long> values = new HashMap<>();
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (!line.startsWith("VmRSS:") && !line.startsWith("VmHWM:")) continue;
                String[] fields = line.trim().split("\\s+");
                values.put(fields[0].substring(0, fields[0].length() - 1), Long.parseLong(fields[1]));
            }
            return values;
        }

        private static double mib(long kibibytes) {
            return kibibytes < 0 ? -1.0 : kibibytes / 1024.0;
        }

        private static double bytesToMiB(long bytes) {
            return bytes < 0 ? -1.0 : bytes / 1024.0 / 1024.0;
        }
    }

    private static int activeRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND state IN ('PENDING','LEASED')", Integer.class, SEASON.code());
    }

    private static int pendingHistories() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season=? AND kind='PLAYER_HISTORY' AND state IN ('PENDING','LEASED')",
                Integer.class, SEASON.code());
    }

    private static Map<String, Integer> completedByKind() {
        Map<String, Integer> result = new HashMap<>();
        jdbc.queryForList("SELECT kind,count(*) AS total FROM " + TABLES.run()
                + " WHERE season=? AND state='COMPLETED' GROUP BY kind ORDER BY kind", SEASON.code())
                .forEach(row -> result.put((String) row.get("kind"), ((Number) row.get("total")).intValue()));
        return result;
    }

    private static void resetProbeRows() {
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + "," + TABLES.fixtureSync() + ","
                + TABLES.historyFingerprint() + "," + TABLES.payload() + "," + TABLES.run() + ","
                + TABLES.receipt() + " CASCADE");
        jdbc.update("DELETE FROM " + CAPTURED.fixture() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek() + " WHERE season=?", SEASON.code());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice() + " WHERE season=?", SEASON.code());
    }

    private static void createCapturedTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.dailyPrice() + " (season varchar(10),"
                + " price_date date, element_id integer, team_id integer, captured_at timestamptz NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.gameweek() + " (season varchar(10), id integer,"
                + " data_checked boolean NOT NULL DEFAULT false, captured_at timestamptz NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CAPTURED.fixture() + " (season varchar(10), id integer,"
                + " gameweek integer, team_h integer, team_a integer, kickoff_time timestamptz,"
                + " started boolean NOT NULL DEFAULT false, finished boolean NOT NULL DEFAULT false,"
                + " captured_at timestamptz NOT NULL DEFAULT now())");
    }

    private static final class FakeFullSizeFpl implements CatalogueScheduleDispatcher.Fetch,
            HistoryFixtureDispatcher.Fetch {
        private final MutableClock clock;
        private final ObjectNode bootstrap;
        private final ArrayNode fixtures;
        private final Map<Integer, List<Integer>> playersByTeam = new HashMap<>();
        private final Map<Integer, Integer> playerTeams = new HashMap<>();
        private final Map<EndpointKind, AtomicInteger> requests = new HashMap<>();
        private final byte[] bootstrapBytes;
        private final byte[] fixtureBytes;
        private Instant firstHistory;

        FakeFullSizeFpl(MutableClock clock) throws Exception {
            this.clock = clock;
            bootstrap = (ObjectNode) JSON.readTree(resource("bootstrap-static.json"));
            fixtures = (ArrayNode) JSON.readTree(resource("fixtures.json"));
            bootstrap.withArray("elements").forEach(player -> {
                int id = player.get("id").intValue();
                int team = player.get("team").intValue();
                playerTeams.put(id, team);
                playersByTeam.computeIfAbsent(team, ignored -> new ArrayList<>()).add(id);
            });
            playersByTeam.values().forEach(ids -> ids.sort(Comparator.naturalOrder()));
            makeFirstFixturesSyntheticAndComplete();
            bootstrapBytes = JSON.writeValueAsBytes(bootstrap);
            fixtureBytes = JSON.writeValueAsBytes(fixtures);
        }

        @Override
        public OwnedSyncHttpClient.Result get(Endpoint endpoint, int attempt) {
            try {
                requests.computeIfAbsent(endpoint.kind(), ignored -> new AtomicInteger()).incrementAndGet();
                Instant readStarted = clock.instant();
                if (endpoint.kind() == EndpointKind.ELEMENT_SUMMARY && firstHistory == null) {
                    firstHistory = readStarted;
                }
                clock.advance(Duration.ofSeconds(1)); // virtual lower bound per attempted FPL request
                byte[] body = switch (endpoint.kind()) {
                    case BOOTSTRAP_STATIC -> bootstrapBytes;
                    case FIXTURES -> fixtureBytes;
                    case EVENT_LIVE -> live(endpoint.eventId());
                    case ELEMENT_SUMMARY -> history(endpoint.playerId());
                };
                return new Response(200, body, null,
                        new SourceRead(readStarted, clock.instant()), null);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        int playerCount() { return bootstrap.withArray("elements").size(); }
        int requests(EndpointKind kind) { return requests.getOrDefault(kind, new AtomicInteger()).get(); }
        int totalRequests() { return requests.values().stream().mapToInt(AtomicInteger::get).sum(); }
        int publicPairFetches() {
            assertThat(requests(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(requests(EndpointKind.FIXTURES));
            return requests(EndpointKind.BOOTSTRAP_STATIC);
        }

        private void makeFirstFixturesSyntheticAndComplete() {
            for (int index = 0; index < fixtures.size(); index++) {
                ObjectNode fixture = (ObjectNode) fixtures.get(index);
                if (index >= COMPLETED_FIXTURES) continue;
                fixture.put("started", true).put("finished", true).put("finished_provisional", true)
                        .put("minutes", 90).put("team_h_score", 0).put("team_a_score", 0);
                // Every zero in this generated result/manifest is synthetic probe data, not an
                // inferred source value. Eleven deterministic players per side are participants.
                ArrayNode stats = fixture.putArray("stats");
                ObjectNode minutes = stats.addObject().put("identifier", "minutes");
                addManifest(minutes.putArray("h"), fixture.get("team_h").intValue());
                addManifest(minutes.putArray("a"), fixture.get("team_a").intValue());
            }
            bootstrap.withArray("events").forEach(event -> {
                int id = event.get("id").intValue();
                ((ObjectNode) event).put("finished", id <= 4).put("data_checked", id <= 4)
                        .put("is_current", id == 4).put("is_next", id == 5);
            });
        }

        private void addManifest(ArrayNode side, int team) {
            playersByTeam.get(team).stream().limit(11)
                    .forEach(player -> side.addObject().put("element", player).put("value", 90));
        }

        private byte[] live(int event) throws Exception {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode elements = root.putArray("elements");
            Map<Integer, ObjectNode> byPlayer = new HashMap<>();
            fixtures.forEach(value -> {
                if (value.get("event").intValue() != event || !value.get("finished").booleanValue()) return;
                int fixture = value.get("id").intValue();
                for (int team : List.of(value.get("team_h").intValue(), value.get("team_a").intValue())) {
                    playersByTeam.get(team).stream().limit(11).forEach(player -> {
                        ObjectNode element = byPlayer.computeIfAbsent(player, id -> {
                            ObjectNode created = JSON.createObjectNode().put("id", id);
                            created.putArray("explain");
                            return created;
                        });
                        ObjectNode explanation = element.withArray("explain").addObject().put("fixture", fixture);
                        explanation.putArray("stats").addObject().put("identifier", "minutes")
                                .put("value", 90).put("points", 0).put("points_modification", 0);
                    });
                }
            });
            byPlayer.values().stream().sorted(Comparator.comparingInt(node -> node.get("id").intValue()))
                    .forEach(elements::add);
            return JSON.writeValueAsBytes(root);
        }

        private byte[] history(int player) throws Exception {
            int team = playerTeams.get(player);
            ObjectNode root = JSON.createObjectNode();
            ArrayNode rows = root.putArray("history");
            fixtures.forEach(value -> {
                if (!value.get("finished").booleanValue()) return;
                int home = value.get("team_h").intValue();
                int away = value.get("team_a").intValue();
                if (team != home && team != away) return;
                boolean wasHome = team == home;
                boolean appeared = playersByTeam.get(team).indexOf(player) < 11;
                ObjectNode row = rows.addObject();
                row.put("element", player).put("fixture", value.get("id").intValue())
                        .put("round", value.get("event").intValue())
                        .put("opponent_team", wasHome ? away : home).put("was_home", wasHome)
                        .put("kickoff_time", value.get("kickoff_time").textValue())
                        .put("minutes", appeared ? 90 : 0).put("total_points", 0).put("bonus", 0).put("bps", 0)
                        .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0).put("goals_conceded", 0)
                        .put("own_goals", 0).put("penalties_saved", 0).put("penalties_missed", 0).put("saves", 0)
                        .put("yellow_cards", 0).put("red_cards", 0).put("starts", appeared ? 1 : 0)
                        .put("value", 50).put("selected", 0).put("transfers_in", 0).put("transfers_out", 0)
                        .put("expected_goals", "0.00").put("expected_assists", "0.00")
                        .put("expected_goal_involvements", "0.00").put("expected_goals_conceded", "0.00")
                        .put("influence", "0.0").put("creativity", "0.0").put("threat", "0.0")
                        .put("ict_index", "0.0").put("clearances_blocks_interceptions", 0)
                        .put("recoveries", 0).put("tackles", 0).put("defensive_contribution", 0);
            });
            return JSON.writeValueAsBytes(root);
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = OwnedMatchdayWarmStartPressureIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("missing " + name);
            return input.readAllBytes();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        MutableClock(Instant value) { this.value = value; }
        void set(Instant value) { this.value = value; }
        void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
