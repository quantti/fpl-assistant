package dev.quantti.fplassistant.teamxg;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.quantti.fplassistant.tools.ClubRates;
import dev.quantti.fplassistant.tools.UkTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class UnderstatStore {
    public record Capture(String season, Instant capturedAt, List<UnderstatParser.Match> matches) {
        public Capture { matches = List.copyOf(matches); }
    }

    public record Model(ClubRates.Rates rates, Map<String, Instant> capturedAt, Map<String, Instant> through) {
        public Model { capturedAt = Map.copyOf(capturedAt); through = Map.copyOf(through); }
    }

    /** Caller owns the read snapshot. Every captured club contributes to league constants. */
    public Model loadModel(int year, Map<Integer, String> clubs, Instant asOf) {
        String current = UnderstatParser.season(year);
        String previous = UnderstatParser.season(year - 1);
        Map<String, Map<String, ClubRates.SeasonSide>> sides = new HashMap<>();
        Map<String, Instant> captured = new HashMap<>();
        Map<String, Instant> through = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                WITH sides AS (
                    SELECT season,home_team AS club,kickoff_time,home_xg AS xg,away_xg AS xga,true AS home FROM %s
                    UNION ALL
                    SELECT season,away_team AS club,kickoff_time,away_xg AS xg,home_xg AS xga,false AS home FROM %s
                )
                SELECT c.season,c.captured_at,s.club,count(s.club) AS fixtures,MAX(s.kickoff_time) AS through,
                       SUM(s.xg) AS xg,SUM(s.xga) AS xga,count(*) FILTER(WHERE s.home) AS home_fixtures,
                       COALESCE(SUM(s.xg) FILTER(WHERE s.home),0) AS home_xg
                FROM %s c LEFT JOIN sides s ON s.season=c.season AND s.kickoff_time<=?
                WHERE c.season IN (?,?) AND c.captured_at<=?
                GROUP BY c.season,c.captured_at,s.club
                """.formatted(matches, matches, captures), Timestamp.from(asOf), current, previous, Timestamp.from(asOf))) {
            String code = (String) row.get("season");
            captured.put(code, ((Timestamp) row.get("captured_at")).toInstant());
            if (row.get("club") == null) continue;
            Instant latest = ((Timestamp) row.get("through")).toInstant();
            through.merge(code, latest, (a, b) -> a.isAfter(b) ? a : b);
            sides.computeIfAbsent(code, ignored -> new HashMap<>()).put((String) row.get("club"),
                    new ClubRates.SeasonSide(((Number) row.get("fixtures")).intValue(),
                            ((Number) row.get("xg")).doubleValue(), ((Number) row.get("xga")).doubleValue(),
                            ((Number) row.get("home_fixtures")).intValue(), ((Number) row.get("home_xg")).doubleValue()));
        }
        var now = sides.getOrDefault(current, Map.of());
        var prior = sides.getOrDefault(previous, Map.of());
        Set<String> ambiguous = clubs.values().stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, ClubRates.SeasonSide> nowIds = new HashMap<>();
        Map<Integer, ClubRates.SeasonSide> priorIds = new HashMap<>();
        clubs.forEach((id, shortName) -> {
            if (shortName == null || ambiguous.contains(shortName)) return;
            if (now.containsKey(shortName)) nowIds.put(id, now.get(shortName));
            if (prior.containsKey(shortName)) priorIds.put(id, prior.get(shortName));
        });
        return new Model(ClubRates.blend(nowIds, priorIds, ClubRates.league(now, prior)), captured, through);
    }

    public List<Map<String, Object>> teamMatches(int year, String club, int limit, Instant asOf) {
        return jdbc.queryForList("""
                WITH sides AS (
                    SELECT season,match_id,kickoff_time,home_team AS club,away_team AS opponent,true AS home,
                           home_goals AS goals,away_goals AS conceded,home_xg AS xg,away_xg AS xga FROM %s
                    UNION ALL
                    SELECT season,match_id,kickoff_time,away_team AS club,home_team AS opponent,false AS home,
                           away_goals AS goals,home_goals AS conceded,away_xg AS xg,home_xg AS xga FROM %s
                )
                SELECT s.*,c.captured_at FROM sides s JOIN %s c ON c.season=s.season
                WHERE s.club=? AND s.season IN (?,?) AND c.captured_at<=? AND s.kickoff_time<=?
                ORDER BY s.kickoff_time DESC,s.match_id DESC LIMIT ?
                """.formatted(matches, matches, captures), club, UnderstatParser.season(year),
                UnderstatParser.season(year - 1), Timestamp.from(asOf), Timestamp.from(asOf), limit);
    }

    public static String provenance(Map<String, Instant> captures, Map<String, Instant> through) {
        if (captures.isEmpty()) return "Team xG source: Understat unavailable — no stored capture; no FPL fallback.";
        return "Team xG source: Understat; " + captures.keySet().stream().sorted(Comparator.reverseOrder())
                .map(s -> "season " + s.replace('-', '/') + " captured " + UkTime.format(captures.get(s), "unknown")
                        + ", matches through " + UkTime.format(through.get(s), "none"))
                .collect(java.util.stream.Collectors.joining("; ")) + ".";
    }

    private final JdbcTemplate jdbc;
    private final String captures;
    private final String matches;
    private final TransactionTemplate writeTransaction;

    public UnderstatStore(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                          @Value("${fpl.understat.capture-table-name:fpl_understat_capture}") String captures,
                          @Value("${fpl.understat.match-table-name:fpl_understat_match}") String matches) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.matches = matches;
        this.writeTransaction = new TransactionTemplate(transactions);
    }

    /** Capture rows and metadata commit atomically; a partial season is never exposed. */
    public void replace(List<Capture> incoming) {
        writeTransaction.executeWithoutResult(status -> {
            for (Capture capture : incoming.stream().sorted(Comparator.comparing(Capture::season)).toList()) {
                int changed = jdbc.update("""
                        INSERT INTO %s (season,captured_at) VALUES (?,?)
                        ON CONFLICT (season) DO UPDATE SET captured_at=EXCLUDED.captured_at
                        WHERE %s.captured_at<=EXCLUDED.captured_at
                        """.formatted(captures, captures), capture.season(), Timestamp.from(capture.capturedAt()));
                if (changed == 0) continue;
                jdbc.update("DELETE FROM " + matches + " WHERE season=?", capture.season());
                jdbc.batchUpdate("""
                        INSERT INTO %s (season,match_id,kickoff_time,home_team,away_team,
                                        home_goals,away_goals,home_xg,away_xg)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """.formatted(matches), capture.matches().stream().map(m -> new Object[]{
                                capture.season(), m.id(), Timestamp.from(m.kickoff()), m.home(), m.away(),
                                m.homeGoals(), m.awayGoals(), m.homeXg(), m.awayXg()}).toList());
            }
        });
    }
}
