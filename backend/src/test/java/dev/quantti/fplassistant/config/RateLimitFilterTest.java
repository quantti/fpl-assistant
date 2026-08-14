package dev.quantti.fplassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void spoofedForwardedForCannotChooseTheBucket() {
        // Fly appends the real client to any client-sent X-Forwarded-For, so the last
        // entry is the trustworthy one — keying on the first would hand a forged header
        // a fresh rate-limit bucket per request.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        request.setRemoteAddr("172.16.0.1");

        assertThat(RateLimitFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void flyClientIpHeaderWinsWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Fly-Client-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(RateLimitFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutProxyHeadersTheSocketAddressIsUsed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        assertThat(RateLimitFilter.clientIp(request)).isEqualTo("192.168.1.50");
    }

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
