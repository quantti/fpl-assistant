package fi.karivanttinen.fplassistant.price;

import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

public final class PricePredictionSchema {

    public record Tables(String forecastCapture, String forecastPlayer,
                         String changeCapture, String changeEvent) {
        public Tables {
            require(forecastCapture, "fpl_price_forecast_capture");
            require(forecastPlayer, "fpl_price_forecast_player");
            require(changeCapture, "fpl_price_change_capture");
            require(changeEvent, "fpl_price_change_event");
            boolean test = forecastCapture.endsWith("_test");
            if (forecastPlayer.endsWith("_test") != test || changeCapture.endsWith("_test") != test
                    || changeEvent.endsWith("_test") != test) {
                throw new IllegalArgumentException("Cannot mix production and test tables");
            }
        }

        private static void require(String value, String base) {
            if (value == null || !Set.of(base, base + "_test").contains(value)) {
                throw new IllegalArgumentException("Invalid price prediction table identifier");
            }
        }

        public static Tables production() {
            return new Tables("fpl_price_forecast_capture", "fpl_price_forecast_player",
                    "fpl_price_change_capture", "fpl_price_change_event");
        }

        public static Tables test() {
            return new Tables("fpl_price_forecast_capture_test", "fpl_price_forecast_player_test",
                    "fpl_price_change_capture_test", "fpl_price_change_event_test");
        }
    }

    private PricePredictionSchema() { }

    public static void create(JdbcTemplate jdbc, Tables tables) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id integer PRIMARY KEY CHECK (id = 1),
                    enabled boolean NOT NULL DEFAULT false,
                    fence bigint NOT NULL DEFAULT 0 CHECK (fence >= 0),
                    lease_until timestamptz,
                    generation bigint NOT NULL DEFAULT 0,
                    season varchar(7), fetched_at timestamptz, checked_at timestamptz,
                    source_updated_at timestamptz, target timestamptz, previous_target timestamptz,
                    fingerprint varchar(64), changed_at timestamptz,
                    player_count integer, ready_count integer,
                    teams jsonb CHECK (jsonb_array_length(teams) = 20)
                )
                """.formatted(tables.forecastCapture()));
        jdbc.update("INSERT INTO " + tables.forecastCapture() + " (id) VALUES (1) ON CONFLICT DO NOTHING");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(7) NOT NULL, element_id integer NOT NULL CHECK (element_id > 0),
                    generation bigint NOT NULL, team_id integer NOT NULL CHECK (team_id > 0),
                    web_name varchar(256) NOT NULL, full_name varchar(513) NOT NULL,
                    team_name varchar(256) NOT NULL, team_short varchar(256) NOT NULL,
                    position integer NOT NULL CHECK (position BETWEEN 1 AND 4),
                    current_price integer NOT NULL CHECK (current_price > 0),
                    state varchar(16) NOT NULL CHECK (state IN
                        ('READY', 'CALIBRATING', 'LOCKED', 'UNAVAILABLE', 'UNSUPPORTED')),
                    progress numeric, projected_progress numeric, likelihood integer,
                    signal varchar(32), locked_until timestamptz,
                    PRIMARY KEY (season, element_id)
                )
                """.formatted(tables.forecastPlayer()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id integer PRIMARY KEY CHECK (id = 1), generation bigint NOT NULL DEFAULT 0,
                    season varchar(7), fetched_at timestamptz, checked_at timestamptz,
                    identity_fetched_at timestamptz, fingerprint varchar(64), changed_at timestamptz,
                    event_count integer, earliest_event timestamptz, latest_event timestamptz
                )
                """.formatted(tables.changeCapture()));
        jdbc.update("INSERT INTO " + tables.changeCapture() + " (id) VALUES (1) ON CONFLICT DO NOTHING");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(7) NOT NULL, element_id integer NOT NULL CHECK (element_id > 0),
                    generation bigint NOT NULL, team_id integer NOT NULL CHECK (team_id > 0),
                    web_name varchar(256) NOT NULL, full_name varchar(513) NOT NULL,
                    team_name varchar(256) NOT NULL, team_short varchar(256) NOT NULL,
                    gameweek integer NOT NULL CHECK (gameweek BETWEEN 1 AND 38),
                    changed_at timestamptz NOT NULL,
                    price_before integer NOT NULL CHECK (price_before > 0),
                    price_after integer NOT NULL CHECK (price_after > 0 AND price_after <> price_before),
                    PRIMARY KEY (season, element_id, changed_at)
                )
                """.formatted(tables.changeEvent()));
    }
}
