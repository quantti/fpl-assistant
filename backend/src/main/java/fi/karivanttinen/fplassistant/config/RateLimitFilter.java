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
        if (!request.getRequestURI().equals("/api/chat")) {
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

    /**
     * The first X-Forwarded-For entry is attacker-controlled: Fly's proxy APPENDS the real
     * client address to whatever the client already sent, so keying on the first entry hands
     * out a fresh rate-limit bucket per forged header. Prefer Fly-Client-IP (set by the proxy,
     * not forgeable through it), then the LAST forwarded entry, then the socket address.
     */
    static String clientIp(HttpServletRequest request) {
        String flyClientIp = request.getHeader("Fly-Client-IP");
        if (flyClientIp != null && !flyClientIp.isBlank()) {
            return flyClientIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] entries = forwarded.split(",");
            return entries[entries.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
