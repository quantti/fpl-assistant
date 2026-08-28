package fi.karivanttinen.fplassistant.tools;

import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the season the tools should query.
 *
 * <p>The season flagged {@code is_current} is created before its data is synced — a new season row
 * exists for weeks while every FPL table is still empty. Filtering on {@code is_current} alone
 * therefore returns nothing at all across the changeover. This resolver prefers the current season
 * once it actually holds data and otherwise falls back to the most recent season that does, so the
 * assistant stays useful; {@link SeasonContext#provenance()} is what keeps the fallback honest.
 */
@Component
public class SeasonResolver {

    private final JdbcTemplate jdbc;

    public SeasonResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SeasonContext resolve() {
        // Named rather than left to queryForMap's own message: the table is tapas-owned, and
        // "Incorrect result size: expected 1, actual 2" sends whoever reads it into this code
        // instead of into the season table, which is the only place the problem can be fixed.
        List<Map<String, Object>> flagged = jdbc.queryForList(
                "SELECT id, code, start_date FROM season WHERE is_current = true ORDER BY id");
        if (flagged.size() != 1) {
            throw new IncorrectResultSizeDataAccessException(
                    "Exactly one season must be flagged is_current in the tapas season table;"
                            + " found " + flagged.size()
                            + (flagged.isEmpty() ? ". Flag the season being played."
                                    : ": " + flagged.stream().map(row -> String.valueOf(row.get("code")))
                                            .toList() + ". Clear the flag on all but one."),
                    1, flagged.size());
        }
        Map<String, Object> current = flagged.getFirst();

        // A season counts as populated only once both gameweeks and players have landed; a sync
        // that is midway through would otherwise be picked while most tools still see nothing.
        List<Map<String, Object>> populated = jdbc.queryForList("""
                SELECT s.id, s.code, s.is_current
                FROM season s
                WHERE EXISTS (SELECT 1 FROM gameweek g WHERE g.season_id = s.id)
                  AND EXISTS (SELECT 1 FROM player p WHERE p.season_id = s.id)
                ORDER BY s.is_current DESC, s.start_date DESC
                LIMIT 1
                """);

        String currentCode = (String) current.get("code");
        LocalDate currentStart = ((Date) current.get("start_date")).toLocalDate();

        if (populated.isEmpty()) {
            // Nothing anywhere yet. Report the current season so tools can say which one is empty.
            int currentId = ((Number) current.get("id")).intValue();
            return new SeasonContext(currentId, currentCode, true, currentCode, currentStart);
        }

        Map<String, Object> chosen = populated.getFirst();
        return new SeasonContext(
                ((Number) chosen.get("id")).intValue(),
                (String) chosen.get("code"),
                Boolean.TRUE.equals(chosen.get("is_current")),
                currentCode,
                currentStart);
    }

    /**
     * The most recent season before the given one that actually holds player data. Used to show
     * last season's statistics next to the current season's once the current season holds data —
     * the early-season weighting the system prompt describes needs both on the table at once.
     *
     * <p>The {@code EXISTS} is the same discipline as {@link #resolve()}: a season row can sit
     * in the table for weeks with nothing synced against it, and picking that one by start date
     * alone would drop last season's evidence off the card without a word.
     */
    public Optional<SeasonContext> previousSeason(SeasonContext of) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.id, s.code FROM season s
                WHERE s.start_date < (SELECT start_date FROM season WHERE id = ?)
                  AND EXISTS (SELECT 1 FROM player p WHERE p.season_id = s.id)
                ORDER BY s.start_date DESC
                LIMIT 1
                """, of.seasonId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> r = rows.getFirst();
        return Optional.of(new SeasonContext(
                ((Number) r.get("id")).intValue(), (String) r.get("code"), false,
                of.currentSeasonCode(), of.currentSeasonStart()));
    }

    /**
     * Gameweeks whose fixtures are all played and already carry a synced record. Counting
     * distinct gameweeks in {@code player_fixture_stats} instead would count a gameweek in
     * progress the moment its first fixture syncs, flipping a caller's sample-size switch a week
     * early.
     *
     * <p>Shared by {@code PickTools} and {@code CandidatePool}, which both need this count to
     * decide whether to trust the season being played or fall back to the previous one.
     */
    public int completedGameweeks(int seasonId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM gameweek g
                WHERE g.season_id = ? AND g.finished = true
                  AND EXISTS (SELECT 1 FROM player_fixture_stats pfs
                              WHERE pfs.season_id = g.season_id AND pfs.gameweek = g.id)
                """, Integer.class, seasonId);
        return count == null ? 0 : count;
    }

    /**
     * The seasons a ranking's metrics come from: the resolved season, and the one before it when
     * the resolved season is being played. See {@link MetricSeasons}.
     */
    public MetricSeasons metricSeasons(SeasonContext season) {
        Optional<SeasonContext> previous = season.currentSeason()
                ? previousSeason(season) : Optional.empty();
        return new MetricSeasons(season, previous, completedGameweeks(season.seasonId()),
                latestGameweek(season.seasonId()));
    }

    /**
     * The newest gameweek the underlying data actually reaches. Printed so a stalled tapas sync
     * shows as visibly old data rather than ranking silently on weeks-old numbers.
     */
    public int latestGameweek(int seasonId) {
        Integer gw = jdbc.queryForObject(
                "SELECT COALESCE(max(gameweek), 0) FROM player_fixture_stats WHERE season_id = ?",
                Integer.class, seasonId);
        return gw == null ? 0 : gw;
    }

    /**
     * Whether the previous season is a better source of metrics than the season being played,
     * and its context if so. "Better" is not merely "has any data at all": a previous season
     * with one synced gameweek is not evidence to prefer over this season's four, so the
     * fallback only fires when the previous season actually holds more completed evidence than
     * {@code played} — the season being played's own count, already known to the caller.
     *
     * <p>{@code PickTools} and {@code CandidatePool} stated this rule independently, and one of
     * them lost the comparison against {@code played} in a later edit and fell back
     * unconditionally whenever the season being played was thin — bridging to a previous season
     * that was thinner still. It lives here once now so the two cannot drift apart again.
     *
     * @param played completed gameweeks the caller has already counted for {@code season}
     * @param minSampleGameweeks completed gameweeks {@code season} needs before its own numbers
     *        are preferred outright, without even consulting the previous season
     */
    public Optional<SeasonContext> betterMetricSeason(SeasonContext season, int played,
                                                       int minSampleGameweeks) {
        if (played >= minSampleGameweeks) {
            return Optional.empty();
        }
        Optional<SeasonContext> previous = previousSeason(season);
        if (previous.isPresent() && completedGameweeks(previous.get().seasonId()) > played) {
            return previous;
        }
        return Optional.empty();
    }
}
