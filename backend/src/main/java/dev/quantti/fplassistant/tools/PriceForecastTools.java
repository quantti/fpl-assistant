package dev.quantti.fplassistant.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.price.*;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.*;
import dev.quantti.fplassistant.price.PricePredictionStore.*;

@Component
@PricePredictionEnabled
public class PriceForecastTools implements FplTool {
    private static final String UNAVAILABLE = "Official price capture unavailable: capture permission is disabled.";
    private static final String GUIDE = "Official forecast is a guide, not a guarantee; progress is not probability.";
    private final JdbcTemplate jdbc;
    private final PlayerNameSearch names;
    private final PricePredictionStore store;
    private final PricePredictionPermission permission;
    private final PricePredictionConfiguration.Settings settings;
    private final Clock clock;

    @Autowired
    public PriceForecastTools(JdbcTemplate jdbc, PlayerNameSearch names, PricePredictionStore store,
                              PricePredictionPermission permission, PricePredictionConfiguration.Settings settings) {
        this(jdbc, names, store, permission, settings, Clock.systemUTC());
    }

    PriceForecastTools(JdbcTemplate jdbc, PlayerNameSearch names, PricePredictionStore store,
                       PricePredictionPermission permission, PricePredictionConfiguration.Settings settings, Clock clock) {
        this.jdbc = jdbc;
        this.names = names;
        this.store = store;
        this.permission = permission;
        this.settings = settings;
        this.clock = clock;
    }

    @Tool(description = """
            Get captured official current market price and the separate upcoming official price forecast.
            Use for current price and upcoming rise/drop questions when available. Name may be a surname,
            full name or name with FPL short club name/three-letter code; accents are optional.
            Forecast progress is not probability; unavailable forecast does not mean no change.
            Market price is not the manager's personal selling price. Reads captured data only.
            """)
    public String getPlayerPriceForecast(String playerName) {
        return serve(() -> store.readForecastView(optional -> {
            if (optional.isEmpty()) return "No official forecast capture has been published yet.";
            ForecastView view = optional.orElseThrow();
            if (!seasonMatches(view.snapshot().season())) return seasonUnavailable();
            Instant asOf = clock.instant();
            String header = forecastHeader(view, asOf);
            List<Match> matches = matches(playerName, settings.tables().forecastPlayer(),
                    view.snapshot().season(), view.publication().generation());
            if (matches.isEmpty()) return header + "\nPlayer not found in this captured forecast.";
            Player player = view.snapshot().players().get(matches.getFirst().elementId());
            return header + "\n" + forecastRow(view, player, asOf) + ambiguity(matches) + "\n" + GUIDE;
        }));
    }

