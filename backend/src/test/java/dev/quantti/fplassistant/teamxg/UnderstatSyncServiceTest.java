package dev.quantti.fplassistant.teamxg;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UnderstatSyncServiceTest {
    private final UnderstatClient client = mock(UnderstatClient.class);
    private final UnderstatStore store = mock(UnderstatStore.class);
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private final UnderstatSyncService sync = new UnderstatSyncService(client, store,
            new StaticListableBeanFactory(Map.of("clock", Clock.fixed(NOW, ZoneOffset.UTC)))
                    .getBeanProvider(Clock.class));

    private UnderstatStore.Capture empty(String season) {
        return new UnderstatStore.Capture(season, NOW, List.of());
    }

    @Test
    void fetchesExactlyTwoLeagueSeasonsAndPublishesEachAtomically() {
        when(client.fetch(2025)).thenReturn("{\"dates\":[]}");
        when(client.fetch(2026)).thenReturn("{\"dates\":[]}");
        assertThat(sync.sync(2026)).isZero();
        var order = inOrder(client, store);
        order.verify(client).fetch(2025);
        order.verify(store).replace(List.of(empty("2025-26")));
        order.verify(client).fetch(2026);
        order.verify(store).replace(List.of(empty("2026-27")));
        verifyNoMoreInteractions(client, store);
    }

    @Test
    void currentFetchFailureDoesNotDiscardAValidArchiveCapture() {
        when(client.fetch(2025)).thenReturn("{\"dates\":[]}");
        when(client.fetch(2026)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> sync.sync(2026)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed seasons retained");
        verify(store).replace(List.of(empty("2025-26")));
        verifyNoMoreInteractions(store);
    }

    @Test
    void invalidArchiveDoesNotBlockThePlayingSeasonOrOverwriteTheArchive() {
        when(client.fetch(2025)).thenReturn("{}");
        when(client.fetch(2026)).thenReturn("{\"dates\":[]}");
        assertThatThrownBy(() -> sync.sync(2026)).isInstanceOf(IllegalStateException.class);
        verify(store).replace(List.of(empty("2026-27")));
        verifyNoMoreInteractions(store);
    }

    @Test
    void bothFailuresWriteNothingAndRemainAnOperationalFailure() {
        when(client.fetch(anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> sync.sync(2026)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }
}
