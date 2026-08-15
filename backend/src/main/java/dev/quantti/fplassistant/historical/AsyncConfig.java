package dev.quantti.fplassistant.historical;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} so {@link StartupSyncRunner} genuinely runs off the request path — the
 * only mechanism in this codebase so far that needs background execution, hence scoped to this
 * package rather than a top-level config class.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
