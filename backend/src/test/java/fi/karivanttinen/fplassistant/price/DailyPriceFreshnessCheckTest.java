package fi.karivanttinen.fplassistant.price;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class DailyPriceFreshnessCheckTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DailyPriceEmailAlert email = mock(DailyPriceEmailAlert.class);
    private final LocalDate today = LocalDate.of(2026, 7, 15);

    @Test
    void staleCaptureSendsLatestAndExpectedUkDates() {
        check(today.minusDays(2), "2026-07-15T05:00:00Z");
        verify(email).send(today.minusDays(2), today);
    }

    @Test
    void emptyCaptureSendsMissingLatestDate() {
        check(null, "2026-07-15T05:00:00Z");
        verify(email).send(null, today);
    }

    @Test
    void freshAndFutureCapturesDoNotSend() {
        check(today, "2026-07-15T05:00:00Z");
        check(today.plusDays(1), "2026-07-15T05:00:00Z");
        verifyNoInteractions(email);
    }

    @Test
    void usesUkCutoverRatherThanUtcOrJvmTimezone() {
        check(today.minusDays(1), "2026-07-15T00:59:59Z");
        verifyNoInteractions(email);
        check(today.minusDays(1), "2026-07-15T01:00:00Z");
        verify(email).send(today.minusDays(1), today);
    }

    @Test
    void repeatsAtEachCheckWhileStaleThenStopsOnRecovery() {
        check(today.minusDays(1), "2026-07-15T05:00:00Z");
        check(today.minusDays(1), "2026-07-15T11:00:00Z");
        check(today, "2026-07-15T17:00:00Z");
        verify(email, times(2)).send(today.minusDays(1), today);
        verifyNoMoreInteractions(email);
    }

    @Test
    void databaseFailureDoesNotSendOrEscape() {
        when(jdbc.queryForObject(anyString(), eq(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatCode(() -> checker("2026-07-15T05:00:00Z").checkFreshness())
                .doesNotThrowAnyException();
        verifyNoInteractions(email);
    }

    private void check(LocalDate latest, String now) {
        when(jdbc.queryForObject("SELECT MAX(price_date) FROM fpl_daily_price_test", LocalDate.class))
                .thenReturn(latest);
        checker(now).checkFreshness();
    }

    private DailyPriceFreshnessCheck checker(String now) {
        return new DailyPriceFreshnessCheck(jdbc, "fpl_daily_price_test", email,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }
}
