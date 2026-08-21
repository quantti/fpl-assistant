package dev.quantti.fplassistant.historical;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;

/**
 * Keeps the season in progress current via football-data.org, upserting on the same natural
 * key {@code (season, home_team_code, away_team_code)} vaastav uses.
 *
 * <p><b>Source precedence:</b> vaastav is authoritative for any completed season; this only
 * ever writes the season the database flags {@code is_current}. The season written is derived
 * from the response itself (each match's {@code season.startDate}), not from
 * {@link SeasonResolver}: the resolver deliberately falls back to a completed season while the
 * current one has no tapas data, and keying the guard on it kept the current season's results
 * unwritten for weeks after kickoff. A response serving any season other than the
 * flagged-current one — the API lagging the calendar over the summer — is skipped whole, so
 * vaastav's completed seasons stay untouched. A later vaastav backfill of a just-completed
 * season upserts the same natural key, an idempotent overwrite with authoritative scores.
 */
@Component
public class FootballDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataSyncService.class);

    private final FootballDataSource source;
    private final SeasonResolver seasonResolver;
    private final JdbcTemplate jdbc;
    private final String tableName;

    public FootballDataSyncService(FootballDataSource source, SeasonResolver seasonResolver,
                                   JdbcTemplate jdbc,
                                   @Value("${fpl.historical.table-name}") String tableName) {
        this.source = source;
        this.seasonResolver = seasonResolver;
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    /** @return number of matches written, for logging/testing — 0 if nothing needed syncing */
    public int sync() {
        SeasonContext season = seasonResolver.resolve();

        String body = source.fetchCurrentSeasonMatches();
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(body);
        if (matches.isEmpty()) {
            log.info("football-data sync: the response carried no matches — nothing to do");
            return 0;
        }

        // The write guard. The season stamped on the rows comes from the payload, and only a
        // response serving the database's flagged-current season is written — anything else
        // (the API still serving the just-finished season over the summer) is skipped whole.
        String responseSeason = matches.getFirst().seasonCode();
        if (!responseSeason.equals(season.currentSeasonCode())) {
            log.info("Skipping football-data sync: the response serves season {} but the"
                    + " database's current season is {} — nothing written",
                    responseSeason, season.currentSeasonCode());
            return 0;
        }

        int written = 0;
        for (FootballDataMatch match : matches) {
            if (!match.isFinished() || match.homeGoals() == null || match.awayGoals() == null) {
                continue;
            }
            upsert(responseSeason, match);
            written++;
        }
        log.info("football-data sync: {} completed matches written for {} ({} total rows in response)",
                written, responseSeason, matches.size());
        return written;
    }

    private void upsert(String season, FootballDataMatch match) {
        int homeCode = FootballDataTeamAliases.resolve(match.homeTeamName());
        int awayCode = FootballDataTeamAliases.resolve(match.awayTeamName());
        jdbc.update("""
                INSERT INTO %s (season, gameweek, home_team_code, away_team_code,
                                 home_team_name, away_team_name, home_goals, away_goals,
                                 kickoff_time, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'football-data')
                ON CONFLICT (season, home_team_code, away_team_code) DO UPDATE SET
                    gameweek = EXCLUDED.gameweek,
                    home_team_name = EXCLUDED.home_team_name,
                    away_team_name = EXCLUDED.away_team_name,
                    home_goals = EXCLUDED.home_goals,
                    away_goals = EXCLUDED.away_goals,
                    kickoff_time = EXCLUDED.kickoff_time,
                    source = 'football-data',
                    ingested_at = NOW()
                """.formatted(tableName), // tableName is config-injected, not user input
                season, match.matchday(), homeCode, awayCode,
                match.homeTeamName(), match.awayTeamName(),
                match.homeGoals(), match.awayGoals(), parseKickoff(match.utcDate()));
    }

    private static Timestamp parseKickoff(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            log.warn("football-data match has an unparseable utcDate '{}', storing as NULL", iso);
            return null;
        }
    }
}
