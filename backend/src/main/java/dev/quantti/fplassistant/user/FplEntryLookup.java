package dev.quantti.fplassistant.user;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Checks that an FPL team id exists, by asking FPL.
 *
 * <p>A <b>documented exception</b> to this project's rule that external APIs are hit only at sync
 * time. The rule exists to keep chat off the network; this is a user-initiated write on a
 * different path, and storing an id nobody validated means the first sign that it is wrong comes
 * much later, from a tool that finds no squad.
 *
 * <p>Bounded on purpose: short timeouts, no retries, and a failure to reach FPL is reported as
 * its own outcome rather than folded into "no such team". Those two must stay distinguishable —
 * telling a user their team id is wrong when FPL is merely down sends them looking for a mistake
 * they did not make.
 */
@Component
public class FplEntryLookup {

    private static final Logger log = LoggerFactory.getLogger(FplEntryLookup.class);

    /** Tighter than the sync clients': a person is waiting on this response. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    public enum Result {
        /** FPL knows this entry id. */
        EXISTS,
        /** FPL answered, and says there is no such entry. */
        NOT_FOUND,
        /** FPL could not be reached or answered with something unusable. Says nothing about the id. */
        UNAVAILABLE
    }

    private final RestClient restClient;
    private final String entryUrl;

    public FplEntryLookup(RestClient.Builder restClientBuilder,
                          @Value("${fpl.user.entry-url}") String entryUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.entryUrl = entryUrl;
    }

    public Result lookup(int entryId) {
        try {
            return restClient.get()
                    .uri(entryUrl.formatted(entryId))
                    .retrieve()
                    // 404 is an answer, not a failure: FPL is telling us the id is wrong.
                    .onStatus(status -> status.value() == 404, (request, response) -> { })
                    .toBodilessEntity()
                    .getStatusCode().value() == 404 ? Result.NOT_FOUND : Result.EXISTS;
        } catch (RestClientException e) {
            // Deliberately not logging the entry id at WARN with anything identifying: an id
            // links an account to a real person's public FPL profile.
            log.warn("Could not reach FPL to validate a team id; reporting it as unavailable", e);
            return Result.UNAVAILABLE;
        }
    }
}
