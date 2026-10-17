package fi.karivanttinen.fplassistant.shortlist;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import fi.karivanttinen.fplassistant.price.DailyPriceFreshnessCheck;
import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.tools.ClubRates;
import fi.karivanttinen.fplassistant.tools.DailyPriceSource;
import fi.karivanttinen.fplassistant.tools.DataAge;
import fi.karivanttinen.fplassistant.tools.ExpectedPoints;
import fi.karivanttinen.fplassistant.tools.PfsReadSource;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import fi.karivanttinen.fplassistant.tools.UkTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only comparison; membership and its catalogue identity remain independent. */
@Service
public class ShortlistComparison {
    public record Response(Instant asOf, String horizon, List<String> sources, List<Row> rows,
                           Instant scheduleCapturedAt, List<String> modelSeasons,
                           Map<String, Instant> modelThrough) {}
    public record Market(String webName, String fullName, String teamShort, int position, int nowCost,
                         String status, String news, Double ownership, Integer penalties,
                         Integer directFreeKicks, Integer corners, Instant capturedAt,
                         Integer chanceOfPlayingNextRound) {}
    public record Totals(Integer points, Integer minutes, Integer goals, Integer assists,
                         Integer cleanSheets, Integer saves, Integer bonus, Double xg, Double xa,
                         Double xgi, String source) {}
    public record Fixture(int id, String opponent, boolean home, Integer difficulty,
                          Double cleanSheetProbability) {}
    public record Week(int gameweek, List<Fixture> fixtures) {}
    public record Forecast(Double expectedCleanSheets, String reason, List<Week> weeks) {}
    public record Match(int id, Integer gameweek, String opponent, boolean home, Instant kickoff,
                        Integer minutes, Integer points, Integer goals, Integer assists,
                        Double xg, Double xa, Double xgi, Integer defcon, String evidence) {}
    public record Defcon(int threshold, int hits, int readings, Double actionsPer90,
                         int observedAppearances) {}
    public record Row(String season, int elementId, Market market, Totals totals,
                      Double pointsPerMillion, String reason, Forecast forecast,
                      List<Match> history, String historyReason, Defcon defcon,
                      List<String> observationSources) {}

    private final ShortlistStore shortlist;
    private final DailyPriceSource prices;
    private final SeasonResolver seasons;
    private final RankingMetrics ranking;
    private final JdbcTemplate jdbc;
    private final PfsReadSource pfs;
    private final String dailyTable;
    private final Clock clock;

