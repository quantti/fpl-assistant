package dev.quantti.fplassistant.teamxg;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UnderstatSyncServiceTest {
    private final UnderstatClient client = mock(UnderstatClient.class);
    private final UnderstatStore store = mock(UnderstatStore.class);
    private final UnderstatSyncService sync = new UnderstatSyncService(client, store,
            new StaticListableBeanFactory(Map.of("clock", Clock.fixed(
                    Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC))).getBeanProvider(Clock.class));

    @Test
    void fetchesExactlyTwoLeagueSeasonsThenPublishesTogether() {
        when(client.fetch(2025)).thenReturn("{\"dates\":[]}");
        when(client.fetch(2026)).thenReturn("{\"dates\":[]}");
        assertThat(sync.sync(2026)).isZero();
        var order = inOrder(client, store);
        order.verify(client).fetch(2025);
        order.verify(client).fetch(2026);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<UnderstatStore.Capture>> captures = ArgumentCaptor.forClass(java.util.List.class);
        order.verify(store).replace(captures.capture());
        assertThat(captures.getValue()).extracting(UnderstatStore.Capture::season).containsExactly("2025-26", "2026-27");
        verifyNoMoreInteractions(client, store);
    }

    @Test
    void secondFetchFailurePublishesNeitherSeason() {
        when(client.fetch(2025)).thenReturn("{\"dates\":[]}");
        when(client.fetch(2026)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> sync.sync(2026)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test
    void invalidResponsePublishesNeitherSeason() {
        when(client.fetch(2025)).thenReturn("{}");
        assertThatThrownBy(() -> sync.sync(2026)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
}
