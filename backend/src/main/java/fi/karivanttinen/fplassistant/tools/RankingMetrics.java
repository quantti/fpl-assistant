package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import fi.karivanttinen.fplassistant.price.DailyPriceFreshnessCheck;
import fi.karivanttinen.fplassistant.schedule.ScheduleFreshnessCheck;
import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One transactionally coherent read for every ranking consumer. Identity is anchored to the
 * complete daily capture, while minutes membership is reconstructed from season-scoped canonical
 * fixture sides rather than the unreliable {@code player_team_id} stamped on PFS history.
 */
@Component
public class RankingMetrics {

    public enum WindowReason {
        COMPLETE,
        PRICE_CAPTURE_STALE,
        SOURCE_SEASON_MISMATCH,
        SCHEDULE_CAPTURE_MISSING,
        SCHEDULE_CAPTURE_STALE,
        SCHEDULE_INCOMPLETE,
        INVALID_CAPTURE_IDENTITY,
        CLUB_NOT_IN_SCHEDULE,
        UNMAPPABLE_HISTORY,
        CONTRADICTORY_OPPONENT,
        LATEST_CLUB_MISMATCH,
        RESULT_PENDING,
        FINISHED_WITHOUT_CHRONOLOGY,
        MULTIPLE_CLUBS,
        NO_VERIFIED_CLUB,
        MISSING_OBSERVATION,
        INVALID_MINUTES
    }

    public record FixtureOutcome(int fixtureId, Instant kickoff, Integer minutes) {
    }

    public record MinutesWindow(boolean complete, WindowReason reason, Integer clubId,
                                Instant firstKickoff, Instant lastKickoff,
                                int expected, int observed, int totalMinutes,
                                int appearances, int sixtyPlus,
                                List<FixtureOutcome> latestThree) {
        public MinutesWindow {
            Objects.requireNonNull(reason);
            latestThree = List.copyOf(latestThree);
            if (complete != (reason == WindowReason.COMPLETE)) {
                throw new IllegalArgumentException("window completeness and reason disagree");
            }
        }
    }

    public record CapturePlayer(int elementId, String webName, String fullName,
                                Integer teamId, String teamShort, String teamName,
                                Integer position, int nowCost, Double ownership,
                                String status, String news, Instant capturedAt,
                                int captureNameCount) {
        public boolean hasValidIdentity() {
            return teamId != null && teamId > 0 && position != null
                    && position >= 1 && position <= 4;
        }
    }

    /** Per-season ability, exposure and the exact fixture population that supplied them. */
    public record SeasonMetrics(SeasonContext season, Integer playerId, Integer position,
                                Map<String, Object> ability, MinutesWindow window,
                                SeasonBlend.Exposure exposure) {
        public SeasonMetrics {
            ability = Map.copyOf(ability);
        }
    }

    /** One row for every element id in the capture, before any consumer-specific filtering. */
    public record Candidate(CapturePlayer capture, SeasonMetrics current,
                            SeasonMetrics previous, SeasonBlend.Blended blended,
                            MinutesModel.Eligibility eligibility) {
    }

    public record Snapshot(Instant asOf, DailyPriceSource.Capture capture,
                           MetricSeasons metricSeasons, ScheduleSource.View schedule,
                           List<Candidate> candidates, ClubRates.Rates clubRates,
                           String metricRefresh, ComparisonCoverage comparisonCoverage) {
        public Snapshot {
            candidates = List.copyOf(candidates);
            clubRates = Objects.requireNonNull(clubRates);
            metricRefresh = Objects.requireNonNull(metricRefresh);
            comparisonCoverage = Objects.requireNonNull(comparisonCoverage);
        }

        public Snapshot(Instant asOf, DailyPriceSource.Capture capture,
                        MetricSeasons metricSeasons, ScheduleSource.View schedule,
                        List<Candidate> candidates, ClubRates.Rates clubRates) {
            this(asOf, capture, metricSeasons, schedule, candidates, clubRates,
                    DataAge.METRIC_REFRESH, ComparisonCoverage.assemble(candidates, List.of()));
        }

        public Snapshot(Instant asOf, DailyPriceSource.Capture capture,
                        MetricSeasons metricSeasons, ScheduleSource.View schedule,
                        List<Candidate> candidates, ClubRates.Rates clubRates,
                        String metricRefresh) {
            this(asOf, capture, metricSeasons, schedule, candidates, clubRates, metricRefresh,
                    ComparisonCoverage.assemble(candidates, List.of()));
        }
    }

    private record SeasonPlayer(int id, String webName, int position) {
    }

    private record CanonicalSource(String table, String seasonColumn, Object seasonValue) {
    }

    private record CanonicalFixture(int id, Integer gameweek, int home, int away,
                                    Instant kickoff, boolean started, boolean finished,
                                    Integer homeDifficulty, Integer awayDifficulty) {
        boolean involves(int club) {
            return home == club || away == club;
        }
    }

    private record ClubObservation(int club, Instant latestKickoff, int latestFixtureId) {
    }

    private static final class Aggregate {
        int expected;
        int observed;
        int invalidMinutes;
        int usableRows;
        int totalMinutes;
        int appearances;
        int sixtyPlus;
        double points;
        double xg;
        int xgCount;
        double xa;
        int xaCount;
        double xgc;
        int xgcCount;
        double shortDefcon;
        int shortDefconCount;
        double longDefcon;
        int longDefconCount;

        void add(Aggregate other) {
            expected += other.expected;
            observed += other.observed;
            invalidMinutes += other.invalidMinutes;
            usableRows += other.usableRows;
            totalMinutes += other.totalMinutes;
            appearances += other.appearances;
            sixtyPlus += other.sixtyPlus;
            points += other.points;
            xg += other.xg;
            xgCount += other.xgCount;
            xa += other.xa;
            xaCount += other.xaCount;
            xgc += other.xgc;
            xgcCount += other.xgcCount;
            shortDefcon += other.shortDefcon;
            shortDefconCount += other.shortDefconCount;
            longDefcon += other.longDefcon;
            longDefconCount += other.longDefconCount;
        }
    }

    private static final class History {
        int invalidMappings;
        int contradictions;
        int wholeSeasonSixty;
        final Aggregate ability = new Aggregate();
        final List<ClubObservation> clubs = new ArrayList<>();

        ClubObservation latestClub() {
            return clubs.stream().max(CHRONOLOGY).orElse(null);
        }

        Instant latestDifferentClub(int currentClub) {
            return clubs.stream().filter(club -> club.club() != currentClub)
                    .max(CHRONOLOGY).map(ClubObservation::latestKickoff).orElse(null);
        }
    }

