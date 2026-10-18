package dev.quantti.fplassistant.tools;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.quantti.fplassistant.price.*;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.*;
import dev.quantti.fplassistant.price.PricePredictionSchema.Tables;

import static org.assertj.core.api.Assertions.*;

class PriceForecastToolsIntegrationTest {
    private static final Tables TABLES = Tables.test();
    private static final Instant READ = Instant.parse("2026-10-18T16:00:00Z");
    private static final Instant TARGET = Instant.parse("2026-10-18T23:00:00Z");
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private PricePredictionStore store;
    private PricePredictionPermission permission;
    private PricePredictionSnapshot snapshot;
    private int fingerprint;

    @BeforeAll
    static void disposableDatabaseOnly() {
        String url = System.getenv("DATABASE_URL");
        boolean local = url != null && url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):55432/postgres");
        boolean ci = "true".equals(System.getenv("GITHUB_ACTIONS")) && url != null
                && url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/postgres");
        if (!local && !ci) throw new IllegalStateException("Requires disposable loopback test database");
        dataSource = new DriverManagerDataSource(url, System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of(TABLES.changeEvent(), TABLES.changeCapture(), TABLES.forecastPlayer(), TABLES.forecastCapture())) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        PricePredictionSchema.create(jdbc, TABLES);
        store = new PricePredictionStore(jdbc, new DataSourceTransactionManager(dataSource), TABLES);
        store.enablePublishing();
        permission = new PricePredictionPermission(new MockEnvironment().withProperty("fpl.price-prediction.enabled", "true"));
        Map<Integer, Team> teams = new LinkedHashMap<>();
        for (int teamId = 1; teamId <= 20; teamId++) teams.put(teamId, new Team(teamId,
                teamId == 1 ? "Newcastle" : "Man Utd", teamId == 1 ? "NEW" : "MUN"));
        snapshot = new PricePredictionSnapshot("2026-27", READ, READ.minusSeconds(60), TARGET, teams,
                Map.of(1, player(1, "Muharemović", 1, State.READY, 5, "101"),
                        2, player(2, "Hall", 1, State.READY, 4, "90"),
                        3, player(3, "Hall", 2, State.READY, -5, "-110")));
    }

    private static Player player(int elementId, String name, int teamId, State state, int likelihood, String progress) {
        Signal signal = switch (likelihood) {
            case 5 -> Signal.VERY_LIKELY_RISE;
            case 4 -> Signal.LIKELY_RISE;
            case -5 -> Signal.VERY_LIKELY_DROP;
            case -4 -> Signal.LIKELY_DROP;
            default -> Signal.UNLIKELY_TO_CHANGE;
        };
        return new Player(elementId, teamId, 3, name, "Test " + name, 75,
                new Forecast(state, new BigDecimal(progress), new BigDecimal(progress).add(BigDecimal.ONE),
                        likelihood, signal, state == State.LOCKED ? TARGET : null));
    }

    private void publish(PricePredictionSnapshot next) {
        var lease = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        try {
            assertThat(store.publishForecast(lease, "%064x".formatted(++fingerprint), next, () -> true))
                    .isEqualTo(PricePredictionStore.PublicationResult.REPLACED);
        } finally {
            store.release(lease);
        }
    }

    private void history(List<Change> changes) {
        var lease = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        try {
            store.publishRecentChanges(lease, "%064x".formatted(++fingerprint),
                    new RecentChanges(snapshot.season(), READ.plusSeconds(1), READ, changes), snapshot, () -> true);
        } finally {
            store.release(lease);
        }
    }

    private PriceForecastTools tools(Instant now) { return tools(now, jdbc, "2026-27"); }

    private PriceForecastTools tools(Instant now, JdbcTemplate lookup, String season) {
        return new PriceForecastTools(lookup, new PlayerNameSearch(jdbc), store, permission,
                new PricePredictionConfiguration.Settings(season, TABLES, URI.create("http://localhost:9/"),
                        URI.create("http://localhost:9/")), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void namesUseAccentsClubTokensExactPreferenceAndAmbiguity() {
        publish(snapshot);
        assertThat(tools(READ).getPlayerPriceForecast("Muharemovic NEW"))
                .contains("Muharemović", "Very likely to rise", "£7.5m").doesNotContain("also matched");
        assertThat(tools(READ).getPlayerPriceForecast("Hall MUN"))
                .contains("Hall (Man Utd)", "Very likely to drop").doesNotContain("also matched");
        assertThat(tools(READ).getPlayerPriceForecast("Hall"))
                .contains("Answered for Hall (Newcastle)", "also matched: Hall (Man Utd)");
        assertThat(tools(READ).getPlayerPriceForecast("Newcastle")).contains("Player not found");
        assertThat(tools(READ).getPlayerPriceForecast(" ")).contains("Player not found");
        assertThat(tools(READ).getPlayerPriceForecast("' OR 1=1 --")).contains("Player not found");
        var players = new LinkedHashMap<>(snapshot.players());
        players.put(4, player(4, "Halliday", 1, State.READY, 5, "102"));
        publish(new PricePredictionSnapshot(snapshot.season(), READ.plusSeconds(1), READ, TARGET, snapshot.teams(), players));
        assertThat(tools(READ.plusSeconds(1)).getPlayerPriceForecast("Hall"))
                .contains("Answered for Hall (Newcastle)", "Halliday");
    }

    @Test
    void samePayloadCheckTimeDoesNotRenewPredictorOrOriginalReadTime() {
        publish(snapshot);
        var lease = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
        var checked = new PricePredictionSnapshot(snapshot.season(), READ.plusSeconds(2400),
                snapshot.sourceUpdatedAt(), TARGET, snapshot.teams(), snapshot.players());
        store.publishForecast(lease, "%064x".formatted(fingerprint), checked, () -> true);
        store.release(lease);
        assertThat(tools(READ.plusSeconds(2800)).getPlayerPriceForecast("Hall NEW"))
                .contains("checkedAt UTC: 2026-10-18T16:40:00Z", "original payload readAt UTC: 2026-10-18T16:00:00Z",
                        "updatedAt UTC: 2026-10-18T15:59:00Z", "forecast unavailable: SOURCE_AGE", "£7.5m")
                .doesNotContain("Likely to rise");
    }

    @Test
    void exactTargetExpiresEvenWithFreshReadAndDoesNotHideUsablePrice() {
        publish(new PricePredictionSnapshot(snapshot.season(), TARGET.minusSeconds(10), TARGET.minusSeconds(20),
                TARGET, snapshot.teams(), snapshot.players()));
        assertThat(tools(TARGET).getPlayerPriceForecast("Hall NEW"))
                .contains("2026-10-18T23:00:00Z (expired)", "forecast unavailable: TARGET", "£7.5m")
                .doesNotContain("Likely to rise");
    }

    @Test
    void captureBoundaryAndSourceInvalidityNeverInventSignals() {
        publish(new PricePredictionSnapshot(snapshot.season(), READ, READ, TARGET, snapshot.teams(), snapshot.players()));
        assertThat(tools(READ.plusSeconds(2700)).getPlayerPriceForecast("Hall NEW")).contains("Likely to rise");
        assertThat(tools(READ.plusSeconds(2701)).getPlayerPriceForecast("Hall NEW"))
                .contains("CAPTURE_AGE", "current market price unavailable").doesNotContain("£7.5m", "Likely to rise");
        publish(new PricePredictionSnapshot(snapshot.season(), READ.plusSeconds(1), null, TARGET, snapshot.teams(), snapshot.players()));
        assertThat(tools(READ.plusSeconds(1)).getPlayerPriceForecast("Hall NEW"))
                .contains("SOURCE_AGE", "£7.5m").doesNotContain("Likely to rise");
        publish(new PricePredictionSnapshot(snapshot.season(), READ.plusSeconds(2), READ.plusSeconds(3),
                TARGET, snapshot.teams(), snapshot.players()));
        assertThat(tools(READ.plusSeconds(4)).getPlayerPriceForecast("Hall NEW")).contains("SOURCE_AGE");
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"CALIBRATING", "LOCKED", "UNAVAILABLE", "UNSUPPORTED"})
    void unusablePlayerStateKeepsPriceWithoutActionableSignal(State state) {
        publish(new PricePredictionSnapshot(snapshot.season(), READ, READ, TARGET, snapshot.teams(),
                Map.of(1, player(1, "Test", 1, state, 5, "120"))));
        assertThat(tools(READ).getPlayerPriceForecast("Test"))
                .contains("£7.5m", "PLAYER_STATE", state.name()).doesNotContain("Very likely to rise", "progress 120");
        assertThat(tools(READ).getPriceChangeCandidates("rise", 20)).contains("usable 0/1", "returned 0");
    }

    @Test
    void neutralIsUsefulAndGuideOccursOnce() {
        publish(new PricePredictionSnapshot(snapshot.season(), READ, READ, TARGET, snapshot.teams(),
                Map.of(1, player(1, "Test", 1, State.READY, 0, "12"))));
        assertThat(tools(READ).getPlayerPriceForecast("Test"))
                .contains("Unlikely to change", "progress 12%", "not probability")
                .containsOnlyOnce("guide, not a guarantee");
    }

    @Test
    void warningUsesCadenceAndEveningGrace() {
        Instant start = Instant.parse("2026-10-18T14:40:00Z");
        publish(new PricePredictionSnapshot(snapshot.season(), start, start, TARGET, snapshot.teams(), snapshot.players()));
        assertThat(tools(Instant.parse("2026-10-18T15:09:00Z")).getPlayerPriceForecast("Hall NEW"))
                .doesNotContain("20-minute warning");
        assertThat(tools(Instant.parse("2026-10-18T15:10:00Z")).getPlayerPriceForecast("Hall NEW"))
                .contains("20-minute warning");
    }

    @Test
    void directionOrderUsesClassificationThenSignedProgressThenStableIdAndClampsLimits() {
        Map<Integer, Player> players = new LinkedHashMap<>();
        for (int elementId = 1; elementId <= 23; elementId++) {
            players.put(elementId, player(elementId, "Rise" + elementId, 1, State.READY, 5, "101"));
        }
        players.put(24, player(24, "WeakRise", 1, State.READY, 4, "999"));
        players.put(25, player(25, "TopRise", 1, State.READY, 5, "120"));
        players.put(26, player(26, "DropLow", 1, State.READY, -5, "-120"));
        players.put(27, player(27, "DropHigh", 1, State.READY, -5, "-100"));
        players.put(28, player(28, "Locked", 1, State.LOCKED, 5, "130"));
        publish(new PricePredictionSnapshot(snapshot.season(), READ, READ, TARGET, snapshot.teams(), players));
        String rises = tools(READ).getPriceChangeCandidates("RISE", 100);
        assertThat(rises).contains("usable 27/28", "filtered 25", "returned 20; truncated 5", "completeness is not proven")
                .doesNotContain("WeakRise", "Locked (");
        assertThat(rises.indexOf("TopRise (")).isLessThan(rises.indexOf("Rise1 ("));
        assertThat(rises.indexOf("Rise1 (")).isLessThan(rises.indexOf("Rise2 ("));
        assertThat(tools(READ).getPriceChangeCandidates("drop", -1)).contains("DropLow", "returned 1; truncated 1")
                .doesNotContain("DropHigh");
        assertThat(tools(READ).getPriceChangeCandidates("sideways", 1)).isEqualTo("Direction must be rise or drop.");
        assertThat(tools(READ).getPriceChangeCandidates(null, 1)).isEqualTo("Direction must be rise or drop.");
    }

    @Test
    void emptyMissingErrorDisabledAndSeasonMismatchAreDescriptive() {
        assertThat(tools(READ).getPlayerPriceForecast("Hall")).contains("No official forecast capture");
        assertThat(tools(READ).getPriceChangeCandidates("rise", 1)).contains("No official forecast capture");
        assertThat(tools(READ).getRecentPriceChanges("Hall", 1)).contains("No official recent-change capture");
        publish(snapshot);
        assertThat(tools(READ, jdbc, "2027-28").getPlayerPriceForecast("Hall")).contains("season does not match");
        jdbc.execute("DROP TABLE " + TABLES.forecastPlayer());
        assertThat(tools(READ).getPlayerPriceForecast("Hall")).contains("temporarily unavailable").doesNotContain("£7.5m");
        store.revokeAndDisable();
        assertThat(tools(READ).getPlayerPriceForecast("Hall")).contains("permission is disabled");
        store.enablePublishing();
        permission.stop();
        assertThat(tools(READ).getRecentPriceChanges("Hall", 1)).contains("permission is disabled");
    }

    @Test
    void recentEventsUseOwnIdentityEvenAfterCurrentSeasonReusesId() {
        history(List.of(new Change(1, 2, 7, READ.minusSeconds(86400), 74, 75),
                new Change(1, 2, 8, READ.minusSeconds(100), 75, 74)));
        publish(new PricePredictionSnapshot("2027-28", READ.plusSeconds(10), READ, TARGET, snapshot.teams(),
                Map.of(1, player(1, "DifferentPlayer", 1, State.READY, 5, "110"))));
        String result = tools(READ.plusSeconds(3000)).getRecentPriceChanges("Muharemovic MUN", 1);
        assertThat(result).contains("season 2026-27", "Muharemović (Man Utd)", "market price £7.5m → £7.4m",
                "actual delta -0.1m", "changedAt UTC 2026-10-18T15:58:20Z", "Feed is stale", "Returned 1/2",
                "2026-10-17T16:00:00Z to 2026-10-18T15:58:20Z", "checkedAt UTC: 2026-10-18T16:00:01Z")
                .doesNotContain("DifferentPlayer", "predictor", "Very likely");
        assertThat(tools(READ.plusSeconds(2)).getRecentPriceChanges("DifferentPlayer", 10)).contains("No matching entry");
    }

    @Test
    void emptyHistoryIsAcceptedAndDoesNotProveNoChange() {
        history(List.of());
        assertThat(tools(READ.plusSeconds(2)).getRecentPriceChanges("Hall", 10))
                .contains("Observed event range UTC: empty", "absent entries do not prove no change", "No matching entry");
    }

    @Test
    void recentHistoryClampsAndSortsActualEvents() {
        List<Change> changes = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> new Change(1, 1, 1, READ.minusSeconds(index), 74, 75)).toList();
        history(changes);
        assertThat(tools(READ.plusSeconds(2)).getRecentPriceChanges("Muharemovic", 100))
                .contains("Returned 20/25", "truncated 5", "changedAt UTC 2026-10-18T15:59:59Z")
                .doesNotContain("changedAt UTC 2026-10-18T15:59:39Z");
        assertThat(tools(READ.plusSeconds(2)).getRecentPriceChanges("Muharemovic", -1)).contains("Returned 1/25");
    }

    @Test
    void forecastLookupRemainsInOldImmutableGenerationWhenPublicationIntervenes() {
        publish(snapshot);
        AtomicBoolean replaced = new AtomicBoolean();
        JdbcTemplate lookup = new JdbcTemplate(dataSource) {
            @Override
            public <Result> List<Result> query(String sql, RowMapper<Result> mapper, Object... arguments) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                if (replaced.compareAndSet(false, true)) {
                    publish(new PricePredictionSnapshot(snapshot.season(), READ.plusSeconds(1), READ, TARGET,
                            snapshot.teams(), Map.of(1, player(1, "Replacement", 1, State.READY, -5, "-110"))));
                }
                return super.query(sql, mapper, arguments);
            }
        };
        assertThat(tools(READ, lookup, "2026-27").getPlayerPriceForecast("Muharemovic"))
                .contains("Muharemović", "Very likely to rise").doesNotContain("Replacement");
        assertThat(tools(READ.plusSeconds(1)).getPlayerPriceForecast("Muharemovic")).contains("Player not found");
    }

    @Test
    void historyLookupRemainsCoherentWhenAnEmptyGenerationIsPublished() {
        history(List.of(new Change(1, 2, 1, READ.minusSeconds(1), 74, 75)));
        JdbcTemplate lookup = new JdbcTemplate(dataSource) {
            @Override
            public <Result> List<Result> query(String sql, RowMapper<Result> mapper, Object... arguments) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var lease = store.acquireLease(Duration.ofMinutes(1)).orElseThrow();
                store.publishRecentChanges(lease, "e".repeat(64),
                        new RecentChanges(snapshot.season(), READ.plusSeconds(2), READ, List.of()), snapshot, () -> true);
                store.release(lease);
                return super.query(sql, mapper, arguments);
            }
        };
        assertThat(tools(READ.plusSeconds(3), lookup, "2026-27").getRecentPriceChanges("Muharemovic", 1))
                .contains("Muharemović", "£7.4m → £7.5m", "Returned 1/1");
        assertThat(tools(READ.plusSeconds(3)).getRecentPriceChanges("Muharemovic", 1)).contains("range UTC: empty");
    }

    @Test
    void forecastCaptureBeforeDailyRunKeepsNewAndOldPricesSeparatelyAttributed() {
        jdbc.execute("DROP TABLE IF EXISTS fpl_price_routing_test");
        jdbc.execute("""
                CREATE TABLE fpl_price_routing_test (
                    season text, price_date date, element_id integer, web_name text, full_name text,
                    team_name text, team_short text, element_type integer, now_cost integer,
                    cost_change_event integer, cost_change_start integer, selected_by_percent numeric,
                    status text, news text)
                """);
        try {
            jdbc.update("""
                    INSERT INTO fpl_price_routing_test VALUES
                    ('2026-27', '2026-10-17', 1, 'Muharemović', 'Test Muharemović',
                     'Newcastle', 'NEW', 3, 74, 0, 0, 1, 'a', '')
                    """);
            Instant beforeDaily = Instant.parse("2026-10-18T00:30:00Z");
            publish(new PricePredictionSnapshot(snapshot.season(), beforeDaily, beforeDaily,
                    TARGET, snapshot.teams(), snapshot.players()));
            var daily = new PriceTools(jdbc, new DailyPriceSource(jdbc, "fpl_price_routing_test", new PlayerNameSearch(jdbc)));
            assertThat(tools(beforeDaily).getPlayerPriceForecast("Muharemovic"))
                    .contains("£7.5m", "2026-10-18T00:30:00Z").doesNotContain("£7.4m");
            assertThat(daily.getPriceHistory("Muharemovic", 10)).contains("2026-10-17: £7.4m").doesNotContain("£7.5m");
            assertThat(jdbc.queryForObject("SELECT now_cost FROM fpl_price_routing_test", Integer.class)).isEqualTo(74);
        } finally {
            jdbc.execute("DROP TABLE fpl_price_routing_test");
        }
    }

    @Test
    void durableRevokeDuringLookupIsRecheckedOutsideSnapshotTransaction() {
        publish(snapshot);
        JdbcTemplate lookup = new JdbcTemplate(dataSource) {
            @Override
            public <Result> List<Result> query(String sql, RowMapper<Result> mapper, Object... arguments) {
                store.revokeAndDisable();
                return super.query(sql, mapper, arguments);
            }
        };
        assertThat(tools(READ, lookup, "2026-27").getPlayerPriceForecast("Hall"))
                .contains("permission is disabled").doesNotContain("£7.5m", "Likely to rise");
    }

    @Test
    void runtimeStopDuringHistoryLookupSuppressesTheReadEvidence() {
        history(List.of(new Change(1, 2, 1, READ.minusSeconds(1), 74, 75)));
        JdbcTemplate lookup = new JdbcTemplate(dataSource) {
            @Override
            public <Result> List<Result> query(String sql, RowMapper<Result> mapper, Object... arguments) {
                permission.stop();
                return super.query(sql, mapper, arguments);
            }
        };
        assertThat(tools(READ.plusSeconds(2), lookup, "2026-27").getRecentPriceChanges("Muharemovic", 1))
                .contains("permission is disabled").doesNotContain("£7.5m");
    }
}
