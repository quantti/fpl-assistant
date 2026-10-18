package dev.quantti.fplassistant.tools;

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

import dev.quantti.fplassistant.sync.GameweekFinalityReader;
import dev.quantti.fplassistant.sync.OwnedSyncTables;
import dev.quantti.fplassistant.teamxg.UnderstatStore;
import dev.quantti.fplassistant.teamxg.UnderstatParser;

/**
 * Player observations use guarded FPL rows, per-season provenance and stored-match coverage.
 * Team xG/xGA uses complete Understat match totals with its own capture/season provenance;
 * it neither sums player xG nor infers full-match xGA from player exposure.
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
    private final UnderstatStore understat;
    private final TransactionTemplate readTransaction;

    @Autowired
    public ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                              ObjectProvider<Clock> clock, PfsReadSource pfs, PlayerNameSearch names,
                              GameweekFinalityReader finalityReader,
                              PlatformTransactionManager transactionManager, UnderstatStore understat) {
        this(jdbc, seasonResolver, clock, pfs, names, finalityReader, transactionManager, true, understat);
    }

    private ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                               ObjectProvider<Clock> clock, PfsReadSource pfs, PlayerNameSearch names,
                               GameweekFinalityReader finalityReader,
                               PlatformTransactionManager transactionManager, boolean readOnlySnapshot, UnderstatStore understat) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.pfs = pfs;
        this.names = names;
        this.finalityReader = finalityReader;
        this.understat = understat;
        this.readTransaction = readOnlySnapshot ? new TransactionTemplate(transactionManager) : null;
        if (this.readTransaction != null) {
            this.readTransaction.setReadOnly(true);
            this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

    /** Compatibility constructor for focused legacy tests. */
    ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                       ObjectProvider<Clock> clock, PlayerNameSearch names, UnderstatStore understat) {
        this(jdbc, seasonResolver, clock,
                new PfsReadSource(jdbc, "OFF", "", "fpl_fixture_sync", "fpl_player_fixture_evidence"),
                names, new GameweekFinalityReader(jdbc, clock.getIfAvailable(Clock::systemUTC),
                        OwnedSyncTables.defaults()), null, false, understat);
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
            Get direct Understat team expected goals (xG), expected goals against (xGA) and
            actual scores over the last N completed matches, capped at 10, filling from the
            previous season when necessary. Every match names its season and the response
            dates the source capture. These are Understat team metrics, not FPL player sums;
            do not mix them with FPL player xG/xA or describe older-season evidence as current form.
            """)
    public String getTeamExpectedGoals(String teamName, int lastN) {
        return readSnapshot(() -> understatTeamExpectedGoals(teamName, lastN));
    }

    private String understatTeamExpectedGoals(String teamName, int lastN) {
        try {
            var clubs = UnderstatParser.matchingClubs(teamName);
            if (clubs.isEmpty()) return "No Understat club matched: " + teamName;
            if (clubs.size() > 1) return "Multiple Understat clubs matched: " + String.join(", ", clubs)
                    + ". Please specify one club.";
            Instant asOf = clock.instant();
            int year = seasonResolver.resolve().currentSeasonStart().getYear();
            var rows = understat.teamMatches(year, clubs.getFirst(), Math.max(1, Math.min(lastN, MAX_FIXTURES)), asOf);
            if (rows.isEmpty()) return "Understat team xG/xGA unavailable for " + clubs.getFirst()
                    + ": no stored completed matches in " + UnderstatParser.season(year) + " or the previous season.";
            var captures = new java.util.HashMap<String, Instant>();
            var through = new java.util.HashMap<String, Instant>();
            StringBuilder result = new StringBuilder("\n\nTeam " + clubs.getFirst()
                    + " — last " + rows.size() + " completed matches\nSeason | Kickoff | Opponent | Venue | Score | xG | xGA\n");
            double xg = 0;
            double xga = 0;
            for (var row : rows) {
                String code = (String) row.get("season");
                Instant kickoff = ((java.sql.Timestamp) row.get("kickoff_time")).toInstant();
                captures.put(code, ((java.sql.Timestamp) row.get("captured_at")).toInstant());
                through.merge(code, kickoff, (a, b) -> a.isAfter(b) ? a : b);
                xg += ((Number) row.get("xg")).doubleValue();
                xga += ((Number) row.get("xga")).doubleValue();
                result.append(String.format(Locale.UK, "%s | %s | %s | %s | %s-%s | %.2f | %.2f%n",
                        code.replace('-', '/'), UkTime.format(kickoff, "unknown"), row.get("opponent"),
                        Boolean.TRUE.equals(row.get("home")) ? "H" : "A", row.get("goals"), row.get("conceded"),
                        ((Number) row.get("xg")).doubleValue(), ((Number) row.get("xga")).doubleValue()));
            }
            result.append(String.format(Locale.UK, "Total xG %.2f / xGA %.2f; per match %.2f / %.2f.",
                    xg, xga, xg / rows.size(), xga / rows.size()));
            return UnderstatStore.provenance(captures, through) + result;
        } catch (DataAccessException e) {
            log.error("Understat team stats read failed", e);
            return "Understat team xG/xGA is temporarily unavailable (database error).";
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
