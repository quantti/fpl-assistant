package fi.karivanttinen.fplassistant.price;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fi.karivanttinen.fplassistant.historical.VaastavSource;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;

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
 * The player name is denormalised from the tapas {@code player} table, falling back to
 * vaastav's {@code name} column for a player no longer in the tapas snapshot.
 *
 * <p>Writes are batched, and the vaastav path is incremental. A full season's
 * {@code merged_gw.csv} carries about 30,000 rows (one per player per gameweek); re-upserting
 * all of them one statement at a time on every sync would hold a background thread against the
 * pooled database this project shares with {@code tapas-fpl-app} for minutes at a time. Only
 * gameweeks at or after the highest already stored are rewritten — the latest stored gameweek is
 * included because it may have been captured while still in progress, and every earlier one is
 * frozen and cannot change.
 */
@Component
public class PriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);

    static final String SNAPSHOT_SOURCE = "snapshot";
    static final String VAASTAV_SOURCE = "vaastav";
    private static final String MERGED_GW_PATH = "gws/merged_gw.csv";
    private static final int BATCH_SIZE = 1000;

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

        // One name lookup for both writers: element_id -> display name from the tapas player table.
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
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            int elementId = ((Number) r.get("id")).intValue();
            int price = ((Number) r.get("now_cost")).intValue();
            batch.add(new Object[]{season.code(), 0, elementId,
                    namesById.getOrDefault(elementId, "unknown"), price, SNAPSHOT_SOURCE});
        }
        return upsertAll(batch);
    }

    private int syncVaastav(SeasonContext season, Map<Integer, String> namesById) {
        String csv = vaastavSource.fetchIfPresent(season.code(), MERGED_GW_PATH);
        if (csv == null) {
            log.info("No vaastav merged_gw.csv for {} yet (pre-season or no GWs played) — snapshot only",
                    season.code());
            return 0;
        }
        int fromGameweek = lowestGameweekWorthWriting(season.code());
        List<MergedGwRow> rows = MergedGwParser.parse(csv, season.code());
        List<Object[]> batch = new ArrayList<>();
        for (MergedGwRow row : rows) {
            if (row.gameweek() < fromGameweek) {
                continue; // already stored and frozen — an earlier gameweek's price cannot change
            }
            batch.add(new Object[]{season.code(), row.gameweek(), row.elementId(),
                    namesById.getOrDefault(row.elementId(), row.name()), row.price(), VAASTAV_SOURCE});
        }
        log.info("vaastav merged_gw.csv for {}: {} of {} rows are new or refreshable (from GW{})",
                season.code(), batch.size(), rows.size(), fromGameweek);
        return upsertAll(batch);
    }

    /**
     * @return the earliest gameweek the vaastav writer still needs to touch: the highest one
     *         already stored (it may have been captured mid-gameweek), or 1 when nothing is
     *         stored for this season yet
     */
    private int lowestGameweekWorthWriting(String season) {
        Integer highestStored = jdbc.queryForObject("""
                SELECT MAX(gameweek) FROM %s WHERE season = ? AND source = ?
                """.formatted(tableName), // tableName is config-injected, not user input
                Integer.class, season, VAASTAV_SOURCE);
        return highestStored == null ? 1 : highestStored;
    }

    /** @return the number of rows written */
    private int upsertAll(List<Object[]> batch) {
        String sql = """
                INSERT INTO %s (season, gameweek, element_id, player_name, price, source)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (season, gameweek, element_id, source) DO UPDATE SET
                    player_name = EXCLUDED.player_name,
                    price = EXCLUDED.price,
                    ingested_at = NOW()
                """.formatted(tableName); // tableName is config-injected, not user input
        for (int start = 0; start < batch.size(); start += BATCH_SIZE) {
            jdbc.batchUpdate(sql, batch.subList(start, Math.min(start + BATCH_SIZE, batch.size())));
        }
        return batch.size();
    }
}
