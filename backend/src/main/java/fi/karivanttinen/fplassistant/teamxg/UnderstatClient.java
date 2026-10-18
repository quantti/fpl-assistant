package fi.karivanttinen.fplassistant.teamxg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sync-time only; two league requests replace no player-level data collection. */
@Component
public class UnderstatClient {
    private static final int MAX_BYTES = 5_000_000;
    private final RestClient client;

    public UnderstatClient(@Value("${fpl.understat.base-url:https://understat.com}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(20_000);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Requested-With", "XMLHttpRequest").build();
    }

    public String fetch(int year) {
        UnderstatParser.season(year);
        try {
            return client.get().uri("/getLeagueData/EPL/{year}", year).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("Understat HTTP " + response.getStatusCode().value());
                }
                byte[] raw = limited(response.getBody());
                if (raw.length >= 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                        raw = limited(gzip);
                    }
                }
                return new String(raw, StandardCharsets.UTF_8);
            });
        } catch (Exception e) {
            throw new IllegalStateException("Understat " + UnderstatParser.season(year) + " fetch failed", e);
        }
    }

    private static byte[] limited(InputStream input) throws java.io.IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Understat payload exceeds 5 MB");
        return bytes;
    }
}
