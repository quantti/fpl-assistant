package fi.karivanttinen.fplassistant.price;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the schedule itself, not the sync.
 *
 * <p>The cron string and its zone live in {@code application.yml}, so a typo there would not
 * fail the build — it would fail at 02:00, silently, as a day missing from the price series
 * that nobody notices until someone asks why a price is stale. These read the real configured
 * values rather than literals, so the test fails if the configuration drifts from the intent.
 *
 * <p>The intent, from the owner's instruction and the research behind it: FPL applies price
 * changes at 00:00 UK for 2026/27 (moved from ~01:30), and we read two hours later.
 */
@SpringBootTest
class DailyPriceScheduleTest {

    @Value("${fpl.daily-price.fetch-at}")
    private String cron;

    @Value("${fpl.daily-price.fetch-zone}")
    private String zone;

    @Value("${fpl.daily-price.check-at}")
    private String checkCron;

    @Test
    void cronIsValidAndFiresAtTwoInTheMorning() {
        assertThat(CronExpression.isValidExpression(cron))
                .as("a malformed cron only fails at 02:00, in production")
                .isTrue();

        ZonedDateTime from = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, ZoneId.of(zone));
        ZonedDateTime next = CronExpression.parse(cron).next(from);

        assertThat(next).isNotNull();
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isZero();
    }

    @Test
    void staysTwoHoursAfterMidnightUkThroughTheDaylightSavingSwitch() {
        ZoneId uk = ZoneId.of(zone);
        CronExpression expression = CronExpression.parse(cron);

        // British Summer Time (UTC+1) — late October, before the clocks go back.
        ZonedDateTime summer = expression.next(ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, uk));
        // Greenwich Mean Time (UTC+0) — after the switch on 2026-10-25.
        ZonedDateTime winter = expression.next(ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, uk));

        assertThat(summer).isNotNull();
        assertThat(winter).isNotNull();
        // Both land at 02:00 local, which is the point: the gap to FPL's 00:00 UK price change
        // stays two hours all year. A UTC cron would drift to 03:00 local for half the season.
        assertThat(summer.getHour()).isEqualTo(2);
        assertThat(winter.getHour()).isEqualTo(2);
        assertThat(Duration.between(summer.toLocalDate().atStartOfDay(uk), summer))
                .isEqualTo(Duration.ofHours(2));
        assertThat(Duration.between(winter.toLocalDate().atStartOfDay(uk), winter))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void zoneIsUkRatherThanTheContainerDefault() {
        // The Fly container runs UTC; the whole point of naming a zone is not to inherit it.
        assertThat(zone).isEqualTo("Europe/London");
    }

    @Test
    void freshnessCheckRunsAfterTheCaptureWindow() {
        assertThat(CronExpression.isValidExpression(checkCron))
                .as("a malformed check cron only fails at runtime")
                .isTrue();

        // Three checks a day, all after the 02:00 UK capture — a check before 02:00 could
        // only ever report yesterday's row as stale, which is the healthy state.
        ZonedDateTime from = ZonedDateTime.of(2026, 9, 15, 0, 0, 0, 0, ZoneId.of(zone));
        ZonedDateTime next = CronExpression.parse(checkCron).next(from);
        assertThat(next).isNotNull();
        assertThat(next.getHour()).isIn(6, 12, 18);
    }
}