    private record Target(int playerId, CanonicalFixture fixture) {
    }

    private record SideTarget(int clubId, CanonicalFixture fixture) {
    }

    private record CurrentBuild(Map<Integer, SeasonMetrics> metrics,
                                List<ComparisonCoverage.ClubGap> gaps,
                                boolean comparisonAssessed) {
    }

    private static final Comparator<ClubObservation> CHRONOLOGY = Comparator
            .comparing(ClubObservation::latestKickoff)
            .thenComparingInt(ClubObservation::latestFixtureId);
    private static final Comparator<CanonicalFixture> NEWEST_FIXTURE = Comparator
            .comparing(CanonicalFixture::kickoff, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(CanonicalFixture::id).reversed();

    private final JdbcTemplate jdbc;
    private final ScheduleSource schedules;
    private final String dailyTable;
    private final Clock clock;
    private final PfsReadSource pfs;
    private final TransactionTemplate readTransaction;

    public RankingMetrics(JdbcTemplate jdbc, ScheduleSource schedules,
                          PlatformTransactionManager transactionManager,
                          ObjectProvider<Clock> clock,
                          @Value("${fpl.daily-price.table-name}") String dailyTable,
                          PfsReadSource pfs) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.dailyTable = dailyTable;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.pfs = pfs;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Reads one immutable snapshot under one database snapshot and one chosen clock instant. */
    public Snapshot load(DailyPriceSource.Capture capture, MetricSeasons metricSeasons) {
        Objects.requireNonNull(capture);
        Objects.requireNonNull(metricSeasons);
        return readTransaction.execute(status -> loadInside(capture, metricSeasons, clock.instant()));
    }

    private Snapshot loadInside(DailyPriceSource.Capture capture, MetricSeasons metric, Instant asOf) {
        ScheduleSource.View schedule = schedules.readView();
        List<CapturePlayer> capturePlayers = loadCapture(capture);
        Map<String, List<SeasonPlayer>> currentPopulation = loadPlayerPopulation(metric.played().seasonId());
        Map<String, List<SeasonPlayer>> previousPopulation = metric.previous().isPresent()
                ? loadPlayerPopulation(metric.previousSeasonId()) : Map.of();

        Map<Integer, Integer> currentIds = new HashMap<>();
        Map<Integer, Integer> previousIds = new HashMap<>();
        Map<Integer, Integer> currentPositions = new HashMap<>();
        Map<Integer, Integer> previousPositions = new HashMap<>();
        boolean sameSeason = capture.season().equals(metric.played().code());
        for (CapturePlayer player : capturePlayers) {
            if (sameSeason) {
                currentIds.put(player.elementId(), player.elementId());
                if (player.position() != null) {
                    currentPositions.put(player.elementId(), player.position());
                }
            } else {
                SeasonPlayer bridged = uniqueBridge(player, currentPopulation);
                if (bridged != null) {
                    currentIds.put(player.elementId(), bridged.id());
                    currentPositions.put(player.elementId(), bridged.position());
                }
            }
            SeasonPlayer prior = uniqueBridge(player, previousPopulation);
            if (prior != null) {
                previousIds.put(player.elementId(), prior.id());
                previousPositions.put(player.elementId(), prior.position());
            }
        }

        Map<Integer, SeasonMetrics> currentMetrics;
        List<ComparisonCoverage.ClubGap> coverageGaps = List.of();
        boolean comparisonAssessed = false;
        if (metric.played().currentSeason()) {
            CurrentBuild currentBuild = buildCurrentMetrics(capture, metric.played(), schedule,
                    capturePlayers, currentIds, currentPositions, asOf);
            currentMetrics = currentBuild.metrics();
            coverageGaps = currentBuild.gaps();
            comparisonAssessed = currentBuild.comparisonAssessed();
        } else {
            CanonicalSource source = new CanonicalSource("fixture", "season_id",
                    metric.played().seasonId());
            List<CanonicalFixture> fixtures = loadCanonicalFixtures(source);
            currentMetrics = buildHistoricalMetrics(metric.played(), source, fixtures,
                    new HashSet<>(currentIds.values()), currentPositionsByPlayer(
                            capturePlayers, currentIds, currentPositions));
        }

        Map<Integer, SeasonMetrics> previousMetrics = Map.of();
        if (metric.previous().isPresent()) {
            SeasonContext previous = metric.previous().orElseThrow();
            CanonicalSource source = new CanonicalSource("fixture", "season_id", previous.seasonId());
            previousMetrics = buildHistoricalMetrics(previous, source, loadCanonicalFixtures(source),
                    new HashSet<>(previousIds.values()), currentPositionsByPlayer(
                            capturePlayers, previousIds, previousPositions));
        }

        // Club sides for the same seasons the player metrics came from, keyed by capture team id.
        Map<Integer, ClubRates.SeasonSide> rawCurrentSides;
        Map<Integer, ClubRates.SeasonSide> currentSides;
        if (metric.played().currentSeason()) {
            rawCurrentSides = currentSourceFailure(capture, metric.played(), schedule, asOf) != null
                    ? Map.of()
                    : loadClubSides(new CanonicalSource(schedules.fixtureTable(), "season",
                            schedule.snapshot().orElseThrow().season()),
                            metric.played().seasonId(), asOf);
            currentSides = rawCurrentSides;
        } else {
            rawCurrentSides = loadClubSides(new CanonicalSource("fixture", "season_id",
                    metric.played().seasonId()), metric.played().seasonId(), null);
            currentSides = bridgeClubs(rawCurrentSides, metric.played().seasonId(), capturePlayers);
        }
        Map<Integer, ClubRates.SeasonSide> rawPreviousSides = Map.of();
        Map<Integer, ClubRates.SeasonSide> previousSides = Map.of();
        if (metric.previous().isPresent()) {
            SeasonContext previous = metric.previous().orElseThrow();
            rawPreviousSides = loadClubSides(new CanonicalSource("fixture", "season_id",
                    previous.seasonId()), previous.seasonId(), null);
            previousSides = bridgeClubs(rawPreviousSides, previous.seasonId(), capturePlayers);
        }
        // The league constants pool every club of each season, from the raw sides rather than the
        // bridged ones: the mean is not a club's property, so a relegated club that bridges to no
        // capture team still shapes it, and the shipped read matches the offline measurement.
        Optional<ClubRates.League> league = ClubRates.league(rawCurrentSides, rawPreviousSides);
        ClubRates.Rates clubRates = ClubRates.blend(currentSides, previousSides, league);

        List<Candidate> candidates = new ArrayList<>(capturePlayers.size());
        for (CapturePlayer player : capturePlayers) {
            Integer currentId = currentIds.get(player.elementId());
            Integer previousId = previousIds.get(player.elementId());
            SeasonMetrics current = currentId == null ? null : currentMetrics.get(currentId);
            SeasonMetrics previous = previousId == null ? null : previousMetrics.get(previousId);
            Map<String, Object> currentAbility = current == null || current.ability().isEmpty()
                    ? null : current.ability();
            Map<String, Object> previousAbility = previous == null || previous.ability().isEmpty()
                    ? null : previous.ability();
            if (previousAbility != null
                    && !sameDefconDefinition(player.position(), previous.position())) {
                previousAbility = withoutDefcon(previousAbility);
            }
            SeasonBlend.Blended blended = SeasonBlend.blend(
                    currentAbility, previousAbility,
                    current == null ? null : current.exposure(),
                    previous == null ? null : previous.exposure(),
                    metric.played().currentSeason());
            candidates.add(new Candidate(player, current, previous, blended,
                    MinutesModel.eligibility(player,
                            current == null ? null : current.window(),
                            metric.played().currentSeason())));
        }
        ComparisonCoverage comparisonCoverage = ComparisonCoverage.assemble(
                candidates, coverageGaps, comparisonAssessed);
        return new Snapshot(asOf, capture, metric, schedule, candidates, clubRates,
                DataAge.RANKING_METRIC_REFRESH, comparisonCoverage);
    }

    private List<CapturePlayer> loadCapture(DailyPriceSource.Capture capture) {
        return jdbc.query("""
                SELECT element_id, web_name, full_name, team_id, team_short, team_name,
                       element_type, now_cost, selected_by_percent, status, news, captured_at,
                       count(*) OVER (PARTITION BY LOWER(web_name)) AS name_count
                FROM %s WHERE season = ? AND price_date = ? ORDER BY element_id
                """.formatted(dailyTable), (rs, rowNum) -> new CapturePlayer(
                        rs.getInt("element_id"), rs.getString("web_name"),
                        rs.getString("full_name"), nullableInteger(rs.getObject("team_id")),
                        rs.getString("team_short"), rs.getString("team_name"),
                        nullableInteger(rs.getObject("element_type")), rs.getInt("now_cost"),
                        nullableDouble(rs.getObject("selected_by_percent")),
                        rs.getString("status"), rs.getString("news"),
                        instant(rs.getObject("captured_at")), rs.getInt("name_count")),
                capture.season(), capture.date());
    }

    private Map<String, List<SeasonPlayer>> loadPlayerPopulation(int seasonId) {
        if (seasonId < 0) {
            return Map.of();
        }
        Map<String, List<SeasonPlayer>> byName = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, web_name, element_type FROM player WHERE season_id = ?", seasonId)) {
            byName.computeIfAbsent(key(String.valueOf(row.get("web_name"))),
                    ignored -> new ArrayList<>()).add(new SeasonPlayer(
                            integer(row.get("id")), String.valueOf(row.get("web_name")),
                            integer(row.get("element_type"))));
        }
        return byName;
    }

