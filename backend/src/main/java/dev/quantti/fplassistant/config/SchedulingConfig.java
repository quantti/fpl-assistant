package dev.quantti.fplassistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} for the daily FPL price capture
 * ({@code DailyPriceScheduler}). Top-level rather than package-scoped, unlike
 * {@code AsyncConfig}: a clock-driven trigger is an application-wide capability, and the next
 * thing that wants one should not have to discover it inside a feature package.
 *
 * <p><b>This only works because the Fly machine stays up.</b> {@code fly.toml} sets
 * {@code min_machines_running = 1} and {@code auto_stop_machines = false}; under the previous
 * stop-when-idle configuration a 02:00 cron would simply never have fired, because nothing
 * would have been running at 02:00 to fire it. If that setting is ever reverted, the price
 * capture stops silently — see the freshness note in {@code docs/STATUS.md}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
