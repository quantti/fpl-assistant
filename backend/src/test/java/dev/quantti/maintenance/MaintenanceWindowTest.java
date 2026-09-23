package dev.quantti.maintenance;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceWindowTest {

    @Test
    void endsExactlyAtMidnightInFinland() {
        assertThat(MaintenanceWindow.isActive(MaintenanceWindow.ENDS_AT.minus(Duration.ofMillis(1))))
                .isTrue();
        assertThat(MaintenanceWindow.isActive(MaintenanceWindow.ENDS_AT)).isFalse();
    }
}
