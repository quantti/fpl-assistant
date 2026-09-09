package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.DailyPriceSource;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CandidatePoolCaptureTest {

    @Test
    void mapsTheSharedSourcesCaptureWithoutChangingItsSeasonOrDate() {
        DailyPriceSource daily = mock(DailyPriceSource.class);
        RankingMetrics rankingMetrics = mock(RankingMetrics.class);
        CandidatePool pool = new CandidatePool(daily, rankingMetrics);
        LocalDate date = LocalDate.of(2026, 9, 8);
        when(daily.latestCapture())
                .thenReturn(Optional.of(new DailyPriceSource.Capture("2026-27", date)));

        assertThat(pool.latestCapture())
                .contains(new CandidatePool.Capture("2026-27", date));
        verify(daily).latestCapture();
        verifyNoMoreInteractions(daily);
        verifyNoInteractions(rankingMetrics);
    }

    @Test
    void keepsAnEmptySharedCaptureEmpty() {
        DailyPriceSource daily = mock(DailyPriceSource.class);
        RankingMetrics rankingMetrics = mock(RankingMetrics.class);
        CandidatePool pool = new CandidatePool(daily, rankingMetrics);
        when(daily.latestCapture()).thenReturn(Optional.empty());

        assertThat(pool.latestCapture()).isEmpty();
        verify(daily).latestCapture();
        verifyNoMoreInteractions(daily);
        verifyNoInteractions(rankingMetrics);
    }
}
