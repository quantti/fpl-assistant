package fi.karivanttinen.fplassistant.teamxg;

import java.time.Clock;
import java.util.ArrayList;
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
        List<UnderstatStore.Capture> captures = new ArrayList<>();
        for (int year : new int[]{playingYear - 1, playingYear}) {
            String body = client.fetch(year);
            var capturedAt = clock.instant();
            captures.add(new UnderstatStore.Capture(UnderstatParser.season(year), capturedAt,
                    UnderstatParser.parse(year, body, capturedAt)));
        }
        store.replace(captures);
        return captures.stream().mapToInt(c -> c.matches().size()).sum();
    }
}
