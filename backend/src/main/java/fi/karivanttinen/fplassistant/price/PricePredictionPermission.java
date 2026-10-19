package fi.karivanttinen.fplassistant.price;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.Environment;

public final class PricePredictionPermission {
    private final Environment environment;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean configurationDenied = new AtomicBoolean();

    public PricePredictionPermission(Environment environment) {
        this.environment = environment;
    }

    public static boolean configured(Environment environment) {
        if (!"true".equalsIgnoreCase(environment.getProperty("fpl.price-prediction.enabled", "false"))) return false;
        String mode = environment.getProperty("fpl.owned-sync.mode", "OFF").trim();
        if ("OFF".equalsIgnoreCase(mode)) return true;
        String season = environment.getProperty("fpl.price-prediction.playing-season", "").trim();
        return "ACTIVE".equalsIgnoreCase(mode)
                && "true".equalsIgnoreCase(environment.getProperty("fpl.owned-sync.importer.enabled", "false"))
                && "true".equalsIgnoreCase(environment.getProperty("fpl.owned-sync.cutover-confirmed", "false"))
                && !season.isEmpty()
                && season.equals(environment.getProperty("fpl.owned-sync.playing-season", "").trim());
    }

    public boolean permitted() {
        if (!configured(environment)) configurationDenied.set(true);
        return !stopped.get() && !configurationDenied.get();
    }

    boolean revocationRequired() {
        return configurationDenied.get();
    }

    public void stop() {
        stopped.set(true);
    }
}
