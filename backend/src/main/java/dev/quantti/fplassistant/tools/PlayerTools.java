package dev.quantti.fplassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlayerTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final DailyPriceSource daily;

    public PlayerTools(JdbcTemplate jdbc, SeasonResolver seasonResolver, DailyPriceSource daily) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.daily = daily;
    }

    @Tool(description = """
            Search for an FPL player by name (web name or surname), spelled exactly as the user
            wrote it — never a corrected or similar-looking name. Answers from this season's FPL
            squad first (club, position, today's price, ownership, availability) and adds the
            most recent completed season's statistics beneath it. If the name is not in this
            season's squad the reply says so first, and that is the answer: the player cannot be
            picked. The statistics are: position, team, price, total points, form, goals,
            assists, clean sheets, xG, xA, ownership %, and availability status.
            Returns up to 5 matching players. The reply begins with the season the numbers
            come from; if that is not the season currently being played, tell the user the
            figures are historical. If the reply names a player the user did not ask for, that is
            not their answer: say who was matched and ask, rather than answering about them.
            """)
    public String getPlayerStats(String playerName) {
        try {
            // This season's squad first, from the daily capture taken out of FPL's own API. The
            // shared player table held zero rows for 2026-27 with the season already under way,
            // so resolving a name there alone answers only for who played last season — which is
            // how a question about Konsa was answered about Konaté, a name that existed in the
            // season the resolver had fallen back to.
            LocalDate captured = daily.latestCaptureDate();
            List<Map<String, Object>> current =
                    captured == null ? List.of() : daily.matchPlayers(playerName, captured);

            SeasonContext season = seasonResolver.resolve();
            List<Map<String, Object>> past = historicalRows(playerName, season);

            if (current.isEmpty() && past.isEmpty()) {
                // Spelled out, because the failure mode is the model quietly trying a
                // similar-looking name and answering about whoever that finds.
                return season.provenance() + "\nNo player matched \"" + playerName + "\"."
                        + " Do not search for a similar-looking name or answer about a different"
                        + " player: tell the user this name was not found and ask for the club or"
                        + " a fuller spelling.";
            }

            if (captured == null) {
                // No capture has ever run, so we do not know this season's squad — which is not
                // the same as knowing the player is absent from it. Claiming absence here would
                // answer every question during a fresh deploy with a confident falsehood.
                return season.provenance()
                        + "\n\nThis season's FPL squad list is unavailable (no daily capture has"
                        + " run), so whether this player is in the game cannot be confirmed. Do"
                        + " not say they are missing from it. The figures below are from a past"
                        + " season.\n\n"
                        + past.stream().map(r -> formatPlayer(r, season))
                                .collect(Collectors.joining("\n---\n"));
            }

            if (current.isEmpty()) {
                // In last season's data but not in this season's game — sold abroad, retired,
                // relegated, or never in FPL. Saying it outright is the whole point: this is
                // exactly the shape of answer that let a wrong-name guess look successful.
                return season.provenance()
                        + "\n\nNOT IN THIS SEASON'S FPL GAME: no player matching \"" + playerName
                        + "\" appears in FPL's current player list. Tell the user that before"
                        + " anything else — they cannot pick this player. The figures below"
                        + " describe a past season only.\n\n"
                        + past.stream().map(r -> formatPlayer(r, season))
                                .collect(Collectors.joining("\n---\n"));
            }

            Map<String, Object> best = current.getFirst();
            String currentBlock = DailyPriceSource.provenanceFor(best)
                    + "\n\n" + formatCurrent(best)
                    + NameMatch.ambiguityNote(current.stream()
                            .map(r -> String.valueOf(r.get("web_name"))).toList());

            List<Map<String, Object>> sameName = past.stream()
                    .filter(r -> String.valueOf(r.get("web_name"))
                            .equalsIgnoreCase(String.valueOf(best.get("web_name"))))
                    .toList();
            List<Map<String, Object>> history = sameName.isEmpty() ? past : sameName;
            if (history.isEmpty()) {
                return currentBlock + "\n\nNo earlier-season statistics are stored for this"
                        + " player.";
            }
            return currentBlock + "\n\n" + season.provenance() + "\n\n"
                    + history.stream().map(r -> formatPlayer(r, season))
                            .collect(Collectors.joining("\n---\n"));
        } catch (DataAccessException e) {
            log.error("getPlayerStats(\"{}\") failed", playerName, e);
            return "Player data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private List<Map<String, Object>> historicalRows(String playerName, SeasonContext season) {
        String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";
        return jdbc.queryForList("""
                SELECT p.web_name, p.first_name, p.second_name,
                       t.name as team, t.short_name,
                       p.element_type, p.now_cost, p.total_points, p.form,
                       p.goals_scored, p.assists, p.clean_sheets,
                       p.minutes, p.expected_goals, p.expected_assists,
                       p.selected_by_percent, p.status, p.news
                FROM player p
                JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                WHERE p.season_id = ?
                  AND (LOWER(p.web_name) LIKE ?
                       OR LOWER(p.second_name) LIKE ?
                       OR LOWER(p.first_name) LIKE ?
                       OR LOWER(p.first_name || ' ' || p.second_name) LIKE ?)
                ORDER BY p.total_points DESC
                LIMIT 5
                """, season.seasonId(), pattern, pattern, pattern, pattern);
    }

    /** Who the player is right now: club, position, today's price, ownership and availability. */
    private String formatCurrent(Map<String, Object> r) {
        double price = ((Number) r.get("now_cost")).doubleValue() / 10.0;
        Object type = r.get("element_type");
        String position = type == null ? "?" : position(((Number) type).intValue());
        String status = String.valueOf(r.get("status"));
        String availability = "a".equals(status) ? "available" : "FLAGGED (" + status + ")";
        String news = r.get("news") == null || String.valueOf(r.get("news")).isBlank()
                ? "" : " — " + r.get("news");
        return "%s (%s, %s) — this season: £%.1fm, owned by %s%%, %s%s".formatted(
                r.get("web_name"), position, r.get("team_short"), price,
                r.get("selected_by_percent"), availability, news);
    }

    private static String position(int elementType) {
        return switch (elementType) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
    }

    private String formatPlayer(Map<String, Object> r, SeasonContext season) {
        String position = switch (((Number) r.get("element_type")).intValue()) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
        double price = ((Number) r.get("now_cost")).doubleValue() / 10.0;
        // Price and ownership are "current-state" fields: when the numbers come from a
        // completed season, label them so the model cannot present them as this season's.
        String priceLabel = season.currentSeason()
                ? "Price"
                : "Price (" + season.code() + ", historical)";
        String ownershipLabel = season.currentSeason()
                ? "Ownership"
                : "Ownership (" + season.code() + ", historical)";
        // FPL status codes: a=available, d=doubtful, i/s/u/n = out for some reason. news is nullable.
        Object news = r.get("news");
        String detail = (news == null || news.toString().isBlank()) ? "" : " (" + news + ")";
        String status = switch (String.valueOf(r.get("status"))) {
            case "a" -> "Available";
            case "d" -> "Doubtful" + detail;
            default -> "Unavailable" + detail;
        };

        return """
                %s %s (%s) [%s]
                %s: £%.1fm | Points: %s | Form: %s
                Goals: %s | Assists: %s | CS: %s | Minutes: %s
                xG: %s | xA: %s | %s: %s%%
                Status: %s
                """.formatted(
                position, r.get("web_name"), r.get("short_name"), r.get("team"),
                priceLabel, price, r.get("total_points"), r.get("form"),
                r.get("goals_scored"), r.get("assists"), r.get("clean_sheets"), r.get("minutes"),
                r.get("expected_goals"), r.get("expected_assists"), ownershipLabel, r.get("selected_by_percent"),
                status
        );
    }
}
