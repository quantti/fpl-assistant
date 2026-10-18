package dev.quantti.fplassistant.teamxg;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UnderstatSchema {
    private final JdbcTemplate jdbc;
    private final String captures;
    private final String matches;

    public UnderstatSchema(JdbcTemplate jdbc,
                           @Value("${fpl.understat.capture-table-name:fpl_understat_capture}") String captures,
                           @Value("${fpl.understat.match-table-name:fpl_understat_match}") String matches) {
        this.jdbc = jdbc;
        this.captures = captures;
        this.matches = matches;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + captures + " (season VARCHAR(7) PRIMARY KEY, captured_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(7) NOT NULL REFERENCES %s(season) ON DELETE CASCADE,
                    match_id BIGINT NOT NULL CHECK (match_id>0), kickoff_time TIMESTAMPTZ NOT NULL,
                    home_team VARCHAR(3) NOT NULL, away_team VARCHAR(3) NOT NULL,
                    home_goals SMALLINT NOT NULL CHECK (home_goals>=0),
                    away_goals SMALLINT NOT NULL CHECK (away_goals>=0),
                    home_xg NUMERIC NOT NULL CHECK (home_xg BETWEEN 0 AND 100),
                    away_xg NUMERIC NOT NULL CHECK (away_xg BETWEEN 0 AND 100),
                    PRIMARY KEY (season,match_id), UNIQUE (season,home_team,away_team),
                    CHECK (home_team<>away_team)
                )
                """.formatted(matches, captures));
        jdbc.execute("ALTER TABLE " + captures + " ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE " + matches + " ENABLE ROW LEVEL SECURITY");
    }
}
