package fi.karivanttinen.fplassistant.price;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@PricePredictionEnabled
public class PricePredictionConfiguration {
    public record Settings(String playingSeason, PricePredictionSchema.Tables tables, URI bootstrap, URI history) { }

    @Bean
    Settings pricePredictionSettings(Environment environment) {
        String season = environment.getProperty("fpl.price-prediction.playing-season", "");
        if (!season.matches("[0-9]{4}-[0-9]{2}") || Integer.parseInt(season.substring(0, 4)) < 2000
                || Integer.parseInt(season.substring(0, 4)) > 9998
                || Integer.parseInt(season.substring(5)) != (Integer.parseInt(season.substring(0, 4)) + 1) % 100) {
            throw new IllegalArgumentException("Explicit fpl.price-prediction.playing-season must be YYYY-YY");
        }
        boolean test = environment.acceptsProfiles(Profiles.of("test"));
        String base = test ? environment.getProperty("fpl.price-prediction.test-base-url", "http://localhost:9/")
                : "https://fantasy.premierleague.com/api/";
        URI uri = URI.create(base);
        if (test && (!"http".equals(uri.getScheme())
                || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)) {
            throw new IllegalArgumentException("Test price source must be a loopback HTTP stub");
        }
        if (!base.endsWith("/")) throw new IllegalArgumentException("Source base requires trailing slash");
        return new Settings(season, test ? PricePredictionSchema.Tables.test() : PricePredictionSchema.Tables.production(),
                uri.resolve("bootstrap-static/"), uri.resolve("stats/price-changes/"));
    }

    @Bean
    PricePredictionPermission pricePredictionPermission(Environment environment) {
        return new PricePredictionPermission(environment);
    }

    @Bean
    PricePredictionStore pricePredictionStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, Settings settings) {
        PricePredictionSchema.create(jdbc, settings.tables());
        return new PricePredictionStore(jdbc, transactions, settings.tables());
    }

    @Bean(destroyMethod = "close")
    PricePredictionHttp pricePredictionHttp() {
        return new PricePredictionHttp(Duration.ofSeconds(20));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    PricePredictionWorker pricePredictionWorker(PricePredictionStore store, PricePredictionPermission permission,
                                               PricePredictionHttp http, Settings settings) {
        return new PricePredictionWorker(store, permission, http::get, Clock.systemUTC(), settings.playingSeason(),
                settings.bootstrap(), settings.history());
    }
}
