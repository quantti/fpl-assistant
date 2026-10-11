package fi.karivanttinen.fplassistant.tools;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.sync.GameweekFinalityReader;
import fi.karivanttinen.fplassistant.sync.OwnedSyncTables;

/**
 * Phase 4 expected-stats (xG / xA / xGI / xGA) tools. Both read FPL's own expected-stats columns
 * in {@code player_fixture_stats} — these are FPL's data, not a third-party model — and aggregate
 * over the last N fixtures in the resolved season. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 *
 * <p>Each tool answers from a single query. {@code expected_goals}/{@code expected_assists} are
 * per-player and sum across a team; {@code expected_goals_conceded} and {@code goals_conceded}
 * are <b>not</b> — FPL records the team's figure on every player who was on the pitch, so summing
 * them would multiply the team's xGA by its squad size. They are aggregated with {@code MAX},
 * which is the value carried by anyone who played the full match. Verified against the
 * {@code fixture} scoreline: {@code MAX(goals_conceded)} matches the goals the team actually
 * conceded in every checked fixture.
 *
 * <p>Beneath the season banners every reply carries {@link DataAge#METRIC_REFRESH} and one
 * {@code Stored match coverage:} line per season in the returned window (KAR-9). The dates are the
 * returned rows' kickoffs: {@code player_fixture_stats.updated_at} is never shown, because a
 * DefCon-only enrichment write restamps it without touching minutes, xG or xA.
 */
