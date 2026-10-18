package fi.karivanttinen.fplassistant.teamxg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sync-time only; two league requests replace no player-level data collection. */
@Component
public class UnderstatClient {
    private static final int MAX_BYTES = 5_000_000;
    private final HttpClient client;
    private final URI baseUrl;

    public UnderstatClient(@Value("${fpl.understat.base-url:https://understat.com}") String baseUrl) {
        this.baseUrl = URI.create(baseUrl);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public String fetch(int year) {
        UnderstatParser.season(year);
        var request = HttpRequest.newBuilder(baseUrl.resolve("/getLeagueData/EPL/" + year))
                .header("X-Requested-With", "XMLHttpRequest").GET().build();
        var exchange = client.sendAsync(request,
                BodyHandlers.limiting(BodyHandlers.ofByteArray(), MAX_BYTES));
        try {
            // Await the complete bounded body, not just headers or the next readable byte.
            var response = exchange.get(30, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Understat HTTP " + response.statusCode());
            }
            byte[] raw = response.body();
            if (raw.length >= 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    raw = limited(gzip);
                }
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Understat " + UnderstatParser.season(year) + " fetch failed", e);
        } finally {
            exchange.cancel(true);
        }
    }

    private static byte[] limited(InputStream input) throws java.io.IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Understat payload exceeds 5 MB");
        return bytes;
    }
}
