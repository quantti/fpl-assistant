package fi.karivanttinen.fplassistant.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void allowsUpToTheLimitWithinOneWindow() {
        RateLimitFilter filter = new RateLimitFilter();
        long now = 1_000_000L;

        for (int i = 0; i < RateLimitFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            assertThat(filter.allow("1.2.3.4", now)).as("request %d", i + 1).isTrue();
        }
        assertThat(filter.allow("1.2.3.4", now)).isFalse();
    }

    @Test
    void limitIsPerIp() {
        RateLimitFilter filter = new RateLimitFilter();
        long now = 1_000_000L;

        for (int i = 0; i < RateLimitFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            filter.allow("1.2.3.4", now);
        }
        assertThat(filter.allow("1.2.3.4", now)).isFalse();
        assertThat(filter.allow("5.6.7.8", now)).isTrue();
    }

    @Test
    void windowResetsAfterAMinute() {
        RateLimitFilter filter = new RateLimitFilter();
        long now = 1_000_000L;

        for (int i = 0; i < RateLimitFilter.MAX_REQUESTS_PER_MINUTE + 5; i++) {
            filter.allow("1.2.3.4", now);
        }
        assertThat(filter.allow("1.2.3.4", now)).isFalse();
        assertThat(filter.allow("1.2.3.4", now + 60_001)).isTrue();
    }
}