    @Tool(description = """
            List captured official upcoming price-change candidates in direction rise or drop, limit 1–20.
            Only usable verified directional signals are included; classification then signed progress
            determines order. Reports capture coverage, omissions and truncation, not a complete market list.
            """)
    public String getPriceChangeCandidates(String direction, int limit) {
        String requested = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!requested.equals("rise") && !requested.equals("drop")) return "Direction must be rise or drop.";
        return serve(() -> store.readForecastView(optional -> {
            if (optional.isEmpty()) return "No official forecast capture has been published yet.";
            ForecastView view = optional.orElseThrow();
            if (!seasonMatches(view.snapshot().season())) return seasonUnavailable();
            Instant asOf = clock.instant();
            List<Player> usable = view.snapshot().players().values().stream()
                    .filter(player -> assessment(view, player, asOf).forecastUsable()).toList();
            boolean rise = requested.equals("rise");
            Comparator<Player> ordering = Comparator.comparingInt((Player player) ->
                    Math.abs(player.forecast().likelihood())).reversed();
            Comparator<Player> progress = Comparator.comparing(player -> player.forecast().progress());
            if (rise) progress = progress.reversed();
            List<Player> filtered = usable.stream().filter(player -> directional(player.forecast().signal(), rise))
                    .sorted(ordering.thenComparing(progress).thenComparingInt(Player::elementId)).toList();
            List<Player> selected = filtered.stream().limit(bound(limit)).toList();
            StringBuilder output = new StringBuilder(forecastHeader(view, asOf));
            output.append("\nCoverage: usable ").append(usable.size()).append("/")
                    .append(view.snapshot().players().size()).append(" captured players; filtered ")
                    .append(filtered.size()).append(" ").append(requested).append(" candidates; returned ")
                    .append(selected.size()).append("; truncated ").append(filtered.size() - selected.size())
                    .append(". Upstream completeness is not proven.");
            var refusals = view.snapshot().players().values().stream()
                    .map(player -> assessment(view, player, asOf).refusal())
                    .filter(refusal -> refusal != PricePredictionEvidence.Refusal.NONE)
                    .collect(java.util.stream.Collectors.groupingBy(refusal -> refusal,
                            java.util.TreeMap::new, java.util.stream.Collectors.counting()));
            if (!refusals.isEmpty()) output.append("\nUnavailable forecast evidence counts: ").append(refusals);
            selected.forEach(player -> output.append("\n").append(forecastRow(view, player, asOf)));
            if (selected.isEmpty()) output.append("\nNo usable captured candidates in this direction; this does not prove no upcoming changes.");
            return output.append("\n").append(GUIDE).toString();
        }));
    }

    @Tool(description = """
            Get a named player's recent actual official price-change events, lastN 1–20 events.
            Uses the independent captured recent-change feed, with exact UTC event times and before/after
            market prices. Name may include FPL short club name/three-letter code; accents are optional.
            Missing entries do not prove no change; this is not a forecast or guaranteed complete archive.
            Use getPriceHistory for longer daily observations instead.
            """)
    public String getRecentPriceChanges(String playerName, int lastN) {
        return serve(() -> store.readRecentView(optional -> {
            if (optional.isEmpty()) return "No official recent-change capture has been published yet; no conclusion about actual changes is possible.";
            RecentView view = optional.orElseThrow();
            if (!seasonMatches(view.snapshot().season())) return seasonUnavailable();
            Instant asOf = clock.instant();
            StringBuilder output = new StringBuilder("Data source: official FPL stats/price-changes captured feed; season ")
                    .append(view.snapshot().season()).append(".\nLast successful checkedAt UTC: ")
                    .append(time(view.publication().checkedAt())).append("; original payload readAt UTC: ")
                    .append(time(view.snapshot().fetchedAt())).append("; identity readAt UTC: ")
                    .append(time(view.snapshot().identityFetchedAt())).append(".\nAssessed at UTC: ").append(time(asOf));
            boolean fresh = view.publication().checkedAt() != null
                    && !view.publication().checkedAt().isBefore(view.snapshot().fetchedAt())
                    && !view.publication().checkedAt().isAfter(asOf)
                    && Duration.between(view.publication().checkedAt(), asOf).compareTo(Duration.ofMinutes(45)) <= 0;
            output.append(fresh ? "\nFeed read is within 45 minutes." : "\nFeed is stale or its read time is invalid; dated events below are historical, not current overnight coverage.");
            var eventTimes = view.namedChanges().stream().map(row -> row.change().changedAt()).sorted().toList();
            output.append("\nObserved event range UTC: ").append(eventTimes.isEmpty() ? "empty"
                    : time(eventTimes.getFirst()) + " to " + time(eventTimes.getLast()))
                    .append(". Retention and upstream completeness are not guaranteed; absent entries do not prove no change.");
            List<Match> matches = matches(playerName, settings.tables().changeEvent(),
                    view.snapshot().season(), view.publication().generation());
            if (matches.isEmpty()) return output.append("\nNo matching entry in this captured recent-change feed.").toString();
            Match match = matches.getFirst();
            List<NamedChange> events = view.namedChanges().stream()
                    .filter(row -> row.change().elementId() == match.elementId())
                    .sorted(Comparator.comparing((NamedChange row) -> row.change().changedAt()).reversed()).toList();
            List<NamedChange> selected = events.stream().limit(bound(lastN)).toList();
            for (NamedChange row : selected) {
                Change change = row.change();
                output.append("\n").append(row.webName()).append(" (").append(row.eventTeam().name())
                        .append("): changedAt UTC ").append(time(change.changedAt())).append("; market price ")
                        .append(money(change.priceBefore())).append(" → ").append(money(change.priceAfter()))
                        .append("; actual delta ").append(String.format(Locale.ROOT, "%+.1fm", (change.priceAfter() - change.priceBefore()) / 10.0));
            }
            return output.append("\nReturned ").append(selected.size()).append("/").append(events.size())
                    .append(" captured matching events; truncated ").append(events.size() - selected.size())
                    .append(".").append(ambiguity(matches)).toString();
        }));
    }

    private String serve(Supplier<String> answer) {
        try {
            if (!permission.permitted() || !store.publishingEnabled()) return UNAVAILABLE;
            String result = answer.get();
            if (!permission.permitted() || !store.publishingEnabled()) return UNAVAILABLE;
            return result;
        } catch (RuntimeException exception) {
            return "Official price capture temporarily unavailable (stored evidence could not be read). No price or signal can be inferred.";
        }
    }

    private List<Match> matches(String query, String table, String season, long generation) {
        PlayerNameSearch.Fragment fragment = names.fragment(query, "web_name",
                "web_name || ' ' || full_name", "team_name || ' ' || team_short");
        List<Object> arguments = new ArrayList<>(List.of(season, generation));
        arguments.addAll(fragment.whereArgs());
        arguments.addAll(fragment.orderArgs());
        return jdbc.query("SELECT element_id, web_name, team_name FROM (SELECT DISTINCT element_id, web_name, "
                + "full_name, team_name, team_short FROM " + table + " WHERE season = ? AND generation = ?) captured"
                + " WHERE " + fragment.where() + " ORDER BY " + fragment.exactFirst() + ", element_id, team_name",
                (result, rowNumber) -> new Match(result.getInt("element_id"), result.getString("web_name")
                        + " (" + result.getString("team_name") + ")"), arguments.toArray()).stream()
                .collect(java.util.stream.Collectors.toMap(Match::elementId, match -> match,
                        (first, second) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
    }

    private record Match(int elementId, String display) { }

    private static String ambiguity(List<Match> matches) {
        return NameMatch.ambiguityNote(matches.stream().map(Match::display).toList());
    }

    private boolean seasonMatches(String season) { return settings.playingSeason().equals(season); }
    private static String seasonUnavailable() { return "Official price capture unavailable: captured season does not match the configured playing season."; }
    private static int bound(int requested) { return Math.max(1, Math.min(20, requested)); }
    private static String time(Instant instant) { return instant == null ? "unavailable" : instant.toString(); }
    private static String money(int price) { return String.format(Locale.ROOT, "£%.1fm", price / 10.0); }

    private static PricePredictionEvidence.Assessment assessment(ForecastView view, Player player, Instant asOf) {
        return PricePredictionEvidence.assess(view.snapshot(), view.publication().checkedAt(), player.elementId(),
                asOf, PricePredictionCadence.phase(asOf, view) == PricePredictionCadence.Phase.FREQUENT);
    }

    private static String forecastHeader(ForecastView view, Instant asOf) {
        var snapshot = view.snapshot();
        return "Data source: official FPL bootstrap captured market prices and price predictor; season " + snapshot.season()
                + ".\nLast successful checkedAt UTC: " + time(view.publication().checkedAt())
                + "; original payload readAt UTC: " + time(snapshot.fetchedAt())
                + ".\nPredictor source updatedAt UTC: " + time(snapshot.sourceUpdatedAt())
                + "; exact forecast target UTC: " + time(snapshot.target())
                + (snapshot.target() != null && !snapshot.target().isAfter(asOf) ? " (expired)" : "")
                + ".\nAssessed at UTC: " + time(asOf)
                + (snapshot.players().values().stream().anyMatch(player -> assessment(view, player, asOf).delayed())
                ? "\nDelayed predictor/capture update (20-minute warning)." : "");
    }

    private static String forecastRow(ForecastView view, Player player, Instant asOf) {
        var evidence = assessment(view, player, asOf);
        Forecast forecast = player.forecast();
        String result = player.webName() + " (" + view.snapshot().teams().get(player.teamId()).name() + "): "
                + (evidence.currentPriceUsable() ? "captured market price " + money(player.currentPrice())
                + " at checkedAt " + time(view.publication().checkedAt()) : "current market price unavailable (capture age)");
        if (evidence.forecastUsable()) {
            result += "; " + label(forecast.signal()) + "; progress " + forecast.progress()
                    + "%; projected progress " + forecast.projectedProgress() + "%";
        } else {
            result += "; forecast unavailable: " + evidence.refusal() + "; player state " + forecast.state();
            if (forecast.lockedUntil() != null) result += "; locked until UTC " + time(forecast.lockedUntil());
        }
        return result;
    }

    private static boolean directional(Signal signal, boolean rise) {
        return rise ? signal == Signal.VERY_LIKELY_RISE || signal == Signal.LIKELY_RISE
                : signal == Signal.VERY_LIKELY_DROP || signal == Signal.LIKELY_DROP;
    }

    private static String label(Signal signal) {
        return switch (signal) {
            case VERY_LIKELY_RISE -> "Very likely to rise";
            case LIKELY_RISE -> "Likely to rise";
            case UNLIKELY_TO_CHANGE -> "Unlikely to change";
            case LIKELY_DROP -> "Likely to drop";
            case VERY_LIKELY_DROP -> "Very likely to drop";
        };
    }
}
