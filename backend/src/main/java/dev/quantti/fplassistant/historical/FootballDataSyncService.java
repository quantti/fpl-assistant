package dev.quantti.fplassistant.historical;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * from the response itself (each match's own {@code season.startDate}), not from
 * {@link SeasonResolver}: the resolver deliberately falls back to a completed season while the
 * current one has no tapas data, and keying the guard on it kept the current season's results
 * unwritten for weeks after kickoff. The guard is applied <b>per match</b>, and each row is
 * stamped with that match's own season — a response mixing two seasons (a rollover, a filtered
 * fetch) writes only the flagged-current half, so vaastav's completed seasons stay untouched.
 * A later vaastav backfill of a just-completed season upserts the same natural key, an
 * idempotent overwrite with authoritative scores.
 *
 * <p>Seasons are matched on their start year rather than by string equality, so a
 * {@code season.code} written as {@code 2026/27} or {@code 2026-2027} still compares equal to
 * the {@code 2026-27} derived from the payload. Without that, a format change on either side
 * would turn the sync into a permanent silent no-op — the same weeks-of-stale-results failure,
 * only harder to see.
 */
@Component
public class FootballDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataSyncService.class);

    private static final Pattern YEAR = Pattern.compile("\\d{4}");

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

        // Nothing the API can serve before kickoff belongs in the database: it is still on the
        // just-finished season, which vaastav owns. Fetching anyway would burn a request per
        // cold start all summer — and because a skipped sync writes nothing, StartupSyncRunner's
        // staleness marker never advances, so every restart would fetch again.
        if (LocalDate.now().isBefore(season.currentSeasonStart())) {
            log.info("Skipping football-data sync: season {} has not kicked off yet (starts {})",
                    season.currentSeasonCode(), season.currentSeasonStart());
            return 0;
        }

        String body = source.fetchCurrentSeasonMatches();
        List<FootballDataMatch> matches = FootballDataResponseParser.parse(body);
        if (matches.isEmpty()) {
            log.info("football-data sync: the response carried no matches — nothing to do");
            return 0;
        }

        Integer currentYear = seasonStartYear(season.currentSeasonCode());
        if (currentYear == null) {
            log.warn("Skipping football-data sync: the database's current season code '{}' carries"
                    + " no four-digit year, so it cannot be compared against the response",
                    season.currentSeasonCode());
            return 0;
        }

        // The write guard, per match: only matches belonging to the database's flagged-current
        // season are written, each stamped with its own season. A response serving the
        // just-finished season (the API lagging the calendar) contributes nothing.
        int written = 0;
        int wrongSeason = 0;
        for (FootballDataMatch match : matches) {
            if (!currentYear.equals(seasonStartYear(match.seasonCode()))) {
                wrongSeason++;
                continue;
            }
            if (!match.isFinished() || match.homeGoals() == null || match.awayGoals() == null) {
                continue;
            }
            upsert(match.seasonCode(), match);
            written++;
        }
        if (written == 0 && wrongSeason > 0) {
            // Loud, because the shape of a permanent no-op (a season-code format change on
            // either side) and the shape of a harmless summer skip are identical from here.
            log.warn("football-data sync wrote nothing: all {} matches in the response belong to a"
                    + " season other than {} — if this repeats after kickoff, check that the"
                    + " season codes on both sides still agree", wrongSeason, season.currentSeasonCode());
            return 0;
        }
        log.info("football-data sync: {} completed matches written for {} ({} total rows in"
                + " response, {} from another season)",
                written, season.currentSeasonCode(), matches.size(), wrongSeason);
        return written;
    }

    /**
     * The four-digit year a season code starts with, or {@code null} if it has none. Compared
     * instead of the codes themselves so {@code 2026-27}, {@code 2026/27} and {@code 2026-2027}
     * all name the same season.
     */
    private static Integer seasonStartYear(String code) {
        if (code == null) {
            return null;
        }
        Matcher m = YEAR.matcher(code);
        return m.find() ? Integer.valueOf(m.group()) : null;
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
