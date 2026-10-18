package dev.quantti.fplassistant.teamxg;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

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

    /** All fetched seasons commit together; a failed replacement never exposes a partial capture. */
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