    private static SeasonPlayer uniqueBridge(CapturePlayer player,
                                               Map<String, List<SeasonPlayer>> population) {
        if (player.captureNameCount() != 1) {
            return null;
        }
        List<SeasonPlayer> matches = population.getOrDefault(key(player.webName()), List.of());
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private CurrentBuild buildCurrentMetrics(
            DailyPriceSource.Capture capture, SeasonContext season, ScheduleSource.View schedule,
            List<CapturePlayer> players, Map<Integer, Integer> ids,
            Map<Integer, Integer> positions, Instant asOf) {
        WindowReason sourceFailure = currentSourceFailure(capture, season, schedule, asOf);
        if (sourceFailure != null) {
            Map<Integer, SeasonMetrics> unavailable = new HashMap<>();
            for (CapturePlayer player : players) {
                Integer id = ids.get(player.elementId());
                if (id != null) {
                    unavailable.put(id, new SeasonMetrics(season, id, positions.get(player.elementId()),
                            Map.of(), emptyWindow(sourceFailure, player.teamId()), null));
                }
            }
            return new CurrentBuild(unavailable, List.of(), false);
        }

        ScheduleSource.Snapshot selected = schedule.snapshot().orElseThrow();
        CanonicalSource source = new CanonicalSource(schedules.fixtureTable(), "season",
                selected.season());
        List<CanonicalFixture> fixtures = schedule.fixtures().stream()
                .map(row -> new CanonicalFixture(row.id(), row.gameweek(), row.homeTeamId(),
                        row.awayTeamId(), row.kickoff(), row.started(), row.finished(),
                        row.homeDifficulty(), row.awayDifficulty()))
                .toList();
        Set<Integer> playerIds = new HashSet<>(ids.values());
        Map<Integer, History> history = loadHistory(source, season.seasonId(), playerIds, asOf);
        Map<Integer, Map<Integer, Instant>> dailyMembership = loadDailyMembership(
                capture.season(), playerIds, asOf);
        Set<Integer> scheduleClubs = new HashSet<>();
        fixtures.forEach(fixture -> {
            scheduleClubs.add(fixture.home());
            scheduleClubs.add(fixture.away());
        });

        Map<Integer, List<CanonicalFixture>> expected = new HashMap<>();
        Map<Integer, WindowReason> baseReasons = new HashMap<>();
        Map<Integer, Integer> clubs = new HashMap<>();
        List<ComparisonCoverage.ClubGap> coverageGaps = new ArrayList<>();
        for (CapturePlayer player : players) {
            Integer playerId = ids.get(player.elementId());
            if (playerId == null) {
                continue;
            }
            Integer club = player.teamId();
            clubs.put(playerId, club);
            if (!player.hasValidIdentity() || player.capturedAt().isAfter(asOf)) {
                baseReasons.put(playerId, WindowReason.INVALID_CAPTURE_IDENTITY);
                expected.put(playerId, List.of());
                continue;
            }
            if (!scheduleClubs.contains(club)) {
                baseReasons.put(playerId, WindowReason.CLUB_NOT_IN_SCHEDULE);
                expected.put(playerId, List.of());
                continue;
            }

            History record = history.getOrDefault(playerId, new History());
            if (record.invalidMappings > 0) {
                baseReasons.put(playerId, WindowReason.UNMAPPABLE_HISTORY);
            } else if (record.contradictions > 0) {
                baseReasons.put(playerId, WindowReason.CONTRADICTORY_OPPONENT);
            } else if (record.latestClub() != null && record.latestClub().club() != club) {
                baseReasons.put(playerId, WindowReason.LATEST_CLUB_MISMATCH);
            }
            Instant fence = later(record.latestDifferentClub(club),
                    latestDifferentDaily(dailyMembership.get(playerId), club, scheduleClubs));
            List<CanonicalFixture> clubFixtures = fixtures.stream()
                    .filter(fixture -> fixture.involves(club))
                    .toList();
            if (clubFixtures.stream().anyMatch(fixture -> fixture.finished()
                    && fixture.kickoff() == null)) {
                baseReasons.putIfAbsent(playerId, WindowReason.FINISHED_WITHOUT_CHRONOLOGY);
            }
            List<CanonicalFixture> completed = clubFixtures.stream()
                    .filter(CanonicalFixture::finished)
                    .filter(fixture -> fixture.kickoff() != null)
                    .filter(fixture -> !fixture.kickoff().isAfter(asOf))
                    .filter(fixture -> fence == null || fixture.kickoff().isAfter(fence))
                    .sorted(NEWEST_FIXTURE)
                    .limit(ExpectedPoints.WINDOW_MATCHES)
                    .toList();
            expected.put(playerId, completed);

            Instant oldest = completed.isEmpty() ? null : completed.getLast().kickoff();
            List<CanonicalFixture> pending = clubFixtures.stream()
                    .filter(fixture -> !fixture.finished() && fixture.kickoff() != null)
                    .filter(fixture -> !fixture.kickoff().isAfter(asOf))
                    .filter(fixture -> fence == null || fixture.kickoff().isAfter(fence))
                    .filter(fixture -> completed.size() < ExpectedPoints.WINDOW_MATCHES
                            || !fixture.kickoff().isBefore(oldest))
                    .toList();
            if (!pending.isEmpty()) {
                baseReasons.putIfAbsent(playerId, WindowReason.RESULT_PENDING);
                pending.forEach(fixture -> coverageGaps.add(new ComparisonCoverage.ClubGap(
                        club, fixture.id(), fixture.gameweek(), ComparisonCoverage.GapReason
                                .RELEVANT_ELAPSED_UNFINISHED_FIXTURE)));
            }
        }

        coverageGaps.addAll(loadCompletedSideCoverage(season, source, expected, clubs));
        Map<Integer, Aggregate> aggregates = loadTargetAggregates(season.seasonId(), expected);
        Map<Integer, List<FixtureOutcome>> outcomes = loadLatestOutcomes(season.seasonId(), expected);
        Map<Integer, SeasonMetrics> result = new HashMap<>();
        for (CapturePlayer player : players) {
            Integer playerId = ids.get(player.elementId());
            if (playerId == null) {
                continue;
            }
            Aggregate aggregate = aggregates.getOrDefault(playerId, new Aggregate());
            History record = history.getOrDefault(playerId, new History());
            Map<String, Object> ability = ability(aggregate, record.wholeSeasonSixty);
            MinutesWindow window = window(clubs.get(playerId), expected.getOrDefault(playerId, List.of()),
                    aggregate, outcomes.getOrDefault(playerId, List.of()), baseReasons.get(playerId));
            result.put(playerId, new SeasonMetrics(season, playerId,
                    positions.get(player.elementId()), ability, window, exposure(window, aggregate)));
        }
        return new CurrentBuild(result, List.copyOf(coverageGaps), true);
    }

    /** One set-based side-population read over the same expected windows as MinutesModel. */
    private List<ComparisonCoverage.ClubGap> loadCompletedSideCoverage(
            SeasonContext season, CanonicalSource source,
            Map<Integer, List<CanonicalFixture>> expectedByPlayer,
            Map<Integer, Integer> clubsByPlayer) {
        Map<String, SideTarget> unique = new LinkedHashMap<>();
        expectedByPlayer.forEach((playerId, fixtures) -> {
            Integer club = clubsByPlayer.get(playerId);
            if (club != null) {
                fixtures.forEach(fixture -> unique.putIfAbsent(club + ":" + fixture.id(),
                        new SideTarget(club, fixture)));
            }
        });
        if (unique.isEmpty()) {
            return List.of();
        }
        List<SideTarget> targets = new ArrayList<>(unique.values());
        Set<Integer> fixtureIds = new HashSet<>();
        targets.forEach(target -> fixtureIds.add(target.fixture().id()));
        Map<Integer, PfsReadSource.FixtureReceipt> receipts = pfs.fixtureReceipts(
                season.seasonId(), season.code(), fixtureIds);

        List<Object> args = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (SideTarget target : targets) {
            values.add("(?, ?)");
            args.add(target.clubId());
            args.add(target.fixture().id());
        }
        args.add(source.seasonValue());
        args.add(season.seasonId());
        Map<String, Integer> playedRows = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                WITH %s, expected_side(club_id, fixture_id) AS (VALUES %s)
                SELECT e.club_id, e.fixture_id,
                       count(p.player_id) FILTER (WHERE p.minutes BETWEEN 1 AND 90
                           AND p.total_points IS NOT NULL
                           AND p.opponent_team_id = CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END)
                           AS played_rows
                FROM expected_side e
                JOIN %s f ON f.id=e.fixture_id AND f.%s=?
                LEFT JOIN pfs_read p ON p.season_id=? AND p.fixture_id=e.fixture_id
                    AND p.was_home=(e.club_id=f.team_h)
                GROUP BY e.club_id, e.fixture_id
                """.formatted(pfs.cte(), String.join(",", values), source.table(),
                source.seasonColumn()), args.toArray())) {
            playedRows.put(integer(row.get("club_id")) + ":" + integer(row.get("fixture_id")),
                    integer(row.get("played_rows")));
        }
        List<ComparisonCoverage.ClubGap> gaps = new ArrayList<>();
        for (SideTarget target : targets) {
            PfsReadSource.FixtureReceipt receipt = receipts.getOrDefault(target.fixture().id(),
                    PfsReadSource.FixtureReceipt.LEGACY);
            ComparisonCoverage.GapReason reason = receipt == PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED
                    ? ComparisonCoverage.GapReason.UNVERIFIED_OWNED_RECEIPT
                    : playedRows.getOrDefault(target.clubId() + ":" + target.fixture().id(), 0) == 0
                            ? ComparisonCoverage.GapReason.COMPLETED_SIDE_MISSING_USABLE_CORE_ROWS
                            : null;
            if (reason != null) {
                gaps.add(new ComparisonCoverage.ClubGap(target.clubId(), target.fixture().id(),
                        target.fixture().gameweek(), reason));
            }
        }
        return gaps;
    }

    private WindowReason currentSourceFailure(DailyPriceSource.Capture capture,
                                              SeasonContext season, ScheduleSource.View schedule,
                                              Instant asOf) {
        if (!DailyPriceFreshnessCheck.isFresh(capture.date(), asOf)) {
            return WindowReason.PRICE_CAPTURE_STALE;
        }
        if (!capture.season().equals(season.code())) {
            return WindowReason.SOURCE_SEASON_MISMATCH;
        }
        if (schedule.snapshot().isEmpty()) {
            return WindowReason.SCHEDULE_CAPTURE_MISSING;
        }
        if (!schedule.snapshot().orElseThrow().season().equals(season.code())) {
            return WindowReason.SOURCE_SEASON_MISMATCH;
        }
        if (!schedule.completeness().complete()) {
            return WindowReason.SCHEDULE_INCOMPLETE;
        }
        LocalDate capturedDay = LocalDate.ofInstant(
                schedule.snapshot().orElseThrow().capturedAt(), UkTime.zone());
        return ScheduleFreshnessCheck.isFresh(capturedDay, asOf)
                ? null : WindowReason.SCHEDULE_CAPTURE_STALE;
    }

    private Map<Integer, SeasonMetrics> buildHistoricalMetrics(
            SeasonContext season, CanonicalSource source, List<CanonicalFixture> fixtures,
            Set<Integer> playerIds, Map<Integer, Integer> positions) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, History> histories = loadHistory(source, season.seasonId(), playerIds, null);
        Map<Integer, List<CanonicalFixture>> expected = new HashMap<>();
        Map<Integer, WindowReason> baseReasons = new HashMap<>();
        Map<Integer, Integer> clubs = new HashMap<>();
        for (int playerId : playerIds) {
            History history = histories.getOrDefault(playerId, new History());
            if (history.invalidMappings > 0) {
                baseReasons.put(playerId, WindowReason.UNMAPPABLE_HISTORY);
            } else if (history.contradictions > 0) {
                baseReasons.put(playerId, WindowReason.CONTRADICTORY_OPPONENT);
            } else if (history.clubs.isEmpty()) {
                baseReasons.put(playerId, WindowReason.NO_VERIFIED_CLUB);
            } else if (history.clubs.size() != 1) {
                baseReasons.put(playerId, WindowReason.MULTIPLE_CLUBS);
            }
            if (history.clubs.size() == 1) {
                int club = history.clubs.getFirst().club();
                clubs.put(playerId, club);
                List<CanonicalFixture> all = fixtures.stream().filter(fixture -> fixture.involves(club))
                        .sorted(NEWEST_FIXTURE).toList();
                if (all.stream().anyMatch(fixture -> !fixture.finished() || fixture.kickoff() == null)) {
                    baseReasons.putIfAbsent(playerId, WindowReason.FINISHED_WITHOUT_CHRONOLOGY);
                }
                expected.put(playerId, all.stream().filter(CanonicalFixture::finished)
                        .filter(fixture -> fixture.kickoff() != null).toList());
            } else {
                expected.put(playerId, List.of());
            }
        }
        Map<Integer, Aggregate> coverage = loadTargetAggregates(season.seasonId(), expected);
        Map<Integer, List<FixtureOutcome>> outcomes = loadLatestOutcomes(season.seasonId(), expected);
        Map<Integer, SeasonMetrics> result = new HashMap<>();
        for (int playerId : playerIds) {
            History history = histories.getOrDefault(playerId, new History());
            Aggregate aggregate = coverage.getOrDefault(playerId, new Aggregate());
            MinutesWindow window = window(clubs.get(playerId), expected.getOrDefault(playerId, List.of()),
                    aggregate, outcomes.getOrDefault(playerId, List.of()), baseReasons.get(playerId));
            result.put(playerId, new SeasonMetrics(season, playerId, positions.get(playerId),
                    ability(history.ability, history.wholeSeasonSixty), window,
                    exposure(window, aggregate)));
        }
        return result;
    }

    /**
     * One row per club: its completed fixture-sides in this season with the team's expected goals
     * (summed over its players) and expected goals conceded (the team figure, read once with
     * MAX). Sides are attributed by the canonical fixture, never {@code player_team_id}, and a
     * side whose concession was never written is left out rather than read as a shut-out.
     */
    private Map<Integer, ClubRates.SeasonSide> loadClubSides(CanonicalSource source, int seasonId,
                                                              Instant asOf) {
        String cutoff = asOf == null ? "" : " AND f.kickoff_time <= ?";
        String sql = """
                WITH %s, sides AS (
                    SELECT f.id AS fixture_id, p.was_home,
                           CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END AS club,
                           CASE WHEN count(*) FILTER (WHERE p.owned_evidence
                                                   AND p.expected_goals IS NULL)=0
                                THEN SUM(p.expected_goals) END AS xg_for,
                           CASE WHEN count(p.expected_goals_conceded)>0
                                THEN MAX(p.expected_goals_conceded) END AS xg_against
                    FROM pfs_read p
                    JOIN %s f ON f.id = p.fixture_id AND f.%s = ?
                    WHERE p.season_id = ?
                      AND COALESCE(f.finished, false) AND f.kickoff_time IS NOT NULL%s
                      AND p.opponent_team_id = CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END
                      AND p.minutes BETWEEN 0 AND 90
                    GROUP BY f.id, p.was_home, CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END
                )
                SELECT club, count(*) AS fixtures, SUM(xg_for) AS xg_for,
                       SUM(xg_against) AS xg_against,
                       count(*) FILTER (WHERE was_home) AS home_fixtures,
                       COALESCE(SUM(xg_for) FILTER (WHERE was_home), 0) AS home_xg_for
                FROM sides WHERE xg_against > 0 AND xg_for IS NOT NULL GROUP BY club
                """.formatted(pfs.cte(), source.table(), source.seasonColumn(), cutoff);
        List<Object> args = new ArrayList<>();
        args.add(source.seasonValue());
        args.add(seasonId);
        if (asOf != null) {
            args.add(Timestamp.from(asOf));
        }
        Map<Integer, ClubRates.SeasonSide> sides = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            sides.put(integer(row.get("club")), new ClubRates.SeasonSide(
                    integer(row.get("fixtures")), number(row.get("xg_for")),
                    number(row.get("xg_against")), integer(row.get("home_fixtures")),
                    number(row.get("home_xg_for"))));
        }
        return sides;
    }

    /**
     * Re-keys a season's club sides by the capture's team id through a unique, case-insensitive
     * short-name match — the club counterpart of {@link #uniqueBridge}. FPL renumbers clubs every
     * season, so an id is not an identity across seasons; a short name that is not unique on
     * either side attaches nothing rather than guessing.
     */
    private Map<Integer, ClubRates.SeasonSide> bridgeClubs(Map<Integer, ClubRates.SeasonSide> bySeasonId,
                                                            int seasonId,
                                                            List<CapturePlayer> capturePlayers) {
        if (bySeasonId.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Integer>> seasonByName = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, short_name FROM team WHERE season_id = ?", seasonId)) {
            seasonByName.computeIfAbsent(key(String.valueOf(row.get("short_name"))),
                    ignored -> new ArrayList<>()).add(integer(row.get("id")));
        }
        Map<String, Set<Integer>> captureByName = new HashMap<>();
        for (CapturePlayer player : capturePlayers) {
            if (player.teamId() != null && player.teamShort() != null) {
                captureByName.computeIfAbsent(key(player.teamShort()), ignored -> new HashSet<>())
                        .add(player.teamId());
            }
        }
        Map<Integer, ClubRates.SeasonSide> bridged = new HashMap<>();
        captureByName.forEach((name, captureIds) -> {
            List<Integer> seasonIds = seasonByName.getOrDefault(name, List.of());
            if (captureIds.size() == 1 && seasonIds.size() == 1) {
                ClubRates.SeasonSide side = bySeasonId.get(seasonIds.getFirst());
                if (side != null) {
                    bridged.put(captureIds.iterator().next(), side);
                }
            }
        });
        return bridged;
    }

    private Map<Integer, History> loadHistory(CanonicalSource source, int seasonId,
                                              Set<Integer> playerIds, Instant asOf) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        String ids = placeholders(playerIds.size());
        String cutoff = asOf == null ? "" : " AND (f.kickoff_time IS NULL OR f.kickoff_time <= ?)";
        String sql = """
                WITH %s, mapped AS (
                    SELECT p.player_id, p.fixture_id, p.minutes, p.total_points,
                           p.expected_goals, p.expected_assists, p.expected_goals_conceded,
                           p.owned_evidence,
                           p.defensive_contribution, p.opponent_team_id, p.was_home,
                           f.id AS canonical_id, f.team_h, f.team_a, f.kickoff_time, f.finished,
                           CASE WHEN p.was_home THEN f.team_h ELSE f.team_a END AS actual_club,
                           CASE WHEN p.was_home THEN f.team_a ELSE f.team_h END AS actual_opponent
                    FROM pfs_read p
                    LEFT JOIN %s f ON f.id = p.fixture_id AND f.%s = ?
                    WHERE p.season_id = ? AND p.player_id IN (%s)%s
                ), grouped AS (
                    SELECT player_id, actual_club,
                           count(*) FILTER (WHERE canonical_id IS NULL
                               OR (COALESCE(finished, false) AND kickoff_time IS NULL)) AS invalid_mapping,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id <> actual_opponent) AS contradictions,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent) AS valid_rows,
                           max(kickoff_time) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent) AS latest_kickoff,
                           (array_agg(fixture_id ORDER BY kickoff_time DESC, fixture_id DESC)
                               FILTER (WHERE canonical_id IS NOT NULL AND COALESCE(finished, false)
                                   AND kickoff_time IS NOT NULL
                                   AND opponent_team_id = actual_opponent))[1] AS latest_fixture_id,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes >= 60
                               AND minutes <= 90) AS matches60,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent
                               AND minutes BETWEEN 0 AND 90) AS usable_rows,
                           COALESCE(sum(minutes) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent
                               AND minutes BETWEEN 0 AND 90), 0) AS mins,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 1 AND 90) AS appearances,
                           COALESCE(sum(total_points) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent
                               AND minutes BETWEEN 0 AND 90), 0) AS points,
                           COALESCE(sum(expected_goals) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent
                               AND minutes BETWEEN 0 AND 90), 0) AS xg,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 0 AND 90
                               AND (expected_goals IS NOT NULL OR NOT owned_evidence)) AS xg_count,
                           COALESCE(sum(expected_assists) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent
                               AND minutes BETWEEN 0 AND 90), 0) AS xa,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 0 AND 90
                               AND (expected_assists IS NOT NULL OR NOT owned_evidence)) AS xa_count,
                           COALESCE(sum(expected_goals_conceded) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 60 AND 90), 0) AS xgc,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 60 AND 90) AS xgc_count,
                           COALESCE(sum(defensive_contribution) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 1 AND 59
                               AND defensive_contribution IS NOT NULL), 0) AS short_dc,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 1 AND 59
                               AND defensive_contribution IS NOT NULL) AS short_dc_count,
                           COALESCE(sum(defensive_contribution) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 60 AND 90
                               AND defensive_contribution IS NOT NULL), 0) AS long_dc,
                           count(*) FILTER (WHERE canonical_id IS NOT NULL
                               AND COALESCE(finished, false) AND kickoff_time IS NOT NULL
                               AND opponent_team_id = actual_opponent AND minutes BETWEEN 60 AND 90
                               AND defensive_contribution IS NOT NULL) AS long_dc_count
                    FROM mapped GROUP BY player_id, actual_club
                ) SELECT * FROM grouped
                """.formatted(pfs.cte(), source.table(), source.seasonColumn(), ids, cutoff);
        List<Object> args = new ArrayList<>();
        args.add(source.seasonValue());
        args.add(seasonId);
        args.addAll(playerIds);
        if (asOf != null) {
            args.add(Timestamp.from(asOf));
        }
        Map<Integer, History> histories = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            int playerId = integer(row.get("player_id"));
            History history = histories.computeIfAbsent(playerId, ignored -> new History());
            history.invalidMappings += integer(row.get("invalid_mapping"));
            history.contradictions += integer(row.get("contradictions"));
            history.wholeSeasonSixty += integer(row.get("matches60"));
            int validRows = integer(row.get("valid_rows"));
            Integer club = nullableInteger(row.get("actual_club"));
            Instant latest = nullableInstant(row.get("latest_kickoff"));
            Integer latestFixture = nullableInteger(row.get("latest_fixture_id"));
            if (validRows > 0 && club != null && latest != null && latestFixture != null) {
                history.clubs.add(new ClubObservation(club, latest, latestFixture));
            }
            history.ability.add(aggregate(row, 0));
        }
        return histories;
    }

    private Map<Integer, Map<Integer, Instant>> loadDailyMembership(
            String season, Set<Integer> playerIds, Instant asOf) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(season);
        args.add(Timestamp.from(asOf));
        args.addAll(playerIds);
        Map<Integer, Map<Integer, Instant>> memberships = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT element_id, team_id, max(captured_at) AS last_seen
                FROM %s WHERE season = ? AND captured_at <= ? AND team_id IS NOT NULL
                  AND team_id > 0 AND element_id IN (%s)
                GROUP BY element_id, team_id
                """.formatted(dailyTable, placeholders(playerIds.size())), args.toArray())) {
            memberships.computeIfAbsent(integer(row.get("element_id")), ignored -> new HashMap<>())
                    .put(integer(row.get("team_id")), instant(row.get("last_seen")));
        }
        return memberships;
    }

    private Map<Integer, Aggregate> loadTargetAggregates(
            int seasonId, Map<Integer, List<CanonicalFixture>> expected) {
        List<Target> targets = flatten(expected, false);
        Map<Integer, Aggregate> aggregates = new HashMap<>();
        expected.keySet().forEach(id -> aggregates.put(id, new Aggregate()));
        if (targets.isEmpty()) {
            return aggregates;
        }
        List<Object> args = new ArrayList<>(targets.size() * 2 + 1);
        String values = targetValues(targets, args, false);
        args.add(seasonId);
        String sql = """
                WITH %s, expected(player_id, fixture_id) AS (VALUES %s)
                SELECT e.player_id, count(*) AS expected,
                       count(p.player_id) AS observed,
                       count(*) FILTER (WHERE p.player_id IS NOT NULL
                           AND (p.minutes < 0 OR p.minutes > 90)) AS invalid_minutes,
                       count(*) FILTER (WHERE p.minutes BETWEEN 0 AND 90) AS usable_rows,
                       COALESCE(sum(p.minutes) FILTER (WHERE p.minutes BETWEEN 0 AND 90), 0) AS mins,
                       count(*) FILTER (WHERE p.minutes BETWEEN 1 AND 90) AS appearances,
                       count(*) FILTER (WHERE p.minutes BETWEEN 60 AND 90) AS matches60,
                       COALESCE(sum(p.total_points) FILTER (WHERE p.minutes BETWEEN 0 AND 90), 0) AS points,
                       COALESCE(sum(p.expected_goals) FILTER (WHERE p.minutes BETWEEN 0 AND 90), 0) AS xg,
                       count(*) FILTER (WHERE p.minutes BETWEEN 0 AND 90
                           AND (p.expected_goals IS NOT NULL OR NOT p.owned_evidence)) AS xg_count,
                       COALESCE(sum(p.expected_assists) FILTER (WHERE p.minutes BETWEEN 0 AND 90), 0) AS xa,
                       count(*) FILTER (WHERE p.minutes BETWEEN 0 AND 90
                           AND (p.expected_assists IS NOT NULL OR NOT p.owned_evidence)) AS xa_count,
                       COALESCE(sum(p.expected_goals_conceded)
                           FILTER (WHERE p.minutes BETWEEN 60 AND 90), 0) AS xgc,
                       count(*) FILTER (WHERE p.minutes BETWEEN 60 AND 90
                           AND (p.expected_goals_conceded IS NOT NULL OR NOT p.owned_evidence)) AS xgc_count,
                       COALESCE(sum(p.defensive_contribution)
                           FILTER (WHERE p.minutes BETWEEN 1 AND 59
                               AND p.defensive_contribution IS NOT NULL), 0) AS short_dc,
                       count(*) FILTER (WHERE p.minutes BETWEEN 1 AND 59
                           AND p.defensive_contribution IS NOT NULL) AS short_dc_count,
                       COALESCE(sum(p.defensive_contribution)
                           FILTER (WHERE p.minutes BETWEEN 60 AND 90
                               AND p.defensive_contribution IS NOT NULL), 0) AS long_dc,
                       count(*) FILTER (WHERE p.minutes BETWEEN 60 AND 90
                           AND p.defensive_contribution IS NOT NULL) AS long_dc_count
                FROM expected e LEFT JOIN pfs_read p
                  ON p.season_id = ? AND p.player_id = e.player_id AND p.fixture_id = e.fixture_id
                GROUP BY e.player_id
                """.formatted(pfs.cte(), values);
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            aggregates.put(integer(row.get("player_id")), aggregate(row, integer(row.get("expected"))));
        }
        return aggregates;
    }

    private Map<Integer, List<FixtureOutcome>> loadLatestOutcomes(
            int seasonId, Map<Integer, List<CanonicalFixture>> expected) {
        List<Target> targets = flatten(expected, true);
        if (targets.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>(targets.size() * 3 + 1);
        String values = targetValues(targets, args, true);
        args.add(seasonId);
        Map<Integer, List<FixtureOutcome>> outcomes = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                WITH %s, expected(player_id, fixture_id, kickoff) AS (VALUES %s)
                SELECT e.player_id, e.fixture_id, e.kickoff, p.minutes
                FROM expected e LEFT JOIN pfs_read p
                  ON p.season_id = ? AND p.player_id = e.player_id AND p.fixture_id = e.fixture_id
                ORDER BY e.player_id, e.kickoff DESC, e.fixture_id DESC
                """.formatted(pfs.cte(), values), args.toArray())) {
            outcomes.computeIfAbsent(integer(row.get("player_id")), ignored -> new ArrayList<>())
                    .add(new FixtureOutcome(integer(row.get("fixture_id")),
                            instant(row.get("kickoff")), nullableInteger(row.get("minutes"))));
        }
        return outcomes;
    }

    private static List<Target> flatten(Map<Integer, List<CanonicalFixture>> expected,
                                        boolean latestThreeOnly) {
        List<Target> targets = new ArrayList<>();
        expected.forEach((playerId, fixtures) -> {
            List<CanonicalFixture> selected = fixtures.stream().sorted(NEWEST_FIXTURE)
                    .limit(latestThreeOnly ? MinutesModel.CONSISTENTLY_UNDER_60_FIXTURES
                            : Long.MAX_VALUE).toList();
            selected.forEach(fixture -> targets.add(new Target(playerId, fixture)));
        });
        return targets;
    }

    private static String targetValues(List<Target> targets, List<Object> args, boolean kickoff) {
        List<String> rows = new ArrayList<>(targets.size());
        for (Target target : targets) {
            rows.add(kickoff ? "(?, ?, ?::timestamptz)" : "(?, ?)");
            args.add(target.playerId());
            args.add(target.fixture().id());
            if (kickoff) {
                args.add(Timestamp.from(target.fixture().kickoff()));
            }
        }
        return String.join(",", rows);
    }

    private static Aggregate aggregate(Map<String, Object> row, int expected) {
        Aggregate aggregate = new Aggregate();
        aggregate.expected = expected;
        aggregate.observed = integer(row.get("observed"));
        aggregate.invalidMinutes = integer(row.get("invalid_minutes"));
        aggregate.usableRows = integer(row.get("usable_rows"));
        aggregate.totalMinutes = integer(row.get("mins"));
        aggregate.appearances = integer(row.get("appearances"));
        aggregate.sixtyPlus = integer(row.get("matches60"));
        aggregate.points = number(row.get("points"));
        aggregate.xg = number(row.get("xg"));
        aggregate.xgCount = integer(row.get("xg_count"));
        aggregate.xa = number(row.get("xa"));
        aggregate.xaCount = integer(row.get("xa_count"));
        aggregate.xgc = number(row.get("xgc"));
        aggregate.xgcCount = integer(row.get("xgc_count"));
        aggregate.shortDefcon = number(row.get("short_dc"));
        aggregate.shortDefconCount = integer(row.get("short_dc_count"));
        aggregate.longDefcon = number(row.get("long_dc"));
        aggregate.longDefconCount = integer(row.get("long_dc_count"));
        return aggregate;
    }

    private static Map<String, Object> ability(Aggregate aggregate, int wholeSeasonSixty) {
        if (aggregate.usableRows == 0) {
            return Map.of();
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mins", aggregate.totalMinutes);
        metrics.put("matches", aggregate.appearances);
        metrics.put("matches60", wholeSeasonSixty);
        if (aggregate.totalMinutes > 0) {
            if (aggregate.xgCount == aggregate.usableRows) {
                metrics.put("xg90", aggregate.xg * 90 / aggregate.totalMinutes);
            }
            if (aggregate.xaCount == aggregate.usableRows) {
                metrics.put("xa90", aggregate.xa * 90 / aggregate.totalMinutes);
            }
            metrics.put("pts90", aggregate.points * 90 / aggregate.totalMinutes);
        }
        if (aggregate.xgcCount > 0) {
            metrics.put("xgc_per_match", aggregate.xgc / aggregate.xgcCount);
        }
        metrics.put("defcon_short_matches", aggregate.shortDefconCount);
        if (aggregate.shortDefconCount > 0) {
            metrics.put("defcon_short_avg", aggregate.shortDefcon / aggregate.shortDefconCount);
        }
        metrics.put("defcon_matches", aggregate.longDefconCount);
        if (aggregate.longDefconCount > 0) {
            double mean = aggregate.longDefcon / aggregate.longDefconCount;
            metrics.put("defcon_avg", mean);
            metrics.put("defcon_long_avg", mean);
        }
        metrics.put("defcon_long_matches", aggregate.longDefconCount);
        return metrics;
    }

    private static MinutesWindow window(Integer club, List<CanonicalFixture> expected,
                                        Aggregate aggregate, List<FixtureOutcome> outcomes,
                                        WindowReason baseReason) {
        WindowReason reason = baseReason;
        if (reason == null && aggregate.invalidMinutes > 0) {
            reason = WindowReason.INVALID_MINUTES;
        }
        if (reason == null && aggregate.observed != expected.size()) {
            reason = WindowReason.MISSING_OBSERVATION;
        }
        if (reason == null) {
            reason = WindowReason.COMPLETE;
        }
        Instant first = expected.stream().map(CanonicalFixture::kickoff)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant last = expected.stream().map(CanonicalFixture::kickoff)
                .max(Comparator.naturalOrder()).orElse(null);
        return new MinutesWindow(reason == WindowReason.COMPLETE, reason, club, first, last,
                expected.size(), aggregate.observed, aggregate.totalMinutes,
                aggregate.appearances, aggregate.sixtyPlus, outcomes);
    }

    private static MinutesWindow emptyWindow(WindowReason reason, Integer club) {
        return new MinutesWindow(false, reason, club, null, null,
                0, 0, 0, 0, 0, List.of());
    }

    private static SeasonBlend.Exposure exposure(MinutesWindow window, Aggregate aggregate) {
        if (!window.complete() || window.expected() == 0) {
            return null;
        }
        double fixtures = window.expected();
        double appearanceRate = window.appearances() / fixtures;
        double sixtyRate = window.sixtyPlus() / fixtures;
        return new SeasonBlend.Exposure(window.expected(), window.appearances(), window.sixtyPlus(),
                window.totalMinutes(), appearanceRate, sixtyRate,
                appearanceRate - sixtyRate, window.totalMinutes() / fixtures,
                aggregate.points / fixtures);
    }

    private List<CanonicalFixture> loadCanonicalFixtures(CanonicalSource source) {
        return jdbc.query("""
                SELECT id, gameweek, team_h, team_a, kickoff_time, started, finished,
                       team_h_difficulty, team_a_difficulty
                FROM %s WHERE %s = ? ORDER BY id
                """.formatted(source.table(), source.seasonColumn()), (rs, rowNum) ->
                        new CanonicalFixture(rs.getInt("id"),
                                nullableInteger(rs.getObject("gameweek")), rs.getInt("team_h"),
                                rs.getInt("team_a"), nullableInstant(rs.getObject("kickoff_time")),
                                rs.getBoolean("started"), rs.getBoolean("finished"),
                                nullableInteger(rs.getObject("team_h_difficulty")),
                                nullableInteger(rs.getObject("team_a_difficulty"))),
                source.seasonValue());
    }

    private static Map<Integer, Integer> currentPositionsByPlayer(
            List<CapturePlayer> players, Map<Integer, Integer> ids,
            Map<Integer, Integer> positionsByElement) {
        Map<Integer, Integer> positions = new HashMap<>();
        for (CapturePlayer player : players) {
            Integer id = ids.get(player.elementId());
            if (id != null) {
                positions.put(id, positionsByElement.get(player.elementId()));
            }
        }
        return positions;
    }

    private static Instant latestDifferentDaily(Map<Integer, Instant> membership, int club,
                                                Set<Integer> scheduleClubs) {
        if (membership == null) {
            return null;
        }
        return membership.entrySet().stream()
                .filter(entry -> entry.getKey() != club && scheduleClubs.contains(entry.getKey()))
                .map(Map.Entry::getValue).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant later(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private static boolean sameDefconDefinition(Integer current, Integer previous) {
        return defconFamily(current) >= 0 && defconFamily(current) == defconFamily(previous);
    }

    private static int defconFamily(Integer position) {
        if (position == null) return -1;
        return switch (position) {
            case 1 -> 0;
            case 2 -> 1;
            case 3, 4 -> 2;
            default -> -1;
        };
    }

    private static Map<String, Object> withoutDefcon(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.keySet().removeIf(key -> key.startsWith("defcon_"));
        return copy;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Instant nullableInstant(Object value) {
        return value == null ? null : instant(value);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalStateException("Expected timestamp, got "
                    + (value == null ? "null" : value.getClass().getName()));
        };
    }
}
