package fi.karivanttinen.fplassistant.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PricePredictionSnapshot(String season, Instant fetchedAt, Instant sourceUpdatedAt,
                                      Instant target, Map<Integer, Team> teams,
                                      Map<Integer, Player> players) {

    public PricePredictionSnapshot {
        teams = Map.copyOf(teams);
        players = Map.copyOf(players);
    }

    public enum State { READY, CALIBRATING, LOCKED, UNAVAILABLE, UNSUPPORTED }

    public enum Signal {
        VERY_LIKELY_RISE, LIKELY_RISE, UNLIKELY_TO_CHANGE, LIKELY_DROP, VERY_LIKELY_DROP
    }

    public record Team(int id, String name, String shortName) { }

    public record Player(int elementId, int teamId, int position, String webName, String fullName,
                         int currentPrice, Forecast forecast) { }

    public record Forecast(State state, BigDecimal progress, BigDecimal projectedProgress,
                           Integer likelihood, Signal signal, Instant lockedUntil) { }

    public record RecentChanges(String season, Instant fetchedAt, Instant identityFetchedAt,
                                List<Change> changes) {
        public RecentChanges {
            changes = List.copyOf(changes);
        }
    }

    public record Change(int elementId, int teamId, int gameweek, Instant changedAt,
                         int priceBefore, int priceAfter) { }
}
