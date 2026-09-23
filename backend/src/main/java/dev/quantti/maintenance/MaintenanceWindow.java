package dev.quantti.maintenance;

import java.time.Instant;

/**
 * Temporary Supabase maintenance window. Finland is UTC+3 on 26 September 2026, so service
 * resumes at midnight Finnish time.
 */
public final class MaintenanceWindow {

    public static final Instant ENDS_AT = Instant.parse("2026-09-25T21:00:00Z");

    private MaintenanceWindow() {}

    public static boolean isActive(Instant now) {
        return now.isBefore(ENDS_AT);
    }
}
