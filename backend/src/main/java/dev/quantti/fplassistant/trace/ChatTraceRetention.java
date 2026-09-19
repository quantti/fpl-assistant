package dev.quantti.fplassistant.trace;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes trace rows fourteen days after the turn began. */
@Component
@DependsOn("chatTraceSchema")
public class ChatTraceRetention {

    static final Duration RETENTION = Duration.ofDays(14);
    private static final Logger log = LoggerFactory.getLogger(ChatTraceRetention.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final Clock clock;

    public ChatTraceRetention(JdbcTemplate jdbc,
                              @Value("${fpl.trace.table-name}") String table,
                              ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.table = table;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        purgeSafely();
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void hourly() {
        purgeSafely();
    }

    int purge() {
        return jdbc.update("DELETE FROM " + table + " WHERE created_at < ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
    }

    private void purgeSafely() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                log.info("Deleted {} expired chat traces", deleted);
            }
        } catch (RuntimeException e) {
            // Trace cleanup is operational hygiene, not a reason to take chat offline.
            log.warn("Could not delete expired chat traces", e);
        }
    }
}
