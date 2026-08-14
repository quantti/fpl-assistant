package fi.karivanttinen.fplassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot ingestion entry point. Does nothing on a normal start; when the application is
 * launched with {@code --ingest} it truncates the vector store, reloads it from
 * {@code classpath:docs}, and exits the JVM so no web server lingers after the job.
 *
 * <pre>
 * ./gradlew bootRun --args="--ingest"
 * java -jar app.jar --ingest
 * </pre>
 */
@Component
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final IngestionService ingestionService;

    public IngestionRunner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("ingest")) {
            return;
        }
        log.info("--ingest given: truncating the vector store and reloading from classpath:docs");
        ingestionService.ingest();
        log.info("Ingestion complete; exiting");
        System.exit(0);
    }
}
