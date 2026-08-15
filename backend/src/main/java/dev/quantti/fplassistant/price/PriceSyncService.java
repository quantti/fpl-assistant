package dev.quantti.fplassistant.price;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.historical.VaastavSource;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;

/**
 * Keeps the current season's player prices current in {@code fpl_player_price} from two
 * sources, both written only when {@link SeasonContext#currentSeason()} is true (i.e. the
 * resolved season is the one being played, not a completed fallback):
 *
 * <ol>
 *   <li><b>Snapshot</b> — copies {@code player.now_cost} for every player in the resolved
 *       season into {@code gameweek = 0}, {@code source = 'snapshot'}. This is the live price
 *       between GWs; it is overwritten in place on every sync.</li>
 *   <li><b>vaastav</b> — pulls the current season's {@code gws/merged_gw.csv} (absent before
 *       GW1, handled as a no-op) and writes one row per played GW with {@code source =
 *       'vaastav'}, freezing the official end-of-GW price.</li>
 * </ol>
 *
 * <p>Both upsert on {@code (season, gameweek, element_id, source)} so re-runs never duplicate.
 * The player name is denormalised from the shared {@code player} table, falling back to
 * vaastav's {@code name} column for a player no longer in the shared snapshot.
 */
@Component
public class PriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);

    static final String SNAPSHOT_SOURCE = "snapshot";
    static final String VAASTAV_SOURCE = "vaastav";
    private static final String MERGED_GW_PATH = "gws/merged_gw.csv";

    private final VaastavSource vaastavSource;
    private final SeasonResolver seasonResolver;
    private final JdbcTemplate jdbc;
    private final String tableName;

    public PriceSyncService(VaastavSource vaastavSource, SeasonResolver seasonResolver,
                             JdbcTemplate jdbc,
                             @Value("${fpl.price.table-name}") String tableName) {
        this.vaastavSource = vaastavSource;
        this.seasonResolver = seasonResolver;
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    /** @return total rows written (snapshot + vaastav), for logging/testing */
    public int sync() {
        SeasonContext season = seasonResolver.resolve();
        if (!season.currentSeason()) {
            log.info("Skipping price sync: the resolved season ({}) is a completed fallback,"
                    + " not the season in progress", season.code());
            return 0;
        }

        // One name lookup for both writers: element_id -> display name from the shared player table.
        Map<Integer, String> namesById = loadPlayerNames(season.seasonId());

        int snapshot = snapshot(season, namesById);
        int vaastav = syncVaastav(season, namesById);
        log.info("price sync: {} snapshot rows + {} vaastav rows for {}",
                snapshot, vaastav, season.code());
        return snapshot + vaastav;
    }

    private Map<Integer, String> loadPlayerNames(int seasonId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, first_name, second_name, web_name FROM player WHERE season_id = ?
                """, seasonId);
        Map<Integer, String> names = new HashMap<>();
        for (Map<String, Object> r : rows) {
            int id = ((Number) r.get("id")).intValue();
            String first = (String) r.get("first_name");
            String second = (String) r.get("second_name");
            String web = (String) r.get("web_name");
            String name = (first != null && second != null) ? first + " " + second : web;
            names.put(id, name != null ? name : "unknown");
        }
        return names;
    }

    private int snapshot(SeasonContext season, Map<Integer, String> namesById) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, now_cost FROM player WHERE season_id = ?", season.seasonId());
        int written = 0;
        for (Map<String, Object> r : rows) {
            int elementId = ((Number) r.get("id")).intValue();
            int price = ((Number) r.get("now_cost")).intValue();
            String name = namesById.getOrDefault(elementId, "unknown");
            upsert(season.code(), 0, elementId, name, price, SNAPSHOT_SOURCE);
            written++;
        }
        return written;
    }

    private int syncVaastav(SeasonContext season, Map<Integer, String> namesById) {
        String csv = vaastavSource.fetchIfPresent(season.code(), MERGED_GW_PATH);
        if (csv == null) {
            log.info("No vaastav merged_gw.csv for {} yet (pre-season or no GWs played) — snapshot only",
                    season.code());
            return 0;
        }
        List<MergedGwRow> rows = MergedGwParser.parse(csv, season.code());
        int written = 0;
        for (MergedGwRow row : rows) {
            String name = namesById.getOrDefault(row.elementId(), row.name());
            upsert(season.code(), row.gameweek(), row.elementId(), name, row.price(), VAASTAV_SOURCE);
            written++;
        }
        return written;
    }

    private void upsert(String season, int gameweek, int elementId, String name, int price, String source) {
        jdbc.update("""
                INSERT INTO %s (season, gameweek, element_id, player_name, price, source)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, gameweek, element_id, source) DO UPDATE SET
                    player_name = EXCLUDED.player_name,
                    price = EXCLUDED.price,
                    ingested_at = NOW()
                """.formatted(tableName), // tableName is config-injected, not user input
                season, gameweek, elementId, name, price, source);
    }
}
