package fi.karivanttinen.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.TeamRow;

/** Named-column writers shared by the scheduled catalogue path and the incremental compatibility path. */
final class CatalogueCompatibilityRows {

    private CatalogueCompatibilityRows() { }

    static int writeTeams(JdbcTemplate jdbc, List<TeamRow> rows, Instant publicationTime,
                          boolean changedOnly) {
        String sql = """
                INSERT INTO team (id, season_id, code, name, short_name, strength,
                    strength_overall_home, strength_overall_away, strength_attack_home,
                    strength_attack_away, strength_defence_home, strength_defence_away, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id, season_id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name,
                    short_name=EXCLUDED.short_name, strength=EXCLUDED.strength,
                    strength_overall_home=EXCLUDED.strength_overall_home,
                    strength_overall_away=EXCLUDED.strength_overall_away,
                    strength_attack_home=EXCLUDED.strength_attack_home,
                    strength_attack_away=EXCLUDED.strength_attack_away,
                    strength_defence_home=EXCLUDED.strength_defence_home,
                    strength_defence_away=EXCLUDED.strength_defence_away, updated_at=EXCLUDED.updated_at
                """ + (changedOnly ? """
                WHERE (team.code, team.name, team.short_name, team.strength,
                       team.strength_overall_home, team.strength_overall_away,
                       team.strength_attack_home, team.strength_attack_away,
                       team.strength_defence_home, team.strength_defence_away)
                  IS DISTINCT FROM
                      (EXCLUDED.code, EXCLUDED.name, EXCLUDED.short_name, EXCLUDED.strength,
                       EXCLUDED.strength_overall_home, EXCLUDED.strength_overall_away,
                       EXCLUDED.strength_attack_home, EXCLUDED.strength_attack_away,
                       EXCLUDED.strength_defence_home, EXCLUDED.strength_defence_away)
                """ : "");
        return batch(jdbc, sql, rows.stream().map(row -> new Object[]{row.id(), row.seasonId(), row.code(),
                row.name(), row.shortName(), field(row.fields(), "strength"),
                field(row.fields(), "strength_overall_home"), field(row.fields(), "strength_overall_away"),
                field(row.fields(), "strength_attack_home"), field(row.fields(), "strength_attack_away"),
                field(row.fields(), "strength_defence_home"), field(row.fields(), "strength_defence_away"),
                timestamp(publicationTime)}).toList());
    }

