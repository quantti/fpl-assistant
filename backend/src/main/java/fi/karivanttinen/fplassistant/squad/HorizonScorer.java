package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.tools.ExpectedPoints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns a per-90 expectation into what a player is worth over the next N fixtures.
 *
 * <p><b>Expected minutes are the guard the squad builder depends on.</b> Maximising per-90
 * points under a budget buys cheap players whose rate looks adequate and whose minutes do not
 * exist — the same failure as a placeholder, wearing a real name. Minutes divided by the club's
 * fixtures over the same window penalises rotation by construction.
 *
 * <p>The horizon is <b>summed, not averaged</b>. A double gameweek is literally two fixtures and
 * comes out right without special handling; a blank is no fixture and comes out as nothing.
 *
 * <p>The fixture adjustment is coarse: one multiplier on the whole score, where a fixture should
 * move the clean-sheet term more than the attacking terms. Issue #47 replaces it with a team
 * xG/xGC projection.
 */
@Component
public class HorizonScorer {

    /** Expected minutes per club fixture below which a player may not start. */
    public static final double XI_MIN_EXPECTED_MINUTES = 60;

    /** Score multiplier per FDR step away from a neutral 3. */
    public static final double FDR_SWING = 0.1;

    private static final int NEUTRAL_FDR = 3;

    private final JdbcTemplate jdbc;

    public HorizonScorer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Minutes the player can be expected to play in one fixture. Capped at a full match: the
     * fixture count comes from the player's own rows, and a mismatch must not make anyone worth
     * more than ninety minutes.
     */
    public static double expectedMinutes(SquadCandidate candidate) {
        if (candidate.teamFixturesInWindow() <= 0) {
            return 0;
        }
        return Math.min(90.0, candidate.minutesInWindow() / candidate.teamFixturesInWindow());
    }

    public static double fdrAdjust(int fdr) {
        return 1 + (NEUTRAL_FDR - fdr) * FDR_SWING;
    }

    /**
     * Expected FPL points across the given fixtures. A body scores nothing by definition — he is
     * bought for his price, and asserting anything else about him is what the tier exists to
     * prevent.
     */
    public static double score(SquadCandidate candidate, List<Integer> fixtureDifficulties) {
        if (candidate.tier() == SquadCandidate.Tier.BODY || fixtureDifficulties.isEmpty()) {
            return 0;
        }
        double perFixture = ExpectedPoints.perNinety(candidate.metrics())
                * expectedMinutes(candidate) / 90.0;
        double total = 0;
        for (int fdr : fixtureDifficulties) {
            total += perFixture * fdrAdjust(fdr);
        }
        return total;
    }

    /**
     * Each club's fixture difficulty across the next {@code horizon} gameweeks, in kickoff
     * order.
     *
     * <p>Bounded by <b>gameweek range</b> ({@code gameweek < from + horizon}), not by a per-club
     * fixture count. A club with a double inside the window contributes two entries for that one
     * gameweek — what makes a double gameweek score twice — while every club is still scored over
     * the identical stretch of calendar. Capping by fixture count instead (an earlier version of
     * this method did) silently shrinks a doubled club's effective horizon by a gameweek and
     * erases the double's advantage: at {@code horizon=5} a club with no doubles would keep
     * GW1-5, but a club doubling in GW3 would keep GW1,2,3a,3b,4 — five fixtures covering only
     * four gameweeks, with GW5 dropped — which is exactly backwards for a design whose whole
     * point is that summing, not averaging, is supposed to reward the double.
     *
     * <p>{@code finished = false} excludes a fixture that has already been played. {@code
     * is_current} stays true for a gameweek's whole multi-day span, so without this filter a call
     * made after that gameweek's first kickoff would count an already-decided match as one of a
     * player's future fixtures — consuming a horizon slot that can no longer return anything and
     * skewing that club against one whose corresponding fixture has not kicked off yet. Same
     * filter {@code PickTools.avgFdrByTeam} already applies for the same reason.
     *
     * <p>Resolved once for every club rather than once per player: a pool of six hundred players
     * shares at most twenty clubs.
     */
    public Map<Integer, List<Integer>> fixtureDifficulties(int seasonId, int horizon) {
        Integer from = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                    (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                    (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                """, Integer.class, seasonId, seasonId, seasonId);
        if (from == null) {
            return Map.of();
        }
        int upTo = from + horizon;

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT team_id, fdr FROM (
                    SELECT id AS fixture_id, team_h AS team_id, team_h_difficulty AS fdr,
                           gameweek, kickoff_time
                    FROM fixture
                    WHERE season_id = ? AND gameweek >= ? AND gameweek < ? AND finished = false
                    UNION ALL
                    SELECT id, team_a, team_a_difficulty, gameweek, kickoff_time
                    FROM fixture
                    WHERE season_id = ? AND gameweek >= ? AND gameweek < ? AND finished = false
                ) f
                ORDER BY team_id, gameweek, kickoff_time, fixture_id
                """, seasonId, from, upTo, seasonId, from, upTo);

        Map<Integer, List<Integer>> byTeam = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int teamId = ((Number) row.get("team_id")).intValue();
            Object fdr = row.get("fdr");
            byTeam.computeIfAbsent(teamId, key -> new ArrayList<>())
                    .add(fdr instanceof Number number ? number.intValue() : NEUTRAL_FDR);
        }
        return byTeam;
    }
}
