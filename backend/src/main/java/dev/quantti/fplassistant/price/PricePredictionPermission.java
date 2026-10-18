package dev.quantti.fplassistant.price;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.Environment;

public final class PricePredictionPermission {
    private final Environment environment;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public PricePredictionPermission(Environment environment) {
        this.environment = environment;
    }

    public static boolean configured(Environment environment) {
        return "true".equalsIgnoreCase(environment.getProperty("fpl.price-prediction.enabled", "false"))
                && "OFF".equalsIgnoreCase(environment.getProperty("fpl.owned-sync.mode", "OFF").trim());
    }

    public boolean permitted() {
        if (!configured(environment)) stopped.set(true);
        return !stopped.get();
    }

    public void stop() {
        stopped.set(true);
    }
}
