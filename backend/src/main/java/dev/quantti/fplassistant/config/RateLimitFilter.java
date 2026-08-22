package dev.quantti.fplassistant.config;

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
 * Fixed-window per-IP rate limit for the endpoints that cost something outside this process —
 * the ones in {@link #RATE_LIMITED_PATHS}. In-memory only — good enough for a single Fly.io
 * machine; revisit if the app ever scales out.
 *
 * <p>One counter per IP, shared across every listed path rather than per path: a flooded chat
 * from an address also exhausts that address's sign-up budget, and vice versa. That is the
 * intended shape — the limit blunts a script and the real gate (an invite code, a quota) is
 * elsewhere — but it is a coupling to know when a path is added.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Paths where one request costs something outside this process: an LLM call, or a request to
     * FPL. An unlisted endpoint is unlimited, which is why the profile write is here — it makes
     * one outbound call to fantasy.premierleague.com per invocation, and that is the same host
     * the 02:00 price capture depends on.
     *
     * <p>Sign-up qualifies twice over: it calls Supabase, and it creates accounts. Note what this
     * buys, though — the limit below is a shared constant, not a number chosen for account
     * creation. It blunts a script; the invite code and the account cap are the actual gate.
     */
    private static final java.util.Set<String> RATE_LIMITED_PATHS =
            java.util.Set.of("/api/chat", "/api/profile/fpl-team-id", "/api/auth/signup");

    static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final long WINDOW_MS = 60_000;
    // Guard against unbounded growth from address-spoofed traffic.
    private static final int MAX_TRACKED_IPS = 10_000;

    // value: [windowStartMillis, requestCount]
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!RATE_LIMITED_PATHS.contains(request.getRequestURI())) {
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
