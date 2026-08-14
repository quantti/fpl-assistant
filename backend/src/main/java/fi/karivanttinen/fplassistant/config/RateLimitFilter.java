package fi.karivanttinen.fplassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window per-IP rate limit for the unauthenticated chat endpoint. In-memory only —
 * good enough for a single Fly.io machine; revisit if the app ever scales out.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final long WINDOW_MS = 60_000;
    // Guard against unbounded growth from address-spoofed traffic.
    private static final int MAX_TRACKED_IPS = 10_000;

    // value: [windowStartMillis, requestCount]
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/chat")) {
            chain.doFilter(request, response);
            return;
        }
        if (!allow(clientIp(request), System.currentTimeMillis())) {
            response.setStatus(429);
            response.setContentType("text/plain");
            response.getWriter().write("Rate limit exceeded. Try again in a minute.");
            return;
        }
        chain.doFilter(request, response);
    }

    boolean allow(String ip, long nowMillis) {
        if (counters.size() > MAX_TRACKED_IPS) {
            counters.clear();
        }
        long[] counter = counters.compute(ip, (k, v) -> {
            if (v == null || nowMillis - v[0] >= WINDOW_MS) {
                return new long[]{nowMillis, 1};
            }
            v[1]++;
            return v;
        });
        return counter[1] <= MAX_REQUESTS_PER_MINUTE;
    }

    /** Fly's proxy puts the real client address first in X-Forwarded-For. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
