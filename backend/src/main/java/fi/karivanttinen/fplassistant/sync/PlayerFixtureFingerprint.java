package fi.karivanttinen.fplassistant.sync;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import fi.karivanttinen.fplassistant.sync.HistoryParser.Component;
import fi.karivanttinen.fplassistant.sync.HistoryParser.HistoryRow;

/** Semantic fingerprint for a parsed row; fetch time and decimal presentation are not row values. */
public final class PlayerFixtureFingerprint {

    private PlayerFixtureFingerprint() {
    }

    public static String of(HistoryRow row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, row.seasonId(), row.seasonCode(), row.fixtureId(), row.playerId(), row.gameweek(),
                    row.playerTeamId(), row.opponentTeamId(), row.wasHome(), row.kickoffTime(), row.minutes(),
                    row.totalPoints(), row.bonus(), row.bps(), row.goalsScored(), row.assists(),
                    decimal(row.expectedGoals()), decimal(row.expectedAssists()),
                    decimal(row.expectedGoalInvolvements()), row.cleanSheets(), row.goalsConceded(),
                    row.ownGoals(), row.penaltiesSaved(), row.penaltiesMissed(), row.saves(),
                    decimal(row.expectedGoalsConceded()), row.yellowCards(), row.redCards(),
                    decimal(row.influence()), decimal(row.creativity()), decimal(row.threat()),
                    decimal(row.ictIndex()), row.value(), row.selected(), row.transfersIn(),
                    row.transfersOut(), row.starts(), row.clearancesBlocksInterceptions(), row.recoveries(),
                    row.tackles(), row.defensiveContribution(), row.presenceMask());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * PostgreSQL expression for a semantic fingerprint of the named PFS values. Optional values
     * are represented as NULL when their evidence bit is absent, so retained/default physical
     * values cannot affect the digest. JSONB array encoding is unambiguous and uses built-in SHA-256.
     */
    public static String physicalSqlExpression(String pfsAlias, String seasonAlias,
                                               String presenceExpression) {
        List<String> values = List.of(
                pfsAlias + ".season_id", seasonAlias + ".code", pfsAlias + ".fixture_id",
                pfsAlias + ".player_id", pfsAlias + ".gameweek", pfsAlias + ".player_team_id",
                pfsAlias + ".opponent_team_id", pfsAlias + ".was_home",
                pfsAlias + ".kickoff_time AT TIME ZONE 'UTC'", pfsAlias + ".minutes",
                pfsAlias + ".total_points", pfsAlias + ".bonus", pfsAlias + ".bps",
                pfsAlias + ".goals_scored", pfsAlias + ".assists",
                optional(pfsAlias + ".expected_goals", presenceExpression, Component.EXPECTED_GOALS),
                optional(pfsAlias + ".expected_assists", presenceExpression, Component.EXPECTED_ASSISTS),
                optional(pfsAlias + ".expected_goal_involvements", presenceExpression,
                        Component.EXPECTED_GOAL_INVOLVEMENTS),
                pfsAlias + ".clean_sheets", pfsAlias + ".goals_conceded", pfsAlias + ".own_goals",
                pfsAlias + ".penalties_saved", pfsAlias + ".penalties_missed", pfsAlias + ".saves",
                optional(pfsAlias + ".expected_goals_conceded", presenceExpression,
                        Component.EXPECTED_GOALS_CONCEDED),
                pfsAlias + ".yellow_cards", pfsAlias + ".red_cards",
                optional(pfsAlias + ".influence", presenceExpression, Component.INFLUENCE),
                optional(pfsAlias + ".creativity", presenceExpression, Component.CREATIVITY),
                optional(pfsAlias + ".threat", presenceExpression, Component.THREAT),
                optional(pfsAlias + ".ict_index", presenceExpression, Component.ICT_INDEX),
                pfsAlias + ".value", pfsAlias + ".selected", pfsAlias + ".transfers_in",
                pfsAlias + ".transfers_out", pfsAlias + ".starts",
                optional(pfsAlias + ".clearances_blocks_interceptions", presenceExpression,
                        Component.CLEARANCES_BLOCKS_INTERCEPTIONS),
                optional(pfsAlias + ".recoveries", presenceExpression, Component.RECOVERIES),
                optional(pfsAlias + ".tackles", presenceExpression, Component.TACKLES),
                optional(pfsAlias + ".defensive_contribution", presenceExpression,
                        Component.DEFENSIVE_CONTRIBUTION),
                presenceExpression);
        return "encode(sha256(convert_to(jsonb_build_array(" + String.join(",", values)
                + ")::text, 'UTF8')), 'hex')";
    }

    private static String optional(String column, String presenceExpression, Component component) {
        return "CASE WHEN (" + presenceExpression + " & " + component.bit()
                + ") <> 0 THEN " + column + " END";
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array());
            digest.update(bytes);
        }
    }
}