    public ShortlistComparison(ShortlistStore shortlist, DailyPriceSource prices,
                               SeasonResolver seasons, RankingMetrics ranking, JdbcTemplate jdbc,
                               PfsReadSource pfs, @Value("${fpl.daily-price.table-name}") String dailyTable,
                               ObjectProvider<Clock> clocks) {
        this.shortlist = shortlist;
        this.prices = prices;
        this.seasons = seasons;
        this.ranking = ranking;
        this.jdbc = jdbc;
        this.pfs = pfs;
        this.dailyTable = dailyTable;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response read(UUID owner) {
        List<ShortlistStore.Player> saved = shortlist.list(owner);
        if (saved.isEmpty()) return new Response(clock.instant(), "", List.of(), List.of(), null, List.of(), Map.of());
        var capture = prices.latestCapture();
        if (capture.isEmpty()) return new Response(clock.instant(), "Schedule unavailable", List.of(),
                saved.stream().map(p -> unavailable(p, "No daily capture available.")).toList(), null, List.of(), Map.of());
        var metric = seasons.metricSeasons(seasons.resolve());
        var snapshot = ranking.loadCompletedComparison(capture.get(), metric);
        Instant asOf = snapshot.asOf();
        var horizon = HorizonFixtures.derive(snapshot.schedule(), asOf, capture.get().season(), 5);
        var candidates = snapshot.candidates().stream().collect(Collectors.toMap(
                c -> c.capture().elementId(), c -> c.capture()));
        Map<Integer, String> clubs = new HashMap<>();
        candidates.values().forEach(c -> { if (c.teamId() != null) clubs.put(c.teamId(), c.teamShort()); });
        boolean fresh = DailyPriceFreshnessCheck.isFresh(capture.get().date(), asOf)
                && !candidates.isEmpty()
                && candidates.values().stream().allMatch(c -> c.capturedAt() != null
                    && !c.capturedAt().isAfter(asOf)
                    && LocalDate.ofInstant(c.capturedAt(), UkTime.zone()).equals(capture.get().date()))
                && candidates.values().stream().map(RankingMetrics.CapturePlayer::capturedAt).distinct().count() == 1;
        boolean sameSeason = metric.played().currentSeason()
                && metric.played().code().equals(capture.get().season());
        String ids = saved.stream().filter(p -> p.season().equals(capture.get().season()))
                .map(p -> Integer.toString(p.elementId())).distinct().collect(Collectors.joining(","));
        Map<Integer, Map<String, Object>> totals = new HashMap<>();
        Map<Integer, Map<String, Object>> duties = new HashMap<>();
        Map<Integer, List<Map<String, Object>>> observations = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.queryForList("SELECT element_id, penalties_order, direct_fk_order, corners_order, chance_of_playing_next_round FROM "
                    + dailyTable + " WHERE season=? AND price_date=? AND element_id IN (" + ids + ")",
                    capture.get().season(), capture.get().date())
                    .forEach(r -> duties.put(integer(r, "element_id"), r));
            if (sameSeason) {
                jdbc.queryForList("SELECT * FROM player WHERE season_id=? AND id IN (" + ids + ")",
                        metric.played().seasonId()).forEach(r -> totals.put(integer(r, "id"), r));
                jdbc.queryForList("WITH " + pfs.cte() + " SELECT * FROM pfs_read WHERE season_id=?"
                                + " AND player_id IN (" + ids + ")", metric.played().seasonId())
                        .forEach(r -> observations.computeIfAbsent(integer(r, "player_id"), k -> new ArrayList<>()).add(r));
            }
        }
        List<String> sources = new ArrayList<>();
        sources.add("Daily capture: season " + capture.get().season() + ", " + capture.get().date()
                + (fresh ? "." : "; unavailable for current prices and availability."));
        sources.add("Observed totals: season " + metric.played().code() + ". Points/£m = season points / current price in millions; not a forecast.");
        snapshot.schedule().snapshot().ifPresent(s -> sources.add(
                ScheduleSource.provenance(s.season(), s.capturedAt(), asOf)));
        sources.add("Team clean-sheet model: " + metric.played().code()
                + metric.previous().map(s -> " and " + s.code()).orElse("")
                + "; completed matches with usable evidence only; sum of per-fixture probabilities, not player points or a playing-time forecast. Not calibrated.");
        sources.add("Model evidence: " + snapshot.metricEvidence());
        String range = horizon.status() == HorizonFixtures.Status.KNOWN
                ? "GW" + horizon.startGameweek() + "–" + horizon.endGameweek()
                    + (horizon.truncated() ? " (truncated at season end)" : " · 5 editable gameweeks")
                : "Schedule: " + horizon.reason();
        List<Row> rows = saved.stream().map(p -> row(p, snapshot, horizon, candidates.get(p.elementId()),
                totals.get(p.elementId()), duties.getOrDefault(p.elementId(), Map.of()),
                observations.getOrDefault(p.elementId(), List.of()), clubs, fresh, sameSeason)).toList();
        List<String> modelSeasons = new ArrayList<>();
        modelSeasons.add(metric.played().code());
        metric.previous().ifPresent(previous -> modelSeasons.add(previous.code()));
        return new Response(asOf, range, sources, rows,
                snapshot.schedule().snapshot().map(ScheduleSource.Snapshot::capturedAt).orElse(null),
                List.copyOf(modelSeasons), snapshot.clubSampleThrough());
    }

