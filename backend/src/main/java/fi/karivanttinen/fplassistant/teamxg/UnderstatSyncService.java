package fi.karivanttinen.fplassistant.teamxg;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class UnderstatSyncService {
    private final UnderstatClient client;
    private final UnderstatStore store;
    private final Clock clock;

    public UnderstatSyncService(UnderstatClient client, UnderstatStore store, ObjectProvider<Clock> clock) {
        this.client = client;
        this.store = store;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public int sync(int playingYear) {
        UnderstatParser.season(playingYear);
        int written = 0;
        RuntimeException failure = null;
        for (int year : new int[]{playingYear - 1, playingYear}) {
            try {
                String body = client.fetch(year);
                var capturedAt = clock.instant();
                var matches = UnderstatParser.parse(year, body, capturedAt);
                store.replace(List.of(new UnderstatStore.Capture(UnderstatParser.season(year), capturedAt, matches)));
                written += matches.size();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure != null) throw new IllegalStateException(
                "Understat capture failed for at least one season; failed seasons retained, other seasons may have updated", failure);
        return written;
    }
}
