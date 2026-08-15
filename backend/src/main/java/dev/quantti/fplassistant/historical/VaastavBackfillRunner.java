package dev.quantti.fplassistant.historical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot backfill entry point. Does nothing on a normal start; when the application is
 * launched with {@code --backfill} it imports 2019-20 through 2025-26 from vaastav and exits
 * the JVM so no web server lingers after the job — same shape as {@code IngestionRunner}.
 *
 * <pre>
 * ./gradlew bootRun --args="--backfill"
 * java -jar app.jar --backfill
 * </pre>
 */
@Component
public class VaastavBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VaastavBackfillRunner.class);

    private final VaastavBackfillService backfillService;

    public VaastavBackfillRunner(VaastavBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            return;
        }
        log.info("--backfill given: importing {} vaastav seasons",
                VaastavBackfillService.SEASONS.size());
        backfillService.backfill();
        log.info("Backfill complete; exiting");
        System.exit(0);
    }
}
