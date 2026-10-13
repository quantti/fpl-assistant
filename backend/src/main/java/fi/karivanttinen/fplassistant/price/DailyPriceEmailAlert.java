package fi.karivanttinen.fplassistant.price;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Opt-in operational mail; independent of Supabase Auth and application health. */
@Component
public class DailyPriceEmailAlert {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceEmailAlert.class);

    private final RestClient client;
    private final boolean enabled;
    private final String apiKey;
    private final String from;
    private final String to;

    @Autowired
    public DailyPriceEmailAlert(RestClient.Builder builder,
            @Value("${fpl.daily-price.email-alert.enabled:false}") boolean enabled,
            @Value("${fpl.daily-price.email-alert.api-key:}") String apiKey,
            @Value("${fpl.daily-price.email-alert.from:}") String from,
            @Value("${fpl.daily-price.email-alert.to:}") String to) {
        this(builder, enabled, apiKey, from, to, "https://api.resend.com", Duration.ofSeconds(5));
    }

    DailyPriceEmailAlert(RestClient.Builder builder, boolean enabled, String apiKey,
                        String from, String to, String baseUrl, Duration timeout) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.from = from;
        this.to = to;
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void send(LocalDate latest, LocalDate expected) {
        if (!enabled) return;
        if (apiKey.isBlank() || from.isBlank() || to.isBlank()) {
            log.error("Daily price email alert configuration is incomplete; no email attempted");
            return;
        }
        String text = """
                FPL Sidekick: daily price capture is missing or stale.
                Expected capture date (Europe/London, after 02:00): %s
                Latest stored capture date: %s

                Check the Fly machine, capture logs and daily-price/owned-sync configuration.
                Follow the sync operations runbook before changing writers or running a manual sync.
                Reminders repeat at 06:00, 12:00 and 18:00 Europe/London while data remains missing.
                """.formatted(expected, latest == null ? "no capture" : latest);
        try {
            int status = client.post().uri("/emails")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", from, "to", List.of(to),
                            "subject", "FPL Sidekick: missing daily price capture — " + expected,
                            "text", text))
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status >= 200 && status < 300) {
                log.info("Daily price email alert accepted by Resend for expected capture {}", expected);
            } else {
                log.error("Daily price email alert failed (HTTP {}); next attempt at the next freshness check",
                        status);
            }
        } catch (RestClientException | IllegalArgumentException e) {
            // Provider bodies and exception messages can contain credentials or recipient data.
            log.error("Daily price email alert failed (transport/client error); next attempt at the next freshness check");
        }
    }
}
