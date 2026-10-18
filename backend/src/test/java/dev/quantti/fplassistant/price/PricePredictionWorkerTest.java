package dev.quantti.fplassistant.price;

import java.net.URI;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import dev.quantti.fplassistant.price.PricePredictionStore.*;
import static dev.quantti.fplassistant.price.PricePredictionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricePredictionWorkerTest {
    private final PricePredictionStore store = mock(PricePredictionStore.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("fpl.price-prediction.enabled", "true");
    private final MutableClock clock = new MutableClock(READ.minusSeconds(1));
    private final AtomicInteger calls = new AtomicInteger();
    private final Lease lease = new Lease(1, READ.plusSeconds(120));
    private PricePredictionHttp.Fetcher fetcher;

    @BeforeEach
    void setup() {
        when(store.readForecastView()).thenReturn(Optional.empty());
        when(store.readRecentView()).thenReturn(Optional.empty());
        when(store.acquireLease(any())).thenReturn(Optional.of(lease));
        when(store.leaseIsLive(lease)).thenReturn(true);
        when(store.publishForecast(any(), anyString(), any(), any())).thenReturn(PublicationResult.REPLACED);
        when(store.publishRecentChanges(any(), anyString(), any(), any(), any())).thenReturn(PublicationResult.REPLACED);
        fetcher = uri -> {
            calls.incrementAndGet();
            return new PricePredictionHttp.Response(200, null, bytes(uri.getPath().contains("bootstrap") ? bootstrap() : history()));
        };
    }

    private PricePredictionWorker worker() {
        return new PricePredictionWorker(store, new PricePredictionPermission(environment), fetcher, clock, SEASON,
                URI.create("http://localhost/bootstrap/"), URI.create("http://localhost/history/"));
    }

    @Test
    void sequentialCaptureNoRepeatAndNoAutomaticDurableEnable() {
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            worker.tick();
            assertThat(calls).hasValue(2);
            var order = inOrder(store);
            order.verify(store).acquireLease(any());
            order.verify(store).publishForecast(eq(lease), eq(PricePredictionWorker.fingerprint(bytes(bootstrap()))), any(), any());
            order.verify(store).publishRecentChanges(eq(lease), anyString(), any(), any(), any());
            order.verify(store).release(lease);
            verify(store, never()).enablePublishing();
            assertThat(worker.status().forecast().lastSuccess()).isEqualTo(READ);
        }
    }

    @Test
    void restartDoesNotReplayCurrentOrMissedSlots() {
        clock.now = READ.plusSeconds(180);
        try (var worker = worker()) {
            worker.tick();
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void failedHistoryPreservesSuccessfulForecast() {
        fetcher = uri -> new PricePredictionHttp.Response(uri.getPath().contains("bootstrap") ? 200 : 503,
                null, bytes(bootstrap()));
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(worker.status().forecast().lastSuccess()).isEqualTo(READ);
            assertThat(worker.status().history().error()).isEqualTo("HTTP_503");
            verify(store, never()).publishRecentChanges(any(), anyString(), any(), any(), any());
        }
    }

    @Test
    void rateLimitDefersWithoutBurstOrHistoryRequest() {
        fetcher = uri -> {
            calls.incrementAndGet();
            return new PricePredictionHttp.Response(429, "1800", new byte[0]);
        };
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            clock.now = READ.plusSeconds(600);
            worker.tick();
            clock.now = READ.plusSeconds(1800);
            worker.tick();
            assertThat(calls).hasValue(2);
            assertThat(worker.status().forecast().lastSuccess()).isNull();
        }
    }

    @Test
    void modeChangeDuringRequestPreventsPublicationAndDurablyRevokes() {
        fetcher = uri -> {
            calls.incrementAndGet();
            environment.setProperty("fpl.owned-sync.mode", "STAGING");
            return new PricePredictionHttp.Response(200, null, bytes(bootstrap()));
        };
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(calls).hasValue(1);
            verify(store, never()).publishForecast(any(), anyString(), any(), any());
            verify(store, times(1)).revokeAndDisable();
            environment.setProperty("fpl.owned-sync.mode", "OFF");
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void deniedPublicationCallbackLatchesStopAndRevokesBeforeNextTick() {
        when(store.publishForecast(any(), anyString(), any(), any())).thenAnswer(invocation -> {
            environment.setProperty("fpl.owned-sync.mode", "STAGING");
            java.util.function.BooleanSupplier permitted = invocation.getArgument(3);
            assertThat(permitted.getAsBoolean()).isFalse();
            environment.setProperty("fpl.owned-sync.mode", "OFF");
            return PublicationResult.REJECTED;
        });
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            verify(store).revokeAndDisable();
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(calls).hasValue(1);
            assertThat(worker.status().permitted()).isFalse();
        }
    }

    @Test
    void failedDurableRevocationRemainsStoppedAndRetriesAfterModeRestored() {
        doThrow(new IllegalStateException("unavailable")).doNothing().when(store).revokeAndDisable();
        fetcher = uri -> {
            calls.incrementAndGet();
            environment.setProperty("fpl.owned-sync.mode", "STAGING");
            return new PricePredictionHttp.Response(200, null, bytes(bootstrap()));
        };
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            environment.setProperty("fpl.owned-sync.mode", "OFF");
            worker.tick();
            verify(store, times(2)).revokeAndDisable();
            assertThat(calls).hasValue(1);
            assertThat(worker.status().permitted()).isFalse();
        }
    }

    @Test
    void durableRevocationOrSuccessorPreventsHttp() {
        when(store.leaseIsLive(lease)).thenReturn(false);
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void uncertainCommitMustReconcileBeforeAnyFurtherHttp() {
        when(store.publishForecast(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("uncertain"));
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(worker.status().reconciliationRequired()).isTrue();
            assertThat(worker.status().forecast().lastSuccess()).isNull();
            when(store.readForecastView()).thenThrow(new IllegalStateException("unavailable"));
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(calls).hasValue(1);
            assertThat(worker.status().reconciliationRequired()).isTrue();
            var view = new ForecastView(parse(bootstrap()), new Publication(1, READ, READ,
                    PricePredictionWorker.fingerprint(bytes(bootstrap()))), null, 2);
            doReturn(Optional.of(view)).when(store).readForecastView();
            when(store.acquireLease(any())).thenReturn(Optional.empty());
            clock.now = READ.plusSeconds(1200);
            worker.tick();
            assertThat(worker.status().reconciliationRequired()).isFalse();
            assertThat(worker.status().forecast().lastSuccess()).isEqualTo(READ);
        }
    }

    @Test
    void invalidSeasonOrMalformedBootstrapNeverPublishesOrRequestsHistory() {
        var payload = bootstrap();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("game_config").path("settings"))
                .put("static_content_url", "wrong-season");
        fetcher = uri -> {
            calls.incrementAndGet();
            return new PricePredictionHttp.Response(200, null, bytes(payload));
        };
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(calls).hasValue(1);
            assertThat(worker.status().forecast().error()).isEqualTo("INVALID_BOOTSTRAP");
            verify(store, never()).publishForecast(any(), anyString(), any(), any());
        }
    }

    @Test
    void retryAfterSupportsDatesAndBounds() {
        assertThat(PricePredictionWorker.retryDelay("Sun, 18 Oct 2026 17:00:00 GMT", READ)).isEqualTo(Duration.ofHours(1));
        assertThat(PricePredictionWorker.retryDelay("99999999", READ)).isEqualTo(Duration.ofHours(6));
        assertThat(PricePredictionWorker.retryDelay("bad", READ)).isEqualTo(Duration.ofMinutes(10));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shutdownDrainsInflightRequestEvenWhenDurableRevokeFails(boolean revocationFails) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var revoked = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            revoked.countDown();
            if (revocationFails) throw new IllegalStateException("unavailable");
            return null;
        }).when(store).revokeAndDisable();
        fetcher = uri -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new PricePredictionHttp.Response(200, null, bytes(bootstrap()));
        };
        var worker = worker();
        clock.now = READ;
        var tick = java.util.concurrent.CompletableFuture.runAsync(worker::tick, task -> Thread.ofPlatform().start(task));
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var close = java.util.concurrent.CompletableFuture.runAsync(worker::close, task -> Thread.ofPlatform().start(task));
        try {
            assertThat(revoked.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(close.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        tick.get(3, java.util.concurrent.TimeUnit.SECONDS);
        if (revocationFails) {
            assertThatThrownBy(() -> close.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
        } else close.get(3, java.util.concurrent.TimeUnit.SECONDS);
        verify(store, never()).publishForecast(any(), anyString(), any(), any());
        assertThat(calls).hasValue(1);
    }

    @Test
    void uncommittedUncertaintyClearsWithoutClaimingSuccess() {
        when(store.publishForecast(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("uncertain"));
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            when(store.acquireLease(any())).thenReturn(Optional.empty());
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(worker.status().reconciliationRequired()).isFalse();
            assertThat(worker.status().forecast().lastSuccess()).isNull();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void uncertainHistoryDoesNotEraseForecastSuccessAndBlocksNextHttp() {
        when(store.publishRecentChanges(any(), anyString(), any(), any(), any())).thenThrow(new IllegalStateException("uncertain"));
        try (var worker = worker()) {
            clock.now = READ;
            worker.tick();
            assertThat(worker.status().forecast().lastSuccess()).isEqualTo(READ);
            assertThat(worker.status().history().lastSuccess()).isNull();
            when(store.readRecentView()).thenThrow(new IllegalStateException("unavailable"));
            clock.now = READ.plusSeconds(600);
            worker.tick();
            assertThat(calls).hasValue(2);
            assertThat(worker.status().forecast().error()).isNull();
            assertThat(worker.status().reconciliationRequired()).isTrue();
        }
    }

    @Test
    void normalPhaseSkips1450AndEntersFrequentAt1500() {
        clock.now = Instant.parse("2026-10-18T14:49:59Z");
        try (var worker = worker()) {
            clock.now = Instant.parse("2026-10-18T14:50:00Z");
            worker.tick();
            assertThat(calls).hasValue(0);
            clock.now = Instant.parse("2026-10-18T15:00:00Z");
            worker.tick();
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void persistedCheckedTimeDoesNotMakeStaleSourceUsableInStatus() {
        var snapshot = parse(bootstrap());
        clock.now = READ.plusSeconds(3599);
        var view = new ForecastView(snapshot, new Publication(1, READ.plusSeconds(3600), READ,
                PricePredictionWorker.fingerprint(bytes(bootstrap()))), null, 2);
        when(store.readForecastView()).thenReturn(Optional.of(view));
        when(store.acquireLease(any())).thenReturn(Optional.empty());
        try (var worker = worker()) {
            clock.now = READ.plusSeconds(3600);
            worker.tick();
            assertThat(worker.status().checkedAt()).isEqualTo(clock.now);
            assertThat(worker.status().fetchedAt()).isEqualTo(READ);
            assertThat(worker.status().usablePlayers()).isZero();
        }
    }

    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
