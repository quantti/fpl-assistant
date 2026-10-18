package dev.quantti.fplassistant.price;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

public final class PricePredictionHttp implements AutoCloseable {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines;
    private final Duration timeout;

    public record Response(int status, String retryAfter, byte[] body) { }

    @FunctionalInterface
    public interface Fetcher {
        Response get(URI uri) throws Exception;
    }

    public PricePredictionHttp(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("HTTP timeout must be positive and at most 30 seconds");
        }
        this.timeout = timeout;
        client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setAuthenticationEnabled(false)
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis())).build()).build();
        deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "price-prediction-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Response get(URI uri) throws IOException {
        HttpGet request = new HttpGet(uri);
        var deadline = deadlines.schedule(request::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return client.execute(request, response -> {
                var retry = response.getFirstHeader("Retry-After");
                byte[] body = new byte[0];
                if (response.getCode() == 200 && response.getEntity() != null) {
                    if (response.getEntity().getContentLength() > MAX_BYTES) {
                        request.cancel();
                        throw new IOException("Body limit");
                    }
                    body = response.getEntity().getContent().readNBytes(MAX_BYTES + 1);
                    if (body.length > MAX_BYTES) {
                        request.cancel();
                        throw new IOException("Body limit");
                    }
                } else {
                    request.cancel();
                }
                return new Response(response.getCode(), retry == null ? null : retry.getValue(), body);
            });
        } finally {
            deadline.cancel(false);
        }
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
