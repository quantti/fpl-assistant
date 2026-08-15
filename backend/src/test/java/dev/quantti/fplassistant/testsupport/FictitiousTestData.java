package dev.quantti.fplassistant.testsupport;

/**
 * Guards fictitious test data against colliding with real data in the shared test tables.
 *
 * <p>The natural keys this project upserts on do not distinguish by {@code source}, so a
 * hand-written test row using a real team code (or a real season label) can collide with data
 * another test — or a real backfill — writes to the same shared test table. This happened once
 * already ({@code HistoricalSchemaTest} originally used Arsenal/Chelsea's real codes on a real
 * season and collided with backfilled 2019-20 data).
 *
 * <p>Rather than relying on every test author remembering that by convention, tests assert
 * against this class that their fictitious data is actually outside the range real data can
 * occupy. Real FPL team codes are small (under 200 as of 2026); real seasons imported by this
 * project are 2019-20 onward.
 *
 * <p>Lives in {@code testsupport} rather than in one phase's package so every phase can reach
 * it — Phase 4's price tests need the same guard Phase 3's historical tests do.
 */
public final class FictitiousTestData {

    /** Any team code or player element id at or above this cannot collide with real FPL data. */
    public static final int MIN_SAFE_CODE = 9000;

    /**
     * Both {@code pl_match_result.season} and {@code fpl_player_price.season} are
     * {@code VARCHAR(10)}. A too-long fictitious label fails at insert time with "value too long
     * for type character varying(10)" — which has now caught two phases out, so it is checked
     * here alongside the collision rules rather than discovered again in a test run.
     */
    public static final int MAX_SEASON_LENGTH = 10;

    private FictitiousTestData() {
    }

    public static void assertSafeCode(int code) {
        if (code < MIN_SAFE_CODE) {
            throw new IllegalStateException("Test team code " + code + " is below the safe fictitious"
                    + " threshold " + MIN_SAFE_CODE + " and may collide with a real backfilled club");
        }
    }

    public static void assertSafeSeason(String season) {
        if (season.length() > MAX_SEASON_LENGTH) {
            throw new IllegalStateException("Test season '" + season + "' is " + season.length()
                    + " characters; the season columns are VARCHAR(" + MAX_SEASON_LENGTH
                    + ") and the insert will fail at runtime");
        }
        // Every real season this project has ever imported or will import looks like
        // "YYYY-YY" — rejecting that shape entirely (not just the current 2019-20..2025-26
        // range) means a future backfill-range widening can never retroactively collide with
        // a season a test picked because it looked safely far in the future.
        if (season.matches("\\d{4}-\\d{2}")) {
            throw new IllegalStateException("Test season '" + season + "' has the shape of a real"
                    + " FPL season and may collide with real backfilled or synced data — use a"
                    + " non-numeric season label instead (e.g. 'test-season-a')");
        }
    }
}
