package fi.karivanttinen.fplassistant.teamxg;

import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnderstatJobs implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UnderstatJobs.class);
    private final UnderstatSyncService sync;
    private final SeasonResolver seasons;
    private final boolean scheduled;

    public UnderstatJobs(UnderstatSyncService sync, SeasonResolver seasons,
                         @Value("${fpl.understat.scheduled:false}") boolean scheduled) {
        this.sync = sync;
        this.seasons = seasons;
        this.scheduled = scheduled;
    }

    @Scheduled(cron = "${fpl.understat.fetch-at:0 20 2 * * *}", zone = "Europe/London")
    public void captureScheduled() {
        if (!scheduled) return;
        try {
            log.info("Understat captured {} completed matches", capture());
        } catch (Exception e) {
            log.error("Understat capture failed; previous stored capture retained", e);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sync-understat")) return;
        try {
            log.info("--sync-understat captured {} completed matches", capture());
            System.exit(0);
        } catch (Exception e) {
            log.error("--sync-understat failed", e);
            System.exit(1);
        }
    }

    private int capture() {
        // A fallback metric season is not the season being written.
        String playingSeason = seasons.resolve().currentSeasonCode();
        return sync.sync(Integer.parseInt(playingSeason.substring(0, 4)));
    }
}
