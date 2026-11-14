package dev.quantti.fplassistant.defcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Adds the defensive-contribution columns to {@code player_fixture_stats}.
 *
 * <p><b>This is the one place the project alters a shared table, on the owner's explicit
 * instruction (2026-08-26), and the rule in {@code AGENTS.md} otherwise stands.</b> The columns
 * cannot live anywhere else without being worse: defensive contribution is per player per
 * fixture, which is exactly this table's grain, and a parallel {@code fpl_}-prefixed table would
 * duplicate the key, need its own join in every query, and drift the moment shared re-syncs a
 * gameweek.
 *
 * <p><b>Why the columns are missing in the first place.</b> FPL introduced defensive
 * contribution for 2025/26 — 2 points for a defender reaching 10 clearances, blocks,
 * interceptions and tackles in a match, or a midfielder or forward reaching 12 including
 * recoveries. SharedStats' schema predates the field, and its sync writes a named column list, so the
 * values have been arriving in the {@code element-summary} payload and being dropped ever since.
 *
 * <p><b>Safe against the upstream project’s own writes.</b> Every column is nullable with no default, and shared
 * upserts a named column list; a column it does not know about is neither written nor cleared by
 * it. The reverse also holds — {@link DefensiveStatsSyncService} only ever UPDATEs these four
 * columns, so it cannot disturb a value shared owns.
 *
 * <p>Nullable rather than {@code DEFAULT 0} deliberately. Zero is a real value here — a striker
 * who made no defensive actions — and a default would make "not yet fetched" indistinguishable
 * from "nothing happened", which is the same mistake that made the first set-piece capture
 * report the whole league as changed.
 */
@Component
@ConditionalOnProperty(name = "fpl.defcon.ensure-columns", havingValue = "true",
        matchIfMissing = true)
public class DefensiveStatsSchema {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsSchema.class);

    static final String TABLE = "player_fixture_stats";

    /** FPL's own field names, kept identical so the mapping needs no lookup table. */
    static final String[] COLUMNS = {
            "defensive_contribution",
            "clearances_blocks_interceptions",
            "recoveries",
            "tackles",
    };

    private final JdbcTemplate jdbc;

    public DefensiveStatsSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Logged and swallowed, never fatal. The role this app connects as may not hold ALTER on a
     * table it does not own, and refusing to start the whole chatbot over four enrichment columns
     * would be the wrong trade — the sync that fills them already logs and continues, and the
     * pick tools already say when the numbers are unavailable. {@code fpl.defcon.ensure-columns}
     * turns it off entirely once the columns exist.
     */
    @PostConstruct
    public void ensureColumns() {
        try {
            for (String column : COLUMNS) {
                jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS " + column
                        + " SMALLINT");
            }
            log.info("defensive-contribution columns ensured on {}", TABLE);
        } catch (DataAccessException e) {
            log.error("could not add the defensive-contribution columns to {}. Defensive numbers"
                    + " will be unavailable until this is granted or the columns are added by"
                    + " hand; nothing else is affected.", TABLE, e);
        }
    }
}
