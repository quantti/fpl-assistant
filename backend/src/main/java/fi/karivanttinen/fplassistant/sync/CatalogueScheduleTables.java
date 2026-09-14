package fi.karivanttinen.fplassistant.sync;

import java.util.Objects;
import java.util.regex.Pattern;

/** Configurable captured-table names used by the inert catalogue/schedule writer. */
public record CatalogueScheduleTables(String dailyPrice, String gameweek, String fixture) {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    public CatalogueScheduleTables {
        dailyPrice = valid(dailyPrice);
        gameweek = valid(gameweek);
        fixture = valid(fixture);
    }

    public static CatalogueScheduleTables defaults() {
        return new CatalogueScheduleTables("fpl_daily_price", "fpl_gameweek", "fpl_fixture");
    }

    public static CatalogueScheduleTables tests() {
        return new CatalogueScheduleTables("fpl_daily_price_test", "fpl_gameweek_test", "fpl_fixture_test");
    }

    private static String valid(String value) {
        Objects.requireNonNull(value, "SQL identifier");
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        }
        return value;
    }
}
