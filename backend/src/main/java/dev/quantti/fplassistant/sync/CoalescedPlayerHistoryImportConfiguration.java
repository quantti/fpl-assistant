package dev.quantti.fplassistant.sync;

import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Fails closed until the importer can publish the compatibility core catalogue safely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fpl.owned-sync.importer.enabled", havingValue = "true")
public class CoalescedPlayerHistoryImportConfiguration {

    @Bean
    ImporterActivation ownedImporterActivation(Environment environment) {
        String mode = environment.getProperty("fpl.owned-sync.mode", "OFF");
        String season = environment.getProperty("fpl.owned-sync.playing-season", "").trim();
        boolean cutover = environment.getProperty(
                "fpl.owned-sync.cutover-confirmed", Boolean.class, false);
        validateActivation(mode, season, cutover);
        throw new IllegalStateException("Incremental importer remains inert until a changed-only "
                + "compatibility writer publishes team, player, gameweek and fixture rows without "
                + "writing daily capture tables");
    }

    static ImporterActivation validateActivation(String configuredMode, String configuredSeason,
                                                  boolean cutoverConfirmed) {
        Objects.requireNonNull(configuredMode);
        Objects.requireNonNull(configuredSeason);
        OwnedSyncMode mode = OwnedSyncMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        if (mode != OwnedSyncMode.ACTIVE) {
            throw new IllegalStateException("Incremental importer requires fpl.owned-sync.mode=ACTIVE");
        }
        OwnedSyncRuntimeConfiguration.validateActivation(mode, configuredSeason, cutoverConfirmed);
        return new ImporterActivation(configuredSeason);
    }

    record ImporterActivation(String playingSeason) { }
}
