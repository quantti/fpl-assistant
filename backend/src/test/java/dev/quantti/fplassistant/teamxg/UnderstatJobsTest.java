package dev.quantti.fplassistant.teamxg;

import java.time.LocalDate;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.*;

class UnderstatJobsTest {
    @Test
    void disabledSchedulerAndNormalStartupNeverReadOrWriteAnything() {
        var sync = mock(UnderstatSyncService.class);
        var seasons = mock(SeasonResolver.class);
        var jobs = new UnderstatJobs(sync, seasons, false);
        jobs.captureScheduled();
        jobs.run(new DefaultApplicationArguments());
        verifyNoInteractions(sync, seasons);
    }

    @Test
    void usesPlayingSeasonRatherThanMetricFallback() {
        var sync = mock(UnderstatSyncService.class);
        var seasons = mock(SeasonResolver.class);
        when(seasons.resolve()).thenReturn(new SeasonContext(1, "2025-26", false,
                "2026-27", LocalDate.of(2026, 8, 21)));
        new UnderstatJobs(sync, seasons, true).captureScheduled();
        verify(sync).sync(2026);
    }

    @Test
    void scheduledFailureDoesNotTriggerAnotherFetchOrChangeWriters() {
        var sync = mock(UnderstatSyncService.class);
        var seasons = mock(SeasonResolver.class);
        when(seasons.resolve()).thenReturn(new SeasonContext(2, "2026-27", true,
                "2026-27", LocalDate.of(2026, 8, 21)));
        when(sync.sync(2026)).thenThrow(new IllegalStateException("offline"));
        new UnderstatJobs(sync, seasons, true).captureScheduled();
        verify(sync).sync(2026);
        verifyNoMoreInteractions(sync);
    }
}
