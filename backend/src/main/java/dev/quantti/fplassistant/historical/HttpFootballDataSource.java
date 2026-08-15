package dev.quantti.fplassistant.historical;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches the current Premier League season's matches from football-data.org's free tier
 * (10 requests/minute; one request covers the whole season, so a single sync run never
 * approaches that limit).
 *
 * <p>Unlike {@link HttpVaastavSource}, this has an explicit connect/read timeout — this client
 * runs from {@code StartupSyncRunner} off the request path (per the Phase 3 spec's Task 5
 * design), and an unbounded client there would tie up a background thread indefinitely on a
 * hang rather than failing and logging, which is precisely the "must not poison the boot"
 * failure mode that design exists to prevent.
 */
@Component
public class HttpFootballDataSource implements FootballDataSource {

    private static final String URL = "https://api.football-data.org/v4/competitions/PL/matches";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String apiKey;

    public HttpFootballDataSource(@Value("${fpl.historical.football-data-api-key:}") String apiKey) {
        this.apiKey = apiKey;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String fetchCurrentSeasonMatches() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FOOTBALL_DATA_API_KEY is not set — cannot sync");
        }
        try {
            String body = restClient.get()
                    .uri(URL)
                    .header("X-Auth-Token", apiKey)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("empty response from " + URL);
            }
            return body;
        } catch (HttpClientErrorException.TooManyRequests e) {
            // The free tier allows 10 requests/minute; a single sync is one request, but a
            // rate-limit must say so rather than read like a network error — it means "wait and
            // retry on the next startup", not "the network is down".
            throw new IllegalStateException("football-data.org rate limit exceeded (429)"
                    + " — the free tier allows 10 requests/minute; the next startup will retry", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("failed to fetch " + URL, e);
        }
    }
}