    private Row row(ShortlistStore.Player saved, RankingMetrics.Snapshot snapshot,
                    HorizonFixtures.Result horizon, RankingMetrics.CapturePlayer player,
                    Map<String, Object> total, Map<String, Object> duties,
                    List<Map<String, Object>> observations, Map<Integer, String> clubs,
                    boolean fresh, boolean sameSeason) {
        if (!saved.season().equals(snapshot.capture().season()))
            return unavailable(saved, "Historical saved entry; no current-season comparison.");
        if (player == null || !player.hasValidIdentity())
            return unavailable(saved, "Player identity unavailable in the selected daily capture.");
        Market market = fresh ? new Market(player.webName(), player.fullName(), player.teamShort(), player.position(),
                player.nowCost(), player.status(), player.news(), player.ownership(),
                integer(duties, "penalties_order"), integer(duties, "direct_fk_order"),
                integer(duties, "corners_order"), player.capturedAt(),
                integer(duties, "chance_of_playing_next_round")) : null;
        boolean coherent = sameSeason && total != null
                && player.teamId().equals(integer(total, "team_id"))
                && player.position().equals(integer(total, "element_type"));
        Totals stats = coherent ? totals(total, snapshot.asOf()) : null;
        String reason = !fresh ? "Current daily capture unavailable."
                : !coherent ? "Matching playing-season statistics unavailable." : null;
        var forecast = fresh ? forecast(snapshot, horizon, player.teamId(), clubs, sameSeason)
                : new Forecast(null, "Current daily identity unavailable.", List.of());
        if (!coherent) return new Row(saved.season(), saved.elementId(), market, stats, null, reason,
                forecast, List.of(), "Matching season/club observations unavailable.", null, List.of());
        var view = snapshot.schedule();
        boolean validHistory = view.completeness().complete() && view.snapshot().isPresent()
                && view.snapshot().get().season().equals(saved.season());
        List<ScheduleSource.FixtureRow> completed = view.fixtures().stream()
                .filter(f -> f.finished() && (f.homeTeamId() == player.teamId() || f.awayTeamId() == player.teamId()))
                .toList();
        validHistory &= completed.stream().allMatch(f -> f.kickoff() != null && !f.kickoff().isAfter(snapshot.asOf()));
        Map<Integer, ScheduleSource.FixtureRow> canonical = view.fixtures().stream().collect(
                Collectors.toMap(ScheduleSource.FixtureRow::id, f -> f, (a, b) -> a));
        List<Map<String, Object>> valid = observations.stream().filter(r -> {
            var fixture = canonical.get(integer(r, "fixture_id"));
            if (fixture == null || !fixture.finished() || fixture.kickoff() == null
                    || fixture.kickoff().isAfter(snapshot.asOf()) || r.get("was_home") == null) return false;
            int opponent = Boolean.TRUE.equals(r.get("was_home")) ? fixture.awayTeamId() : fixture.homeTeamId();
            return Integer.valueOf(opponent).equals(integer(r, "opponent_team_id"));
        }).toList();
        Map<Integer, Map<String, Object>> byFixture = valid.stream().collect(Collectors.toMap(
                r -> integer(r, "fixture_id"), r -> r, (a, b) -> a));
        List<Match> history = validHistory ? completed.stream()
                .sorted(Comparator.comparing(ScheduleSource.FixtureRow::kickoff).reversed()
                        .thenComparing(Comparator.comparingInt(ScheduleSource.FixtureRow::id).reversed()))
                .limit(5).map(f -> match(f, player.teamId(), byFixture.get(f.id()), clubs)).toList() : List.of();
        List<String> evidence = List.of(
                DataAge.matchCoverage("season " + saved.season() + " observed records",
                        DataAge.Span.of(valid.stream().map(r -> r.get("kickoff_time")).toList()), snapshot.asOf()),
                DataAge.metricRefresh("season " + saved.season() + " observed records",
                        DataAge.Span.of(valid.stream().map(r -> r.get("source_read_finished_at")).toList()),
                        DataAge.Span.of(valid.stream().map(r -> r.get("verified_published_at")).toList()), valid.size()));
        return new Row(saved.season(), saved.elementId(), market, stats,
                fresh ? value(stats.points(), player.nowCost()) : null, reason, forecast, history,
                validHistory ? null : "Completed-fixture chronology unavailable.",
                validHistory ? defcon(valid, player.position()) : null, evidence);
    }

