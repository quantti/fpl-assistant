package fi.karivanttinen.fplassistant;

import java.time.Duration;
import java.time.Instant;

import fi.karivanttinen.maintenance.MaintenanceApplication;
import fi.karivanttinen.maintenance.MaintenanceWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class FplAssistantApplication {

    private static final Logger log = LoggerFactory.getLogger(FplAssistantApplication.class);

    public static void main(String[] args) {
        if (MaintenanceWindow.isActive(Instant.now())) {
            if (!runMaintenanceUntilWindowEnds(args)) {
                return;
            }
        }
        SpringApplication.run(FplAssistantApplication.class, args);
    }

    private static boolean runMaintenanceUntilWindowEnds(String[] args) {
        try (ConfigurableApplicationContext ignored =
                     SpringApplication.run(MaintenanceApplication.class, args)) {
            log.info("Maintenance mode is active until {}; Supabase-dependent components are off",
                    MaintenanceWindow.ENDS_AT);
            while (MaintenanceWindow.isActive(Instant.now())) {
                long remainingMillis = Duration.between(Instant.now(), MaintenanceWindow.ENDS_AT)
                        .toMillis();
                try {
                    Thread.sleep(Math.max(1, remainingMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
}