    static int writePlayers(JdbcTemplate jdbc, List<PlayerRow> rows, Instant publicationTime,
                            boolean changedOnly) {
        String sql = """
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                    element_type, now_cost, status, news, news_added, total_points, minutes,
                    goals_scored, assists, clean_sheets, goals_conceded, own_goals, penalties_saved,
                    penalties_missed, yellow_cards, red_cards, saves, bonus, bps, expected_goals,
                    expected_assists, expected_goal_involvements, expected_goals_conceded, influence,
                    creativity, threat, ict_index, selected_by_percent, form, points_per_game, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id, season_id) DO UPDATE SET team_id=EXCLUDED.team_id,
                    first_name=EXCLUDED.first_name, second_name=EXCLUDED.second_name,
                    web_name=EXCLUDED.web_name, element_type=EXCLUDED.element_type,
                    now_cost=EXCLUDED.now_cost, status=EXCLUDED.status, news=EXCLUDED.news,
                    news_added=EXCLUDED.news_added, total_points=EXCLUDED.total_points,
                    minutes=EXCLUDED.minutes, goals_scored=EXCLUDED.goals_scored,
                    assists=EXCLUDED.assists, clean_sheets=EXCLUDED.clean_sheets,
                    goals_conceded=EXCLUDED.goals_conceded, own_goals=EXCLUDED.own_goals,
                    penalties_saved=EXCLUDED.penalties_saved, penalties_missed=EXCLUDED.penalties_missed,
                    yellow_cards=EXCLUDED.yellow_cards, red_cards=EXCLUDED.red_cards,
                    saves=EXCLUDED.saves, bonus=EXCLUDED.bonus, bps=EXCLUDED.bps,
                    expected_goals=EXCLUDED.expected_goals, expected_assists=EXCLUDED.expected_assists,
                    expected_goal_involvements=EXCLUDED.expected_goal_involvements,
                    expected_goals_conceded=EXCLUDED.expected_goals_conceded,
                    influence=EXCLUDED.influence, creativity=EXCLUDED.creativity, threat=EXCLUDED.threat,
                    ict_index=EXCLUDED.ict_index, selected_by_percent=EXCLUDED.selected_by_percent,
                    form=EXCLUDED.form, points_per_game=EXCLUDED.points_per_game, updated_at=EXCLUDED.updated_at
                """ + (changedOnly ? """
                WHERE (player.team_id, player.first_name, player.second_name, player.web_name,
                       player.element_type, player.now_cost, player.status, player.news, player.news_added,
                       player.total_points, player.minutes, player.goals_scored, player.assists,
                       player.clean_sheets, player.goals_conceded, player.own_goals, player.penalties_saved,
                       player.penalties_missed, player.yellow_cards, player.red_cards, player.saves,
                       player.bonus, player.bps, player.expected_goals, player.expected_assists,
                       player.expected_goal_involvements, player.expected_goals_conceded, player.influence,
                       player.creativity, player.threat, player.ict_index, player.selected_by_percent,
                       player.form, player.points_per_game)
                  IS DISTINCT FROM
                      (EXCLUDED.team_id, EXCLUDED.first_name, EXCLUDED.second_name, EXCLUDED.web_name,
                       EXCLUDED.element_type, EXCLUDED.now_cost, EXCLUDED.status, EXCLUDED.news,
                       EXCLUDED.news_added, EXCLUDED.total_points, EXCLUDED.minutes, EXCLUDED.goals_scored,
                       EXCLUDED.assists, EXCLUDED.clean_sheets, EXCLUDED.goals_conceded, EXCLUDED.own_goals,
                       EXCLUDED.penalties_saved, EXCLUDED.penalties_missed, EXCLUDED.yellow_cards,
                       EXCLUDED.red_cards, EXCLUDED.saves, EXCLUDED.bonus, EXCLUDED.bps,
                       EXCLUDED.expected_goals, EXCLUDED.expected_assists,
                       EXCLUDED.expected_goal_involvements, EXCLUDED.expected_goals_conceded,
                       EXCLUDED.influence, EXCLUDED.creativity, EXCLUDED.threat, EXCLUDED.ict_index,
                       EXCLUDED.selected_by_percent, EXCLUDED.form, EXCLUDED.points_per_game)
                """ : "");
        return batch(jdbc, sql, rows.stream().map(row -> {
            Map<String, Object> fields = row.fields();
            return new Object[]{row.id(), row.seasonId(), row.teamId(), field(fields, "first_name"),
                    field(fields, "second_name"), row.webName(), row.elementType(), row.nowCost(),
                    field(fields, "status"), field(fields, "news"), sqlTime(field(fields, "news_added")),
                    field(fields, "total_points"), field(fields, "minutes"), field(fields, "goals_scored"),
                    field(fields, "assists"), field(fields, "clean_sheets"), field(fields, "goals_conceded"),
                    field(fields, "own_goals"), field(fields, "penalties_saved"), field(fields, "penalties_missed"),
                    field(fields, "yellow_cards"), field(fields, "red_cards"), field(fields, "saves"),
                    field(fields, "bonus"), field(fields, "bps"), field(fields, "expected_goals"),
                    field(fields, "expected_assists"), field(fields, "expected_goal_involvements"),
                    field(fields, "expected_goals_conceded"), field(fields, "influence"),
                    field(fields, "creativity"), field(fields, "threat"), field(fields, "ict_index"),
                    field(fields, "selected_by_percent"), field(fields, "form"),
                    field(fields, "points_per_game"), timestamp(publicationTime)};
        }).toList());
    }

    static int writeGameweeks(JdbcTemplate jdbc, List<GameweekRow> rows, Instant publicationTime,
                              boolean changedOnly) {
        String sql = """
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                    is_current, is_next, average_entry_score, highest_score, transfers_made,
                    most_selected, most_transferred_in, most_captained, most_vice_captained,
                    top_element, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id, season_id) DO UPDATE SET name=EXCLUDED.name,
                    deadline_time=EXCLUDED.deadline_time, finished=EXCLUDED.finished,
                    data_checked=EXCLUDED.data_checked, is_current=EXCLUDED.is_current,
                    is_next=EXCLUDED.is_next, average_entry_score=EXCLUDED.average_entry_score,
                    highest_score=EXCLUDED.highest_score, transfers_made=EXCLUDED.transfers_made,
                    most_selected=EXCLUDED.most_selected, most_transferred_in=EXCLUDED.most_transferred_in,
                    most_captained=EXCLUDED.most_captained,
                    most_vice_captained=EXCLUDED.most_vice_captained,
                    top_element=EXCLUDED.top_element, updated_at=EXCLUDED.updated_at
                """ + (changedOnly ? """
                WHERE (gameweek.name, gameweek.deadline_time, gameweek.finished, gameweek.data_checked,
                       gameweek.is_current, gameweek.is_next, gameweek.average_entry_score,
                       gameweek.highest_score, gameweek.transfers_made, gameweek.most_selected,
                       gameweek.most_transferred_in, gameweek.most_captained,
                       gameweek.most_vice_captained, gameweek.top_element)
                  IS DISTINCT FROM
                      (EXCLUDED.name, EXCLUDED.deadline_time, EXCLUDED.finished, EXCLUDED.data_checked,
                       EXCLUDED.is_current, EXCLUDED.is_next, EXCLUDED.average_entry_score,
                       EXCLUDED.highest_score, EXCLUDED.transfers_made, EXCLUDED.most_selected,
                       EXCLUDED.most_transferred_in, EXCLUDED.most_captained,
                       EXCLUDED.most_vice_captained, EXCLUDED.top_element)
                """ : "");
        return batch(jdbc, sql, rows.stream().map(row -> new Object[]{row.id(), row.seasonId(), row.name(),
                timestamp(row.deadline()), row.finished(), row.dataChecked(), row.current(), row.next(),
                field(row.fields(), "average_entry_score"), field(row.fields(), "highest_score"),
                field(row.fields(), "transfers_made"), field(row.fields(), "most_selected"),
                field(row.fields(), "most_transferred_in"), field(row.fields(), "most_captained"),
                field(row.fields(), "most_vice_captained"), field(row.fields(), "top_element"),
                timestamp(publicationTime)}).toList());
    }