    static Forecast forecast(RankingMetrics.Snapshot snapshot, HorizonFixtures.Result horizon,
                             int club, Map<Integer, String> clubs, boolean sameSeason) {
        var window = horizon.club(club);
        if (!window.known()) return new Forecast(null, window.unknownReason().name(), List.of());
        boolean covered = sameSeason && snapshot.comparisonCoverage().assessed();
        List<Week> weeks = new ArrayList<>();
        double sum = 0;
        boolean complete = true;
        for (int gw = horizon.startGameweek(); gw <= horizon.endGameweek(); gw++) {
            List<Fixture> fixtures = new ArrayList<>();
            for (var f : window.fixtures()) {
                if (f.gameweek() != gw) continue;
                var projection = covered ? ClubRates.projectedGoalsAgainst(snapshot.clubRates(), club,
                        f.opponentTeamId(), f.home()) : java.util.OptionalDouble.empty();
                Double probability = projection.isPresent() && Double.isFinite(projection.getAsDouble())
                        && projection.getAsDouble() >= 0 ? Math.exp(-projection.getAsDouble()) : null;
                if (probability == null) complete = false; else sum += probability;
                fixtures.add(new Fixture(f.id(), clubName(clubs, f.opponentTeamId()), f.home(),
                        f.hasValidDifficulty() ? f.difficulty() : null, probability));
            }
            weeks.add(new Week(gw, List.copyOf(fixtures)));
        }
        return new Forecast(complete ? sum : null,
                complete ? null : "Team projection unavailable: insufficient verified completed-match evidence or missing club rates.", weeks);
    }

    private static Match match(ScheduleSource.FixtureRow fixture, int club,
                               Map<String, Object> observation, Map<Integer, String> clubs) {
        boolean home = fixture.homeTeamId() == club;
        Map<String, Object> r = observation == null || !Boolean.valueOf(home).equals(observation.get("was_home"))
                ? Map.of() : observation;
        return new Match(fixture.id(), fixture.gameweek(), clubName(clubs, home ? fixture.awayTeamId() : fixture.homeTeamId()),
                home, fixture.kickoff(), integer(r, "minutes"), integer(r, "total_points"),
                integer(r, "goals_scored"), integer(r, "assists"), number(r, "expected_goals"),
                number(r, "expected_assists"), sum(number(r, "expected_goals"), number(r, "expected_assists")),
                integer(r, "defensive_contribution"), r.isEmpty() ? "MISSING" : (String) r.get("evidence_status"));
    }

    static Defcon defcon(List<Map<String, Object>> rows, int position) {
        int threshold = ExpectedPoints.defconThreshold(position);
        int appearances = 0, readings = 0, hits = 0, minutes = 0, actions = 0;
        for (var r : rows) {
            Integer mins = integer(r, "minutes"), dc = integer(r, "defensive_contribution");
            if (mins == null || mins <= 0 || mins > 120) continue;
            appearances++;
            if (dc == null || dc < 0 || threshold == 0) continue;
            readings++; minutes += mins; actions += dc;
            if (dc >= threshold) hits++;
        }
        return new Defcon(threshold, hits, readings, minutes == 0 ? null : actions * 90.0 / minutes, appearances);
    }

    private static Totals totals(Map<String, Object> r, Instant asOf) {
        return new Totals(integer(r, "total_points"), integer(r, "minutes"), integer(r, "goals_scored"),
                integer(r, "assists"), integer(r, "clean_sheets"), integer(r, "saves"), integer(r, "bonus"),
                number(r, "expected_goals"), number(r, "expected_assists"),
                sum(number(r, "expected_goals"), number(r, "expected_assists")),
                DataAge.storedDataAge("season totals", r.get("updated_at"), asOf));
    }

    static Double value(Integer points, int cost) { return points == null || cost <= 0 ? null : points * 10.0 / cost; }
    static Double sum(Double a, Double b) { return a == null || b == null ? null : a + b; }
    private static Integer integer(Map<String, Object> r, String key) {
        return r.get(key) instanceof Number n ? n.intValue() : null;
    }
    private static Double number(Map<String, Object> r, String key) {
        return r.get(key) instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }
    private static String clubName(Map<Integer, String> clubs, int id) {
        String name = clubs.get(id);
        return name == null ? "Team " + id : name;
    }
    private static Row unavailable(ShortlistStore.Player p, String reason) {
        return new Row(p.season(), p.elementId(), null, null, null, reason,
                new Forecast(null, reason, List.of()), List.of(), reason, null, List.of());
    }
}