@Component
public class ExpectedStatsTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(ExpectedStatsTools.class);
    private static final int MAX_FIXTURES = 10;
    private static final int MAX_NAME_MATCHES = 4;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final Clock clock;
    private final PfsReadSource pfs;
    private final PlayerNameSearch names;
    private final GameweekFinalityReader finalityReader;
    private final TransactionTemplate readTransaction;

    @Autowired
    public ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                              ObjectProvider<Clock> clock, PfsReadSource pfs, PlayerNameSearch names,
                              GameweekFinalityReader finalityReader,
                              PlatformTransactionManager transactionManager) {
        this(jdbc, seasonResolver, clock, pfs, names, finalityReader, transactionManager, true);
    }

    private ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                               ObjectProvider<Clock> clock, PfsReadSource pfs, PlayerNameSearch names,
                               GameweekFinalityReader finalityReader,
                               PlatformTransactionManager transactionManager, boolean readOnlySnapshot) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.pfs = pfs;
        this.names = names;
        this.finalityReader = finalityReader;
        this.readTransaction = readOnlySnapshot ? new TransactionTemplate(transactionManager) : null;
        if (this.readTransaction != null) {
            this.readTransaction.setReadOnly(true);
            this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

    /** Compatibility constructor for focused legacy tests. */
    ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                       ObjectProvider<Clock> clock, PlayerNameSearch names) {
        this(jdbc, seasonResolver, clock,
                new PfsReadSource(jdbc, "OFF", "", "fpl_fixture_sync", "fpl_player_fixture_evidence"),
                names, new GameweekFinalityReader(jdbc, clock.getIfAvailable(Clock::systemUTC),
                        OwnedSyncTables.defaults()), null, false);
    }

    @Tool(description = """
            Get a player's underlying expected stats over their last N fixtures this season,
            filled from last season when this season has fewer than N — every row names its
            season — expected goals (xG), expected assists (xA), expected goal
            involvements (xGI), alongside actual goals, assists and minutes, and his defensive
            contribution (DefCon): each match's raw action count, and per season how often he
            reached the threshold that pays 2 points. Call this for every player in a comparison of
            defenders or midfielders. The name may be a
            surname, a full name, or a name followed by the club as FPL writes it — short name
            or three-letter code ("Hall Newcastle", "Fernandes Man Utd", "Fernandes MUN");
            accents are optional. Use this to judge whether a player is over- or under-performing their
            underlying numbers, not just their raw goal/assist counts. N is capped at 10. The
            reply begins with the season the numbers come from; if that is not the season
            currently being played, tell the user the figures are historical.
            """)
    public String getPlayerExpectedStats(String playerName, int lastN) {
        return readSnapshot(() -> playerExpectedStats(playerName, lastN));
    }

    private String playerExpectedStats(String playerName, int lastN) {
        try {
            Instant asOf = clock.instant();
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_FIXTURES));
            PlayerNameSearch.Fragment match = names.fragment(playerName, "p.web_name",
                    "p.web_name || ' ' || COALESCE(p.first_name, '') || ' ' || COALESCE(p.second_name, '')",
                    "t.name || ' ' || t.short_name");
            List<Object> args = new ArrayList<>();
            args.add(season.seasonId());
            args.addAll(match.whereArgs());
            args.addAll(match.orderArgs());
            args.add(MAX_NAME_MATCHES);

            List<Map<String, Object>> players = jdbc.queryForList("""
                    SELECT p.id, p.web_name, p.element_type FROM player p
                    JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ? AND %s
                    ORDER BY %s, p.total_points DESC
                    LIMIT ?
                    """.formatted(match.where(), match.exactFirst()), args.toArray());

            if (players.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            int playerId = ((Number) players.getFirst().get("id")).intValue();
            String webName = (String) players.getFirst().get("web_name");
            Object position = players.getFirst().get("element_type");
            String ambiguity = NameMatch.ambiguityNote(
                    players.stream().map(p -> String.valueOf(p.get("web_name"))).toList());

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    WITH %s
                    SELECT pfs.gameweek,pfs.fixture_id,pfs.season_id,pfs.evidence_status,
                           pfs.was_home,pfs.minutes,pfs.goals_scored,pfs.assists,
                           pfs.expected_goals, pfs.expected_assists, pfs.expected_goal_involvements,
                           pfs.defensive_contribution,
                           pfs.kickoff_time, pfs.source_read_finished_at,
                           pfs.verified_published_at,
                           opp.short_name AS opponent
                    FROM pfs_read pfs
                    LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                    WHERE pfs.season_id = ? AND pfs.player_id = ?
                    ORDER BY pfs.kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """.formatted(pfs.cte()), season.seasonId(), playerId, limit);

            List<Map<String, Object>> shown = new ArrayList<>(rows);
            for (Map<String, Object> r : shown) {
                r.put("season_code", season.code());
                r.put("season_id", season.seasonId());
                r.put("element_type", position);
            }
            Optional<SeasonContext> previous = Optional.empty();
            int fromPrevious = 0;
            if (season.currentSeason() && shown.size() < limit) {
                previous = seasonResolver.previousSeason(season);
                if (previous.isPresent()) {
                    List<Map<String, Object>> older = previousSeasonPlayerRows(
                            previous.get(), season.seasonId(), webName, limit - shown.size());
                    fromPrevious = older.size();
                    shown.addAll(older);
                }
            }

            BiFunction<List<Map<String, Object>>, String, String> coverage =
                    (window, code) -> playerCoverage(webName, window, code, asOf);
            if (shown.isEmpty()) {
                return season.provenance() + "\n" + sampleEvidence(shown, 0, season.code(), previous, coverage)
                        + "\nNo fixture-level expected stats yet for " + webName
                        + " in " + season.code() + "." + ambiguity;
            }

            String header = season.provenance() + (fromPrevious > 0
                    ? "\n" + previous.orElseThrow().previousSeasonProvenance(true) : "");
            return header + "\n" + sampleEvidence(shown, fromPrevious, season.code(), previous, coverage)
                    + "\n\n" + formatPlayer(webName, shown, season.code(), fromPrevious) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getPlayerExpectedStats(\"{}\", {}) failed", playerName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Get a team's expected output over their last N fixtures this season, filled from
            last season when this season has fewer than N — every row names its season —
            attacking expected goals (xG), expected assists (xA) and expected goal
            involvements (xGI) aggregated across the team's players per fixture, plus actual
            goals scored, and defensively the expected goals against (xGA) and actual goals
            conceded per fixture. Use this to judge whether a team's attack is threatening
            regardless of whether goals have gone in, and whether its defence is under
            sustained pressure (xGA is the clean-sheet signal for its defenders and keeper).
            N is capped at 10. The reply begins with the season the numbers come from; if that
            is not the season currently being played, tell the user the figures are historical.
            """)
    public String getTeamExpectedGoals(String teamName, int lastN) {
        return readSnapshot(() -> teamExpectedGoals(teamName, lastN));
    }

    private String teamExpectedGoals(String teamName, int lastN) {
        try {
            Instant asOf = clock.instant();
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_FIXTURES));
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> teams = jdbc.queryForList("""
                    SELECT id, name FROM team
                    WHERE season_id = ?
                      AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                    ORDER BY name
                    LIMIT ?
                    """, season.seasonId(), pattern, teamName, MAX_NAME_MATCHES);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String name = (String) teams.getFirst().get("name");
            String ambiguity = NameMatch.ambiguityNote(
                    teams.stream().map(t -> String.valueOf(t.get("name"))).toList());

            // One query for the whole window: attacking figures sum across the team's players,
            // the conceded figures are per-team values repeated on every player (see class javadoc).
            // The kickoff span and source-row counts date the group without hiding undated rows.
            List<Map<String, Object>> fixtures = jdbc.queryForList("""
                    WITH %s
                    SELECT pfs.gameweek,pfs.fixture_id,pfs.season_id,pfs.was_home,
                           opp.short_name AS opponent,
                           CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_goals IS NULL)=0 THEN COALESCE(SUM(pfs.expected_goals), 0) END AS xg,
                           CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_assists IS NULL)=0 THEN COALESCE(SUM(pfs.expected_assists), 0) END AS xa,
                           CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_goal_involvements IS NULL)=0 THEN COALESCE(SUM(pfs.expected_goal_involvements), 0) END AS xgi,
                           COALESCE(SUM(pfs.goals_scored), 0) AS goals,
                           CASE WHEN COUNT(pfs.expected_goals_conceded)>0 THEN MAX(pfs.expected_goals_conceded) END AS xga,
                           COALESCE(MAX(pfs.goals_conceded), 0) AS conceded,
                           MIN(pfs.kickoff_time) AS kickoff_min,
                           MAX(pfs.kickoff_time) AS kickoff_max,
                           COUNT(*) AS source_rows,
                           COUNT(*) FILTER (WHERE pfs.evidence_status='PROVISIONAL') AS incremental_rows,
                           COUNT(*) FILTER (WHERE pfs.evidence_status<>'PROVISIONAL') AS other_rows,
                           COUNT(pfs.kickoff_time) AS dated_rows,
                           MIN(pfs.source_read_finished_at) AS source_read_min,
                           MAX(pfs.source_read_finished_at) AS source_read_max,
                           COUNT(pfs.source_read_finished_at) AS owned_rows,
                           MIN(pfs.verified_published_at) AS publication_min,
                           MAX(pfs.verified_published_at) AS publication_max,
                           COUNT(pfs.verified_published_at) AS published_rows
                    FROM pfs_read pfs
                    LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                    WHERE pfs.season_id = ? AND pfs.player_team_id = ?
                    GROUP BY pfs.fixture_id,pfs.gameweek,pfs.season_id,pfs.was_home,opp.short_name
                    ORDER BY MAX(pfs.kickoff_time) DESC NULLS LAST
                    LIMIT ?
                    """.formatted(pfs.cte()), season.seasonId(), teamId, limit);

            List<Map<String, Object>> shown = new ArrayList<>(fixtures);
            for (Map<String, Object> f : shown) {
                f.put("season_code", season.code());
                f.put("season_id", season.seasonId());
            }
            Optional<SeasonContext> previous = Optional.empty();
            int fromPrevious = 0;
            if (season.currentSeason() && shown.size() < limit) {
                previous = seasonResolver.previousSeason(season);
                if (previous.isPresent()) {
                    List<Map<String, Object>> older = previousSeasonTeamRows(
                            previous.get(), name, limit - shown.size());
                    fromPrevious = older.size();
                    shown.addAll(older);
                }
            }

            BiFunction<List<Map<String, Object>>, String, String> coverage =
                    (window, code) -> teamCoverage(name, window, code, asOf);
            if (shown.isEmpty()) {
                return season.provenance() + "\n" + sampleEvidence(shown, 0, season.code(), previous, coverage)
                        + "\nNo fixture-level expected stats yet for " + name
                        + " in " + season.code() + "." + ambiguity;
            }

            String header = season.provenance() + (fromPrevious > 0
                    ? "\n" + previous.orElseThrow().previousSeasonProvenance(true) : "");
            return header + "\n" + sampleEvidence(shown, fromPrevious, season.code(), previous, coverage)
                    + "\n\n" + formatTeam(name, shown, season.code(), fromPrevious) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getTeamExpectedGoals(\"{}\", {}) failed", teamName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * Last season's fixtures for the same name, only when that name is unique in BOTH seasons —
     * the same bridge the ranking tools use, for the same reason: element ids are reassigned
     * every summer and a guessed match attaches another player's football to this player's card.
     * One player of the name last season and two this season still leaves unknown which of the
     * two he became, so that fill would be a guess either way round.
     */
    private List<Map<String, Object>> previousSeasonPlayerRows(SeasonContext previous,
                                                               int currentSeasonId,
                                                               String webName, int limit) {
        Integer twins = jdbc.queryForObject("""
                SELECT count(*) FROM player WHERE season_id = ? AND LOWER(web_name) = LOWER(?)
                """, Integer.class, currentSeasonId, webName);
        if (twins == null || twins != 1) {
            return List.of();
        }
        List<Map<String, Object>> ids = jdbc.queryForList("""
                SELECT id, element_type FROM player WHERE season_id = ? AND LOWER(web_name) = LOWER(?)
                """, previous.seasonId(), webName);
        if (ids.size() != 1) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH %s
                SELECT pfs.gameweek,pfs.fixture_id,pfs.season_id,pfs.evidence_status,
                       pfs.was_home,pfs.minutes,pfs.goals_scored,pfs.assists,
                       pfs.expected_goals, pfs.expected_assists, pfs.expected_goal_involvements,
                       pfs.defensive_contribution,
                       pfs.kickoff_time, pfs.source_read_finished_at,
                       pfs.verified_published_at,
                       opp.short_name AS opponent
                FROM pfs_read pfs
                LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                WHERE pfs.season_id = ? AND pfs.player_id = ?
                ORDER BY pfs.kickoff_time DESC NULLS LAST
                LIMIT ?
                """.formatted(pfs.cte()), previous.seasonId(), ids.getFirst().get("id"), limit);
        for (Map<String, Object> r : rows) {
            r.put("season_code", previous.code());
            r.put("season_id", previous.seasonId());
            r.put("element_type", ids.getFirst().get("element_type"));
        }
        return rows;
    }

    /** The same fill for a club, matched on its name in the previous season. */
    private List<Map<String, Object>> previousSeasonTeamRows(SeasonContext previous, String name,
                                                             int limit) {
        List<Integer> ids = jdbc.queryForList("""
                SELECT id FROM team WHERE season_id = ? AND LOWER(name) = LOWER(?)
                """, Integer.class, previous.seasonId(), name);
        if (ids.size() != 1) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH %s
                SELECT pfs.gameweek,pfs.fixture_id,pfs.season_id,pfs.was_home,
                       opp.short_name AS opponent,
                       CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_goals IS NULL)=0 THEN COALESCE(SUM(pfs.expected_goals), 0) END AS xg,
                       CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_assists IS NULL)=0 THEN COALESCE(SUM(pfs.expected_assists), 0) END AS xa,
                       CASE WHEN COUNT(*) FILTER (WHERE pfs.owned_evidence AND pfs.expected_goal_involvements IS NULL)=0 THEN COALESCE(SUM(pfs.expected_goal_involvements), 0) END AS xgi,
                       COALESCE(SUM(pfs.goals_scored), 0) AS goals,
                       CASE WHEN COUNT(pfs.expected_goals_conceded)>0 THEN MAX(pfs.expected_goals_conceded) END AS xga,
                       COALESCE(MAX(pfs.goals_conceded), 0) AS conceded,
                       MIN(pfs.kickoff_time) AS kickoff_min,
                       MAX(pfs.kickoff_time) AS kickoff_max,
                       COUNT(*) AS source_rows,
                       COUNT(*) FILTER (WHERE pfs.evidence_status='PROVISIONAL') AS incremental_rows,
                       COUNT(*) FILTER (WHERE pfs.evidence_status<>'PROVISIONAL') AS other_rows,
                       COUNT(pfs.kickoff_time) AS dated_rows,
                       MIN(pfs.source_read_finished_at) AS source_read_min,
                       MAX(pfs.source_read_finished_at) AS source_read_max,
                       COUNT(pfs.source_read_finished_at) AS owned_rows,
                       MIN(pfs.verified_published_at) AS publication_min,
                       MAX(pfs.verified_published_at) AS publication_max,
                       COUNT(pfs.verified_published_at) AS published_rows
                FROM pfs_read pfs
                LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                WHERE pfs.season_id = ? AND pfs.player_team_id = ?
                GROUP BY pfs.fixture_id,pfs.gameweek,pfs.season_id,pfs.was_home,opp.short_name
                ORDER BY MAX(pfs.kickoff_time) DESC NULLS LAST
                LIMIT ?
                """.formatted(pfs.cte()), previous.seasonId(), ids.getFirst(), limit);
        for (Map<String, Object> r : rows) {
            r.put("season_code", previous.code());
            r.put("season_id", previous.seasonId());
        }
        return rows;
    }

    /**
     * The unknown-refresh notice, then match coverage for the resolved season's rows and, when the
     * window was filled, separately for the previous season's — two seasons never share a range.
     * The fill is appended after the resolved season's rows, so the split is positional.
     */
    private String readSnapshot(Supplier<String> operation) {
        if (readTransaction == null) return operation.get();
        return readTransaction.execute(status -> operation.get());
    }

    private String sampleEvidence(List<Map<String, Object>> shown, int fromPrevious,
                                  String currentCode, Optional<SeasonContext> previous,
                                  BiFunction<List<Map<String, Object>>, String, String> coverage) {
        int split = shown.size() - fromPrevious;
        List<Map<String, Object>> currentRows = shown.subList(0, split);
        String out = metricRefresh(currentRows, currentCode) + finalityEvidence(currentRows) + "\n"
                + coverage.apply(currentRows, currentCode);
        if (fromPrevious > 0) {
            String previousCode = previous.orElseThrow().code();
            List<Map<String, Object>> previousRows = shown.subList(split, shown.size());
            out += "\n" + metricRefresh(previousRows, previousCode) + finalityEvidence(previousRows) + "\n"
                    + coverage.apply(previousRows, previousCode);
        }
        return out;
    }

    private String finalityEvidence(List<Map<String, Object>> rows) {
        Map<Integer, int[]> grouped = new java.util.TreeMap<>();
        Map<Integer, Set<Integer>> fixtureIdsByGameweek = new java.util.TreeMap<>();
        int seasonId = 0;
        String seasonCode = null;
        for (Map<String, Object> row : rows) {
            if (seasonId == 0) {
                seasonId = ((Number) row.get("season_id")).intValue();
                seasonCode = String.valueOf(row.get("season_code"));
            }
            int gameweek = ((Number) row.get("gameweek")).intValue();
            int[] counts = grouped.computeIfAbsent(gameweek, ignored -> new int[2]);
            fixtureIdsByGameweek.computeIfAbsent(gameweek, ignored -> new java.util.TreeSet<>())
                    .add(((Number) row.get("fixture_id")).intValue());
            if (row.containsKey("source_rows")) {
                counts[0] += ((Number) row.get("incremental_rows")).intValue();
                counts[1] += ((Number) row.get("other_rows")).intValue();
            } else if ("PROVISIONAL".equals(row.get("evidence_status"))) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        }
        Map<Integer, PfsReadSource.IncrementalRows> rowCounts = new java.util.TreeMap<>();
        grouped.forEach((gameweek, counts) -> rowCounts.put(gameweek,
                new PfsReadSource.IncrementalRows(counts[0], counts[1])));
        Set<Integer> incrementalGameweeks = rowCounts.entrySet().stream()
                .filter(entry -> entry.getValue().incrementalRows() > 0)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (incrementalGameweeks.isEmpty()) return "";
        Map<Integer, GameweekFinalityReader.Result> finality = finalityReader.read(
                seasonId, seasonCode, incrementalGameweeks);
        return "\n" + GameweekFinalityFormatter.summary(seasonCode, rowCounts, finality,
                fixtureIdsByGameweek);
    }

    private static String metricRefresh(List<Map<String, Object>> rows, String seasonCode) {
        DataAge.Span source = DataAge.Span.of(List.of());
        DataAge.Span publication = DataAge.Span.of(List.of());
        long total = 0;
        for (Map<String, Object> row : rows) {
            if (row.containsKey("source_rows")) {
                long sourceRows = ((Number) row.get("source_rows")).longValue();
                long ownedRows = ((Number) row.get("owned_rows")).longValue();
                long publishedRows = ((Number) row.get("published_rows")).longValue();
                total += sourceRows;
                source = source.plus(DataAge.Span.aggregate(row.get("source_read_min"),
                        row.get("source_read_max"), sourceRows, ownedRows));
                publication = publication.plus(DataAge.Span.aggregate(row.get("publication_min"),
                        row.get("publication_max"), sourceRows, publishedRows));
            } else {
                total++;
                source = source.plus(DataAge.Span.of(java.util.Collections.singletonList(
                        row.get("source_read_finished_at"))));
                publication = publication.plus(DataAge.Span.of(java.util.Collections.singletonList(
                        row.get("verified_published_at"))));
            }
        }
        return DataAge.metricRefresh("season " + seasonCode + ", returned sample",
                source, publication, total);
    }

    private static String playerCoverage(String webName, List<Map<String, Object>> rows, String code,
                                         Instant asOf) {
        DataAge.Span kickoffs = DataAge.Span.of(rows.stream().map(r -> r.get("kickoff_time")).toList());
        return DataAge.matchCoverage(coverageScope(webName, code,
                rows.isEmpty() ? "" : plural(rows.size(), "returned fixture row")), kickoffs, asOf);
    }

    /** Each group carries its kickoff MIN/MAX and total/dated source-row counts from the query. */
    private static String teamCoverage(String name, List<Map<String, Object>> fixtures, String code,
                                       Instant asOf) {
        DataAge.Span kickoffs = fixtures.stream()
                .map(f -> DataAge.Span.aggregate(f.get("kickoff_min"), f.get("kickoff_max"),
                        ((Number) f.get("source_rows")).longValue(), ((Number) f.get("dated_rows")).longValue()))
                .reduce(DataAge.Span.of(List.of()), DataAge.Span::plus);
        return DataAge.matchCoverage(coverageScope(name, code, fixtures.isEmpty() ? ""
                : plural(fixtures.size(), "returned fixture") + " from "
                        + plural(kickoffs.rows(), "stored player row")), kickoffs, asOf);
    }

    private static String coverageScope(String subject, String code, String counts) {
        return subject + ", season " + code + (counts.isEmpty() ? "" : " (" + counts + ")");
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /** How many fixtures came from which season, for the heading. */
    private static String windowLabel(List<Map<String, Object>> rows, String currentCode,
                                      int fromPrevious) {
        if (fromPrevious <= 0) {
            return "";
        }
        String previousCode = String.valueOf(rows.getLast().get("season_code"));
        return ": %d from %s, %d from %s".formatted(
                rows.size() - fromPrevious, currentCode, fromPrevious, previousCode);
    }

    /**
     * The row's season, printed only when the reply actually mixes two. A single-season window
     * needs no prefix — the banner above already names the season, and repeating it on every
     * line is noise the model has to read past.
     */
    private static String seasonPrefix(Map<String, Object> row, int fromPrevious) {
        Object code = row.get("season_code");
        return fromPrevious <= 0 || code == null ? "" : code + " ";
    }

    static String formatPlayer(String webName, List<Map<String, Object>> rows, String currentCode,
                               int fromPrevious) {
        double xg = 0, xa = 0, xgi = 0;
        boolean xgComplete = true, xaComplete = true, xgiComplete = true;
        int goals = 0, assists = 0, minutes = 0;
        StringBuilder out = new StringBuilder("Expected stats for ").append(webName)
                .append(" (last ").append(rows.size()).append(" fixtures")
                .append(windowLabel(rows, currentCode, fromPrevious)).append("):\n");
        for (Map<String, Object> r : rows) {
            xgComplete &= r.get("expected_goals") != null;
            xaComplete &= r.get("expected_assists") != null;
            xgiComplete &= r.get("expected_goal_involvements") != null;
            xg += num(r, "expected_goals");
            xa += num(r, "expected_assists");
            xgi += num(r, "expected_goal_involvements");
            goals += (int) num(r, "goals_scored");
            assists += (int) num(r, "assists");
            minutes += (int) num(r, "minutes");
            out.append("  %sGW%s vs %s %s: %d min, xG %s xA %s (G%d A%d)%s\n".formatted(
                    seasonPrefix(r, fromPrevious), r.get("gameweek"), opponent(r),
                    Boolean.TRUE.equals(r.get("was_home")) ? "(H)" : "(A)",
                    (int) num(r, "minutes"), metric(r, "expected_goals"),
                    metric(r, "expected_assists"),
                    (int) num(r, "goals_scored"), (int) num(r, "assists"), defconCell(r)));
        }
        out.append("\nTotals over window: xG %s | xA %s | xGI %s | Goals %d | Assists %d | Minutes %d\n"
                .formatted(metric(xg, xgComplete), metric(xa, xaComplete),
                        metric(xgi, xgiComplete), goals, assists, minutes));
        out.append("Actual vs expected goal involvements: %d actual vs %s expected.\n"
                .formatted(goals + assists, metric(xgi, xgiComplete)));
        out.append(defconSummary(rows));
        return out.toString();
    }

    private static int position(Map<String, Object> row) {
        return row.get("element_type") instanceof Number n ? n.intValue() : 0;
    }

    /**
     * The match's DefCon action count — a raw count, not the pick lists' {@code DC} expected
     * points, hence the different name. Nothing at a position FPL awards none to.
     */
    private static String defconCell(Map<String, Object> row) {
        if (ExpectedPoints.defconThreshold(position(row)) == 0) {
            return "";
        }
        return " | DefCon actions "
                + (row.get("defensive_contribution") instanceof Number n ? n.intValue() : "n/a");
    }

    /**
     * How often the player reached his position's threshold in the window (KAR-58) — the record a
     * named-player comparison of defenders or midfielders quotes. One line per season, each judged
     * at the position he held that season: seasons are never pooled, and a CBIT count is not a
     * CBIRT count. The fill is appended after the resolved season's rows, so the split is positional.
     */
    private static String defconSummary(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int i = 1; i <= rows.size(); i++) {
            if (i == rows.size() || !java.util.Objects.equals(
                    rows.get(i).get("season_code"), rows.get(from).get("season_code"))) {
                out.append(defconSeasonLine(rows.subList(from, i)));
                from = i;
            }
        }
        return out.toString();
    }

    /**
     * Only appearances count and a match with no reading is unknown rather than a miss. The 60+
     * split is printed beside the whole because a five-minute cameo cannot reach the threshold
     * and would otherwise read as a failure to.
     */
    private static String defconSeasonLine(List<Map<String, Object>> rows) {
        int position = position(rows.getFirst());
        int threshold = ExpectedPoints.defconThreshold(position);
        String season = "Defensive contribution, season " + rows.getFirst().get("season_code");
        if (threshold == 0) {
            return position == 1 ? season + ": not awarded to goalkeepers.\n" : "";
        }
        int known = 0, hits = 0, longKnown = 0, longHits = 0, unknown = 0, minutes = 0;
        double actions = 0;
        for (Map<String, Object> r : rows) {
            int played = (int) num(r, "minutes");
            if (played <= 0) {
                continue;
            }
            if (r.get("defensive_contribution") instanceof Number n) {
                boolean hit = n.intValue() >= threshold;
                known++;
                hits += hit ? 1 : 0;
                if (played >= 60) {
                    longKnown++;
                    longHits += hit ? 1 : 0;
                }
                actions += n.doubleValue();
                minutes += played;
            } else {
                unknown++;
            }
        }
        String label = season + " (%s: 2 pts at %d+ %s): ".formatted(
                position == 2 ? "DEF" : position == 3 ? "MID" : "FWD", threshold,
                position == 2 ? "CBIT" : "CBIRT");
        if (known == 0) {
            return label + "no reading in this window — unknown, not zero.\n";
        }
        return label + "reached in %d of %s with a reading (%d of %d at 60+ min) | %.2f actions per 90%s.\n"
                .formatted(hits, plural(known, "appearance"), longHits, longKnown,
                        actions * 90 / minutes,
                        unknown == 0 ? "" : " | " + plural(unknown, "appearance") + " without a reading");
    }

    static String formatTeam(String name, List<Map<String, Object>> fixtures,
                             String currentCode, int fromPrevious) {
        double totalXg = 0, totalXa = 0, totalXgi = 0, totalXga = 0;
        boolean xgComplete = true, xaComplete = true, xgiComplete = true, xgaComplete = true;
        int totalGoals = 0, totalConceded = 0;
        StringBuilder out = new StringBuilder("Expected output for ").append(name)
                .append(" (last ").append(fixtures.size()).append(" fixtures")
                .append(windowLabel(fixtures, currentCode, fromPrevious)).append("):\n");
        for (Map<String, Object> f : fixtures) {
            double xg = num(f, "xg");
            double xa = num(f, "xa");
            double xgi = num(f, "xgi");
            double xga = num(f, "xga");
            xgComplete &= f.get("xg") != null;
            xaComplete &= f.get("xa") != null;
            xgiComplete &= f.get("xgi") != null;
            xgaComplete &= f.get("xga") != null;
            int goals = (int) num(f, "goals");
            int conceded = (int) num(f, "conceded");
            totalXg += xg;
            totalXa += xa;
            totalXgi += xgi;
            totalXga += xga;
            totalGoals += goals;
            totalConceded += conceded;
            out.append("  %sGW%s vs %s %s: xG %s xA %s xGI %s (scored %d) | xGA %s (conceded %d)\n"
                    .formatted(seasonPrefix(f, fromPrevious), f.get("gameweek"), opponent(f),
                            Boolean.TRUE.equals(f.get("was_home")) ? "(H)" : "(A)",
                            metric(f, "xg"), metric(f, "xa"), metric(f, "xgi"), goals,
                            metric(f, "xga"), conceded));
        }
        out.append("\nAttack over window: xG %s | xA %s | xGI %s | Goals %d\n"
                .formatted(metric(totalXg, xgComplete), metric(totalXa, xaComplete),
                        metric(totalXgi, xgiComplete), totalGoals));
        out.append("Defence over window: xGA %s | Goals conceded %d\n"
                .formatted(metric(totalXga, xgaComplete), totalConceded));
        return out.toString();
    }

    /** A team that played in a season the {@code team} table no longer covers joins to null. */
    private static String opponent(Map<String, Object> row) {
        Object opponent = row.get("opponent");
        return opponent == null ? "?" : opponent.toString();
    }

    private static String metric(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? "%.2f".formatted(number.doubleValue()) : "n/a";
    }

    private static String metric(double value, boolean complete) {
        return complete ? "%.2f".formatted(value) : "n/a (partial evidence)";
    }

    private static double num(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? 0.0 : ((Number) v).doubleValue();
    }
}
