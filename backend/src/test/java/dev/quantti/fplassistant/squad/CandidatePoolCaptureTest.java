package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.Optional;

import dev.quantti.fplassistant.tools.DailyPriceSource;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CandidatePoolCaptureTest {

    @Test
    void mapsTheSharedSourcesCaptureWithoutChangingItsSeasonOrDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SeasonResolver seasonResolver = mock(SeasonResolver.class);
        DailyPriceSource daily = mock(DailyPriceSource.class);
        CandidatePool pool = new CandidatePool(jdbc, seasonResolver, daily, "unused_table");
        LocalDate date = LocalDate.of(2026, 9, 8);
        when(daily.latestCapture())
                .thenReturn(Optional.of(new DailyPriceSource.Capture("2026-27", date)));

        assertThat(pool.latestCapture())
                .contains(new CandidatePool.Capture("2026-27", date));
        verify(daily).latestCapture();
        verifyNoMoreInteractions(daily);
        verifyNoInteractions(jdbc, seasonResolver);
    }

    @Test
    void keepsAnEmptySharedCaptureEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SeasonResolver seasonResolver = mock(SeasonResolver.class);
        DailyPriceSource daily = mock(DailyPriceSource.class);
        CandidatePool pool = new CandidatePool(jdbc, seasonResolver, daily, "unused_table");
        when(daily.latestCapture()).thenReturn(Optional.empty());

        assertThat(pool.latestCapture()).isEmpty();
        verify(daily).latestCapture();
        verifyNoMoreInteractions(daily);
        verifyNoInteractions(jdbc, seasonResolver);
    }
}