    static List<Integer> writeFixtures(JdbcTemplate jdbc, List<FixtureRow> rows,
                                       Instant publicationTime, boolean changedOnly) {
        if (rows.isEmpty()) return List.of();
        String values = String.join(",\n", java.util.Collections.nCopies(rows.size(),
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)"));
        String sql = """
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a, team_h_score,
                    team_a_score, team_h_difficulty, team_a_difficulty, kickoff_time, started,
                    finished, finished_provisional, minutes, stats, updated_at)
                VALUES %s
                ON CONFLICT (id, season_id) DO UPDATE SET gameweek=EXCLUDED.gameweek,
                    code=EXCLUDED.code, team_h=EXCLUDED.team_h, team_a=EXCLUDED.team_a,
                    team_h_score=EXCLUDED.team_h_score, team_a_score=EXCLUDED.team_a_score,
                    team_h_difficulty=EXCLUDED.team_h_difficulty,
                    team_a_difficulty=EXCLUDED.team_a_difficulty, kickoff_time=EXCLUDED.kickoff_time,
                    started=EXCLUDED.started, finished=EXCLUDED.finished,
                    finished_provisional=EXCLUDED.finished_provisional, minutes=EXCLUDED.minutes,
                    stats=EXCLUDED.stats, updated_at=EXCLUDED.updated_at
                """.formatted(values) + (changedOnly ? """
                WHERE (fixture.gameweek, fixture.code, fixture.team_h, fixture.team_a,
                       fixture.team_h_score, fixture.team_a_score, fixture.team_h_difficulty,
                       fixture.team_a_difficulty, fixture.kickoff_time, fixture.started,
                       fixture.finished, fixture.finished_provisional, fixture.minutes, fixture.stats)
                  IS DISTINCT FROM
                      (EXCLUDED.gameweek, EXCLUDED.code, EXCLUDED.team_h, EXCLUDED.team_a,
                       EXCLUDED.team_h_score, EXCLUDED.team_a_score, EXCLUDED.team_h_difficulty,
                       EXCLUDED.team_a_difficulty, EXCLUDED.kickoff_time, EXCLUDED.started,
                       EXCLUDED.finished, EXCLUDED.finished_provisional, EXCLUDED.minutes, EXCLUDED.stats)
                """ : "") + " RETURNING id";
        List<Object> parameters = new ArrayList<>(rows.size() * 17);
        for (FixtureRow row : rows) {
            parameters.addAll(java.util.Arrays.asList(new Object[]{row.id(), row.seasonId(), row.gameweek(),
                    row.code(), row.homeTeamId(), row.awayTeamId(), row.homeScore(), row.awayScore(),
                    row.homeDifficulty(), row.awayDifficulty(), nullableTimestamp(row.kickoff()),
                    row.started(), row.finished(), field(row.fields(), "finished_provisional"),
                    field(row.fields(), "minutes"), field(row.fields(), "stats"), timestamp(publicationTime)}));
        }
        return jdbc.query(sql, (rs, ignored) -> rs.getInt("id"), parameters.toArray());
    }

    private static Object field(Map<String, Object> fields, String name) {
        return fields.get(name);
    }

    private static Object sqlTime(Object value) {
        return value instanceof Instant instant ? timestamp(instant) : value;
    }

    private static int batch(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
        int count = 0;
        for (int value : jdbc.batchUpdate(sql, rows)) count += Math.max(0, value);
        return count;
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
