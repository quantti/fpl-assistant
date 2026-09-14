package dev.quantti.fplassistant.defcon;

import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.historical.VaastavSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backfills the defensive-contribution columns for a completed season from vaastav's
 * {@code gws/merged_gw.csv}.
 *
 * <p><b>Why not FPL, which is where the nightly sync gets them.</b> {@code element-summary} returns
 * per-match rows for the current season only. A past season appears under {@code history_past},
 * and that is one row per season — totals, not matches. Since the points are a threshold per
 * match, totals cannot answer how often a player crossed it, which is the whole reason these
 * columns exist. vaastav archives the per-match rows, and this project already reads that
 * repository for historical data.
 *
 * <p>One CSV rather than six hundred requests, and no rate limiting to respect.
 *
 * <p><b>Every write is guarded on minutes.</b> The update matches on fixture, player, season
 * <i>and</i> the minutes already stored, so a row is only touched when both sources agree on
 * what happened in it. FPL renumbers players every season and vaastav's {@code element} column is
 * the FPL id of the season the file belongs to; if that assumption were ever wrong for a season,
 * the guard turns a silent corruption of another player's row into a skipped row and a number in
 * the log. Verified for 2025-26 before this was written: sampled keys agree on minutes and
 * resolve to the same names.
 */
@Component
public class DefensiveStatsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsBackfillService.class);

    private static final String FILE = "gws/merged_gw.csv";
    private static final int BATCH_SIZE = 1000;

    private final VaastavSource vaastav;
    private final JdbcTemplate jdbc;
    private final boolean ownedActive;

    public DefensiveStatsBackfillService(VaastavSource vaastav, JdbcTemplate jdbc) {
        this(vaastav, jdbc, "OFF");
    }

    @Autowired
    public DefensiveStatsBackfillService(VaastavSource vaastav, JdbcTemplate jdbc,
                                         @Value("${fpl.owned-sync.mode:OFF}") String ownedMode) {
        this.vaastav = vaastav;
        this.jdbc = jdbc;
        this.ownedActive = "ACTIVE".equalsIgnoreCase(ownedMode.trim());
    }

    /** @return number of rows updated, for logging and tests */
    public int backfill(String seasonCode) {
        validateArchiveSeason(seasonCode);
        Integer seasonId = seasonId(seasonCode);
        if (seasonId == null) {
            log.warn("defcon backfill: no season row for {}; nothing written", seasonCode);
            return 0;
        }
        String csv = vaastav.fetchIfPresent(seasonCode, FILE);
        if (csv == null) {
            log.warn("defcon backfill: {}/{} is missing or empty; nothing written",
                    seasonCode, FILE);
            return 0;
        }

        List<Object[]> updates = toUpdates(csv, seasonId, seasonCode);
        if (updates.isEmpty()) {
            log.warn("defcon backfill: {} parsed to no usable rows; nothing written", seasonCode);
            return 0;
        }

        int updated = applyAll(updates);
        int skipped = updates.size() - updated;
        log.info("defcon backfill {}: {} rows updated, {} skipped (no matching row, or the two"
                + " sources disagree on minutes)", seasonCode, updated, skipped);
        if (skipped > updates.size() / 2) {
            log.warn("defcon backfill {}: more than half the rows did not match. Check that"
                    + " vaastav's element ids belong to the same season as player_fixture_stats"
                    + " before trusting what was written.", seasonCode);
        }
        return updated;
    }

    /**
     * Parses the merged gameweek file into update arguments. Package-private and taking the CSV
     * text so it is unit-testable without a network call.
     *
     * <p>Parsed by header name, never by position: vaastav's column order has changed between
     * seasons, and these four columns were only added when FPL introduced the scoring rule.
     */
    static List<Object[]> toUpdates(String csv, int seasonId, String seasonCode) {
        // Keyed, not appended. vaastav's 2025-26 file lists ten player-fixture pairs twice —
        // identical values both times, an artefact rather than a conflict — and duplicates would
        // otherwise make the update count exceed the number of rows that exist, which is exactly
        // the shape of a bug worth not having to rule out later.
        Map<List<Integer>, Object[]> byKey = new LinkedHashMap<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(csv))) {
            if (!parser.getHeaderMap().containsKey("defensive_contribution")) {
                log.warn("defcon backfill: {}/{} has no defensive_contribution column — the"
                        + " season predates the scoring rule", seasonCode, FILE);
                return List.of();
            }
            for (CSVRecord record : parser) {
                Integer fixtureId = intAt(record, "fixture");
                Integer playerId = intAt(record, "element");
                Integer minutes = intAt(record, "minutes");
                if (fixtureId == null || playerId == null || minutes == null) {
                    continue;
                }
                byKey.put(List.of(fixtureId, playerId), new Object[] {
                        intAt(record, "defensive_contribution"),
                        intAt(record, "clearances_blocks_interceptions"),
                        intAt(record, "recoveries"),
                        intAt(record, "tackles"),
                        fixtureId, playerId, seasonId, minutes,
                });
            }
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(seasonCode + ": failed to parse " + FILE, e);
        }
        return new ArrayList<>(byKey.values());
    }

    private int applyAll(List<Object[]> updates) {
        String sql = """
                UPDATE player_fixture_stats
                   SET defensive_contribution = ?,
                       clearances_blocks_interceptions = ?,
                       recoveries = ?,
                       tackles = ?
                 WHERE fixture_id = ? AND player_id = ? AND season_id = ? AND minutes = ?
                """;
        int updated = 0;
        for (int from = 0; from < updates.size(); from += BATCH_SIZE) {
            List<Object[]> batch =
                    updates.subList(from, Math.min(from + BATCH_SIZE, updates.size()));
            for (int count : jdbc.batchUpdate(sql, batch)) {
                updated += Math.max(count, 0);
            }
        }
        return updated;
    }

    /** Guards direct service calls as well as the CLI runner, before any archive network read. */
    public void validateArchiveSeason(String seasonCode) {
        if (seasonCode == null || seasonCode.isBlank()) {
            throw new IllegalArgumentException("defcon backfill season is required");
        }
        if (!ownedActive) return;
        List<String> playing = jdbc.queryForList(
                "SELECT code FROM season WHERE is_current=true", String.class);
        if (playing.size() != 1) {
            throw new IllegalStateException("ACTIVE defcon backfill requires exactly one playing season");
        }
        if (playing.getFirst().equals(seasonCode.trim())) {
            throw new IllegalStateException("ACTIVE owned sync forbids DefCon backfill of the playing season");
        }
    }

    private Integer seasonId(String seasonCode) {
        List<Integer> ids = jdbc.queryForList(
                "SELECT id FROM season WHERE code = ?", Integer.class, seasonCode);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static Integer intAt(CSVRecord record, String column) {
        // isSet, not isMapped: a header can name a column that a short row does not reach, and
        // record.get would then throw IllegalArgumentException past the IOException catch and
        // abort the whole backfill. MergedGwParser.optional makes the same distinction.
        if (!record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // vaastav writes some integer columns as "3.0"; truncation is safe here because a
            // count of defensive actions has no fractional part to lose.
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
