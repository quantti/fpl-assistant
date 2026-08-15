package fi.karivanttinen.fplassistant.historical;

/**
 * The natural key {@code (season, home_team_code, away_team_code)} does not distinguish by
 * {@code source}, so a hand-written test row using a real team code and a real season can
 * collide with data another test (or the real backfill) writes to the same shared test table —
 * this happened once already ({@code HistoricalSchemaTest} originally used Arsenal/Chelsea's
 * real codes on a real season and collided with backfilled 2019-20 data).
 *
 * <p>Rather than relying on every test author remembering that by convention, tests assert
 * against this class that their fictitious data is actually outside the range real data can
 * occupy. Real FPL team codes are small (under 200 as of 2026); real seasons imported by this
 * phase are 2019-20 through 2025-26.
 */
final class FictitiousTestData {

    /** Any team code at or above this is guaranteed not to collide with a real FPL club. */
    static final int MIN_SAFE_CODE = 9000;

    private FictitiousTestData() {
    }

    static void assertSafeCode(int code) {
        if (code < MIN_SAFE_CODE) {
            throw new IllegalStateException("Test team code " + code + " is below the safe fictitious"
                    + " threshold " + MIN_SAFE_CODE + " and may collide with a real backfilled club");
        }
    }

    static void assertSafeSeason(String season) {
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
