package fi.karivanttinen.fplassistant.historical;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;

/**
 * Keeps the season in progress current via football-data.org, upserting on the same natural
 * key {@code (season, home_team_code, away_team_code)} vaastav uses.
 *
 * <p><b>Source precedence:</b> vaastav is authoritative for any completed season; this only
 * ever writes the season currently being played. Enforced by checking
 * {@link SeasonContext#currentSeason()} before syncing anything — {@code SeasonResolver} falls
 * back to the most recent season that actually holds data when the flagged-current season is
 * still empty (the same changeover problem every other tool in this codebase handles), and
 * writing football-data results into that fallback would silently overwrite vaastav's
 * authoritative backfill for an already-completed season. If the current season has no data
 * yet at all, this is a no-op rather than an error — there is nothing to sync.
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
        if (!season.currentSeason()) {
            log.info("Skipping football-data sync: the resolved season ({}) is a completed"
                    + " fallback, not the season in progress — nothing for football-data to update", season.code());
            return 0;
        }

        String body = source.fetchCurrentSeasonMatches();
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(body);
        int written = 0;
        for (FootballDataMatch match : matches) {
            if (!match.isFinished() || match.homeGoals() == null || match.awayGoals() == null) {
                continue;
            }
            upsert(season.code(), match);
            written++;
        }
        log.info("football-data sync: {} completed matches written for {} ({} total rows in response)",
                written, season.code(), matches.size());
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
