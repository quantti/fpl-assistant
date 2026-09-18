package fi.karivanttinen.fplassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class PlayerTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final DailyPriceSource daily;
    private final Clock clock;
    private final PlayerNameSearch names;

    public PlayerTools(JdbcTemplate jdbc, SeasonResolver seasonResolver, DailyPriceSource daily,
                       ObjectProvider<Clock> clock, PlayerNameSearch names) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.daily = daily;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.names = names;
    }

    @Tool(description = """
            Search for an FPL player by name (web name or surname), spelled exactly as the user
            wrote it — never a corrected or similar-looking name. The name may be a surname, a
            full name, or a name followed by the club ("Hall Newcastle"); accents are optional.
            Answers from this season's FPL squad first (club, position, today's price,
            ownership, availability) and adds
            per-season statistics beneath it: once the season being played holds statistics, it
            and the previous season are shown side by side, each labelled with the season it came
            from — weigh them per the early-season rule. Until then the most recent completed
            season's statistics are shown alone. If the name is not in this
            season's squad the reply says so first, and that is the answer: the player cannot be
            picked — unless the reply says only that they could not be CONFIRMED, in which case
            two sources disagree and the answer is that, not an absence. The statistics are: position, team, price, total points, form, goals,
            assists, clean sheets, xG, xA, ownership %, and availability status.
            Returns up to 5 matching players per season. Every block of statistics names the
            season it came from; if that is not the season currently being played, tell the user
            the figures are historical. If the reply names a player the user did not ask for,
            that is not their answer: say who was matched and ask, rather than answering
            about them.
            """)
    public String getPlayerStats(String playerName) {
        // One instant for every stored-row age on this card (KAR-9).
        Instant asOf = clock.instant();
        try {
            // This season's squad first, from the daily capture taken out of FPL's own API. The
            // tapas player table held zero rows for 2026-27 with the season already under way,
            // so resolving a name there alone answers only for who played last season — which is
            // how a question about Konsa was answered about Konaté, a name that existed in the
            // season the resolver had fallen back to.
            LocalDate captured = daily.latestCaptureDate();
            List<Map<String, Object>> current =
                    captured == null ? List.of() : daily.matchPlayers(playerName, captured);

            SeasonContext season = seasonResolver.resolve();
            // Per-season statistics. Once the resolved season IS the one being played, the
            // previous season is shown beside it — without that, the day the resolver moves to
            // the new season, last season's 38-match evidence silently vanishes and every answer
            // leans on a one-or-two-match sample.
            List<SeasonStats> stats = seasonStatsBlocks(playerName, season);

            if (current.isEmpty() && stats.isEmpty()) {
                // Spelled out, because the failure mode is the model quietly trying a
                // similar-looking name and answering about whoever that finds.
                return season.provenance() + "\nNo player matched \"" + playerName + "\"."
                        + " The lookup already ignores accents and accepts a club after the name."
                        + " You may retry once with the surname alone. Do not search for a"
                        + " similar-looking name or answer about a different player: otherwise"
                        + " tell the user this name was not found and ask for the club or a"
                        + " fuller spelling.";
            }

            if (captured == null) {
                // No capture has ever run, so we do not know this season's squad — which is not
                // the same as knowing the player is absent from it. Claiming absence here would
                // answer every question during a fresh deploy with a confident falsehood.
                // Every block below carries its own "Data source:" line, so no banner here.
                return "This season's FPL squad list is unavailable (no daily capture has"
                        + " run), so whether this player is in the game cannot be confirmed. Do"
                        + " not say they are missing from it. The statistics below name the season"
                        + " each figure is from.\n\n"
                        + formatBlocks(stats, null, asOf);
            }

            if (current.isEmpty()) {
                if (stats.stream().anyMatch(b -> b.context().currentSeason())) {
                    // Two sources that should agree do not: the name is in this season's player
                    // table but not in today's capture of FPL's own list. Asserting absence here
                    // would contradict the statistics printed directly underneath it.
                    return "NOT CONFIRMED IN THIS SEASON'S FPL GAME: no player matching \""
                            + playerName + "\" appears in today's capture of FPL's player list,"
                            + " yet this season's statistics below do carry the name. The two"
                            + " sources disagree — say that the player could not be confirmed in"
                            + " FPL's current list rather than that they cannot be picked, and"
                            + " suggest checking the club or a fuller spelling.\n\n"
                            + formatBlocks(stats, null, asOf);
                }
                // In last season's data but not in this season's game — sold abroad, retired,
                // relegated, or never in FPL. Saying it outright is the whole point: this is
                // exactly the shape of answer that let a wrong-name guess look successful.
                return "NOT IN THIS SEASON'S FPL GAME: no player matching \"" + playerName
                        + "\" appears in FPL's current player list. Tell the user that before"
                        + " anything else — they cannot pick this player. The statistics below"
                        + " name the season each figure is from.\n\n"
                        + formatBlocks(stats, null, asOf);
            }

            Map<String, Object> best = current.getFirst();
            String currentBlock = DailyPriceSource.provenanceFor(best)
                    + "\n\n" + formatCurrent(best)
                    + NameMatch.ambiguityNote(current.stream()
                            .map(r -> String.valueOf(r.get("web_name"))).toList());

            if (stats.isEmpty()) {
                return currentBlock + "\n\nNo per-season statistics are stored for this player.";
            }
            return currentBlock + "\n\n" + formatBlocks(stats, String.valueOf(best.get("web_name")), asOf);
        } catch (DataAccessException e) {
            log.error("getPlayerStats(\"{}\") failed", playerName, e);
            return "Player data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * A season's rows for the player. {@code previous} marks the completed season on the
     * dual-season card; the provenance line is rendered from {@code context}, never stored
     * beside it — a stored line and the context it labels can drift apart, and a block whose
     * header names one season while its price is labelled with another compiles cleanly.
     */
    private record SeasonStats(SeasonContext context, boolean previous,
                               List<Map<String, Object>> rows) {
    }

    /**
     * One block per season holding rows for the name: the resolved season, plus the previous
     * one when the resolved season is the one being played — after the season "flip", last
     * season is otherwise unreachable. Seasons with no rows for the name are dropped rather
     * than rendered empty.
     */
    private List<SeasonStats> seasonStatsBlocks(String playerName, SeasonContext season) {
        Optional<SeasonContext> previous = season.currentSeason()
                ? seasonResolver.previousSeason(season)
                : Optional.empty();
        Map<Integer, List<Map<String, Object>>> rows = seasonRows(playerName, season.seasonId(),
                previous.map(SeasonContext::seasonId).orElse(null));

        List<SeasonStats> blocks = new ArrayList<>();
        List<Map<String, Object>> resolved = rows.getOrDefault(season.seasonId(), List.of());
        if (!resolved.isEmpty()) {
            blocks.add(new SeasonStats(season, false, resolved));
        }
        previous.ifPresent(prev -> {
            List<Map<String, Object>> prevRows = rows.getOrDefault(prev.seasonId(), List.of());
            if (!prevRows.isEmpty()) {
                blocks.add(new SeasonStats(prev, true, prevRows));
            }
        });
        return blocks;
    }

    /**
     * Renders each season's block under its provenance line. When the player was matched in
     * this season's squad, same-web_name rows are preferred per season — a substring search
     * can return five namesakes, and the answer is about one player.
     */
    private String formatBlocks(List<SeasonStats> blocks, String matchedWebName, Instant asOf) {
        // Whether the season being played contributed a block of its own decides how the
        // completed season is introduced: "the current season is under way" alongside numbers
        // from it, but not when it is the only block on the card.
        boolean currentShown = blocks.stream().anyMatch(b -> !b.previous());
        return blocks.stream()
                .map(b -> provenanceOf(b, currentShown) + "\n\n" + formatRows(b, matchedWebName, asOf))
                .collect(Collectors.joining("\n\n"));
    }

    private static String provenanceOf(SeasonStats block, boolean currentShown) {
        return block.previous()
                ? block.context().previousSeasonProvenance(currentShown)
                : block.context().provenance();
    }

    /**
     * Each displayed row is dated by its own {@code updated_at}, after name preference: a
     * namesake filtered out here, or the other season's row, cannot lend this row its write time.
     */
    private String formatRows(SeasonStats block, String matchedWebName, Instant asOf) {
        List<Map<String, Object>> rows = block.rows();
        if (matchedWebName != null) {
            List<Map<String, Object>> sameName = rows.stream()
                    .filter(r -> String.valueOf(r.get("web_name"))
                            .equalsIgnoreCase(matchedWebName))
                    .toList();
            if (!sameName.isEmpty()) {
                rows = sameName;
            }
        }
        return rows.stream().map(r -> formatPlayer(r, block.context())
                        + DataAge.storedDataAge("player record for " + r.get("web_name")
                                + ", season " + block.context().code(), r.get("updated_at"), asOf))
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * Both seasons' rows in one round trip, grouped by season id — this runs on the chat hot
     * path, and the two statements differed only in the season id. The window function keeps
     * each season's own top five, which a single {@code LIMIT} over the union could not.
     *
     * @param previousSeasonId may be null, in which case only {@code seasonId} is queried
     */
    private Map<Integer, List<Map<String, Object>>> seasonRows(String playerName, int seasonId,
                                                               Integer previousSeasonId) {
        int second = previousSeasonId == null ? seasonId : previousSeasonId;
        PlayerNameSearch.Fragment match = names.fragment(playerName, "p.web_name",
                "p.web_name || ' ' || p.first_name || ' ' || p.second_name",
                "t.name || ' ' || t.short_name");
        List<Object> args = new ArrayList<>(match.orderArgs());
        args.add(seasonId);
        args.add(second);
        args.addAll(match.whereArgs());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM (
                    SELECT p.season_id, p.web_name, p.first_name, p.second_name,
                           t.name as team, t.short_name,
                           p.element_type, p.now_cost, p.total_points, p.form,
                           p.goals_scored, p.assists, p.clean_sheets,
                           p.minutes, p.expected_goals, p.expected_assists,
                           p.selected_by_percent, p.status, p.news, p.updated_at,
                           ROW_NUMBER() OVER (PARTITION BY p.season_id
                                              ORDER BY %s, p.total_points DESC) AS rn
                    FROM player p
                    JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id IN (?, ?)
                      AND %s
                ) ranked
                WHERE rn <= 5
                ORDER BY season_id, rn
                """.formatted(match.exactFirst(), match.where()), args.toArray());
        return rows.stream().collect(Collectors.groupingBy(
                r -> ((Number) r.get("season_id")).intValue(),
                LinkedHashMap::new, Collectors.toList()));
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
