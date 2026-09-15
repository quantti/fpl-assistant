package fi.karivanttinen.fplassistant.myteam;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import fi.karivanttinen.fplassistant.myteam.FplEntryClient.ChipPlayed;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.EntrySummary;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.LockedSquad;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.Pick;
import fi.karivanttinen.fplassistant.tools.DataAge;
import fi.karivanttinen.fplassistant.tools.UkTime;
import fi.karivanttinen.fplassistant.user.UserProfileService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The user's own squad: fetched from FPL at chat time, filled in from
 * {@link FplBootstrap} and {@link SquadEnrichment}, cached by entry, and rendered as text.
 *
 * <p><b>The squad is always the one locked at the last deadline.</b> FPL publishes picks only
 * for a gameweek whose deadline has passed, so a transfer made since is invisible here and the
 * card says which gameweek it is describing rather than calling it "current". There is also no
 * selling price on the public endpoint — that lives behind the manager's own session — so the
 * card says funds are approximate instead of implying exact ones.
 *
 * <p>Caches are keyed by FPL entry id, never by user id: a user who edits their stored team id
 * would otherwise keep seeing the previous team until the entry expired.
 */
@Service
public class MyTeamService {

    private static final Map<Integer, String> POSITIONS =
            Map.of(1, "GK", 2, "DEF", 3, "MID", 4, "FWD");

    /** FPL's chip names, in the words the system prompt already uses. */
    private static final Map<String, String> CHIP_NAMES = Map.of(
            "wildcard", "wildcard",
            "freehit", "free hit",
            "bboost", "bench boost",
            "3xc", "triple captain",
            "manager", "assistant manager");

    public sealed interface Result {

        /** Everything the card needs. {@code chipsKnown} is false when FPL's chip data failed. */
        /**
         * @param event the gameweek the squad was locked for
         * @param chipEvent the gameweek the chip list answers for — the next deadline, since
         *        that is what a chip activated now would be played on
         * @param continuing the squad restored at a Free Hit gameweek's deadline; null when no
         *        Free Hit was played
         */
        /**
         * @param eventStatus FPL's finished/checked flags for {@code event}; null when the
         *        bootstrap has no row for it
         * @param live points so far in {@code event} per element; null when the feed was not
         *        read (budget spent or a failed call)
         */
        record Squad(EntrySummary entry, int event, Optional<Instant> deadline, LockedSquad locked,
                     List<SquadPlayer> players, int chipEvent, List<String> playableChips,
                     List<String> chipsOpeningLater, Integer chipsOpenAt, boolean chipsKnown,
                     SquadEnrichment.Enrichment enrichment, Continuing continuing,
                     Instant fetchedAt, FplBootstrap.EventStatus eventStatus,
                     Instant eventStatusAsOf, Instant entryPointsAsOf,
                     FplLivePoints.Live live) implements Result {
        }

        record NoTeamId() implements Result { }

        record TeamNotFound(int entryId) implements Result { }

        /** An entry that has not played a gameweek yet: FPL has no picks to show. */
        record NoSquadYet(int entryId) implements Result { }

        record Unavailable() implements Result { }
    }

    public record SquadPlayer(Pick pick, FplBootstrap.Element element, SquadEnrichment.Depth depth) {
    }

    /**
     * The team restored for transfers at a Free Hit gameweek's deadline. Present only when one
     * was played for the locked gameweek. The previous deadline's picks are exactly the restored
     * fifteen: transfers made inside the Free Hit window belong to the Free Hit team alone. That
     * also requires gameweek N-1 to not itself have been a Free Hit, which holds only because FPL
     * forbids playing a Free Hit in both GW19 and GW20 — the one point in the season where two
     * Free Hits could otherwise fall back-to-back
     * (~/dev/tapas-fpl-app/backend/FPL_RULES.md:95).
     */
    public sealed interface Continuing {
        record Squad(int event, LockedSquad locked, List<SquadPlayer> players)
                implements Continuing {
        }

        /** A Free Hit was played but the continuing squad could not be reconstructed. */
        record Unknown(String reason) implements Continuing {
        }
    }

    private final UserProfileService profiles;
    private final FplEntryClient fpl;
    private final FplBootstrap bootstrap;
    private final SquadEnrichment enrichment;
    private final FplLivePoints live;
    private final TtlCache<Integer, EntrySummary> entryCache;
    private record CachedSquad(LockedSquad squad, Instant fetchedAt) {
    }

    private final TtlCache<String, CachedSquad> squadCache;

    public MyTeamService(UserProfileService profiles, FplEntryClient fpl, FplBootstrap bootstrap,
                         SquadEnrichment enrichment, FplLivePoints live,
                         @Value("${fpl.myteam.cache-ttl-minutes}") long ttlMinutes,
                         @Value("${fpl.myteam.cache-max-entries}") int maxEntries) {
        this.profiles = profiles;
        this.fpl = fpl;
        this.bootstrap = bootstrap;
        this.enrichment = enrichment;
        this.live = live;
        this.entryCache = new TtlCache<>(Duration.ofMinutes(ttlMinutes), maxEntries);
        this.squadCache = new TtlCache<>(Duration.ofMinutes(ttlMinutes), maxEntries);
    }

    /**
     * The entry summary, from cache when it is fresh. Three tools read this in a single turn —
     * the per-turn call budget only adds up because two of them are free.
     */
    public FplEntryClient.Result<EntrySummary> entry(int entryId, FplBudget budget) {
        Optional<EntrySummary> cached = entryCache.get(entryId);
        if (cached.isPresent()) {
            return new FplEntryClient.Result.Ok<>(cached.get());
        }
        if (!budget.take()) {
            return new FplEntryClient.Result.Unavailable<>();
        }
        FplEntryClient.Result<EntrySummary> result = fpl.entry(entryId);
        if (result instanceof FplEntryClient.Result.Ok<EntrySummary>(EntrySummary summary)) {
            entryCache.put(entryId, summary);
        }
        return result;
    }

    /** One manager's locked squad for a gameweek — the user's own, or an opponent's. */
    public FplEntryClient.Result<Optional<LockedSquad>> lockedSquad(int entryId, int event,
                                                                   FplBudget budget) {
        return switch (lockedSquadRead(entryId, event, budget)) {
            case FplEntryClient.Result.Ok<Optional<CachedSquad>>(Optional<CachedSquad> read) ->
                    new FplEntryClient.Result.Ok<>(read.map(CachedSquad::squad));
            case FplEntryClient.Result.NotFound<Optional<CachedSquad>> ignored ->
                    new FplEntryClient.Result.NotFound<>();
            case FplEntryClient.Result.Unavailable<Optional<CachedSquad>> ignored ->
                    new FplEntryClient.Result.Unavailable<>();
        };
    }

    private FplEntryClient.Result<Optional<CachedSquad>> lockedSquadRead(int entryId, int event,
                                                                         FplBudget budget) {
        String key = entryId + "@" + event;
        Optional<CachedSquad> cached = squadCache.get(key);
        if (cached.isPresent()) {
            return new FplEntryClient.Result.Ok<>(cached);
        }
        if (!budget.take()) {
            return new FplEntryClient.Result.Unavailable<>();
        }
        return switch (fpl.picks(entryId, event)) {
            case FplEntryClient.Result.Ok<Optional<LockedSquad>>(Optional<LockedSquad> squad) -> {
                Optional<CachedSquad> read = squad.map(value ->
                        new CachedSquad(value, Instant.now()));
                read.ifPresent(value -> squadCache.put(key, value));
                yield new FplEntryClient.Result.Ok<>(read);
            }
            case FplEntryClient.Result.NotFound<Optional<LockedSquad>> ignored ->
                    new FplEntryClient.Result.NotFound<>();
            case FplEntryClient.Result.Unavailable<Optional<LockedSquad>> ignored ->
                    new FplEntryClient.Result.Unavailable<>();
        };
    }

    public Optional<FplBootstrap.Snapshot> bootstrap(FplBudget budget) {
        return bootstrap.snapshot(budget);
    }

    public Result myTeam(UUID userId, FplBudget budget) {
        Optional<Integer> teamId = profiles.teamIdOf(userId);
        if (teamId.isEmpty()) {
            return new Result.NoTeamId();
        }
        int entryId = teamId.get();

        EntrySummary summary;
        switch (entry(entryId, budget)) {
            case FplEntryClient.Result.Ok<EntrySummary>(EntrySummary body) -> summary = body;
            case FplEntryClient.Result.NotFound<EntrySummary> ignored -> {
                return new Result.TeamNotFound(entryId);
            }
            case FplEntryClient.Result.Unavailable<EntrySummary> ignored -> {
                return new Result.Unavailable();
            }
        }
        if (summary.currentEvent() == null) {
            return new Result.NoSquadYet(entryId);
        }
        int event = summary.currentEvent();

        Optional<FplBootstrap.Snapshot> snapshot = bootstrap.snapshot(budget);
        if (snapshot.isEmpty()) {
            return new Result.Unavailable();
        }

        CachedSquad lockedRead;
        switch (lockedSquadRead(entryId, event, budget)) {
            // Empty rather than absent: FPL serves picks only once a deadline has passed, so an
            // entry with nothing to show has not played yet — not a failure.
            case FplEntryClient.Result.Ok<Optional<CachedSquad>>(Optional<CachedSquad> read) -> {
                if (read.isEmpty()) {
                    return new Result.NoSquadYet(entryId);
                }
                lockedRead = read.get();
            }
            case FplEntryClient.Result.NotFound<Optional<CachedSquad>> ignored -> {
                return new Result.NoSquadYet(entryId);
            }
            case FplEntryClient.Result.Unavailable<Optional<CachedSquad>> ignored -> {
                return new Result.Unavailable();
            }
        }
        LockedSquad locked = lockedRead.squad();

        // At a Free Hit gameweek's deadline, FPL locks the temporary picks for scoring and
        // restores the previous squad for transfers. Fetch those previous-gameweek picks and
        // render both. KAR-49.
        Continuing continuing = null;
        LockedSquad previousSquad = null;
        if ("freehit".equals(locked.activeChip())) {
            if (event <= 1) {
                continuing = new Continuing.Unknown(
                        "a Free Hit in Gameweek 1 has no earlier squad to return to");
            } else {
                String noPreviousPicks = "FPL publishes no picks for Gameweek " + (event - 1);
                switch (lockedSquad(entryId, event - 1, budget)) {
                    case FplEntryClient.Result.Ok<Optional<LockedSquad>>(Optional<LockedSquad> squad)
                            when squad.isPresent() -> previousSquad = squad.get();
                    case FplEntryClient.Result.Ok<Optional<LockedSquad>> ignored ->
                            continuing = new Continuing.Unknown(noPreviousPicks);
                    case FplEntryClient.Result.NotFound<Optional<LockedSquad>> ignored ->
                            continuing = new Continuing.Unknown(noPreviousPicks);
                    case FplEntryClient.Result.Unavailable<Optional<LockedSquad>> ignored ->
                            continuing = new Continuing.Unknown(
                                    "FPL could not be reached for it just now");
                }
            }
        }

        Set<Integer> elementIds = new LinkedHashSet<>();
        locked.picks().forEach(pick -> elementIds.add(pick.element()));
        if (previousSquad != null) {
            previousSquad.picks().forEach(pick -> elementIds.add(pick.element()));
        }
        SquadEnrichment.Enrichment depth = enrichment.forElements(elementIds);

        List<SquadPlayer> players = squadPlayers(locked, snapshot.get(), depth);
        if (previousSquad != null) {
            continuing = new Continuing.Squad(event - 1, previousSquad,
                    squadPlayers(previousSquad, snapshot.get(), depth));
        }

        // Chips are the one part of the card that may be missing without spoiling it, so a
        // spent budget or a failed call drops them rather than failing the answer.
        List<ChipPlayed> played = budget.take()
                ? switch (fpl.chipsPlayed(entryId)) {
                    case FplEntryClient.Result.Ok<List<ChipPlayed>>(List<ChipPlayed> chips) -> chips;
                    default -> null;
                }
                : null;
        // Chips are answered for the NEXT deadline, not for the gameweek being played: a chip
        // activated now applies to the next one. During GW1 that means the wildcard is offered
        // for GW2 — which is exactly when FPL first accepts it.
        int chipEvent = chipTargetEvent(snapshot.get(), event);
        Chips chips = chipsFor(snapshot.get(), played, chipEvent);

        // Last, because it is the most dispensable: a refused budget or a failed call loses the
        // per-player points, never the squad. KAR-15.
        FplLivePoints.Live livePoints = this.live.forEvent(event, budget).orElse(null);
        FplBootstrap.EventStatus eventStatus = snapshot.get().status(event).orElse(null);

        return new Result.Squad(summary, event, snapshot.get().deadline(event), locked, players,
                chipEvent, chips.playable(), chips.openingLater(), chips.opensAt(), chips.known(),
                depth, continuing, Instant.now(), eventStatus, snapshot.get().fetchedAt(),
                lockedRead.fetchedAt(), livePoints);
    }

    private record Chips(List<String> playable, List<String> openingLater, Integer opensAt,
                         boolean known) {
    }

    /** How far FPL has got with the gameweek — the qualifier on every points figure on the card. */
    static String gameweekStatus(int event, FplBootstrap.EventStatus status,
                                 Instant statusAsOf, Instant entryAsOf,
                                 FplLivePoints.Live live) {
        if (status == null) {
            return "Whether Gameweek " + event + " is finished could not be read from FPL.";
        }
        if (!status.finished()) {
            return "Gameweek " + event + " is not finished: points are provisional, bonus is added"
                    + " after each match, and automatic substitutions apply only once FPL marks"
                    + " the gameweek final.";
        }
        if (!status.dataChecked()) {
            return "Gameweek " + event + " has finished but FPL has not confirmed it: bonus and"
                    + " automatic substitutions may still change.";
        }
        List<String> older = new ArrayList<>();
        if (entryAsOf != null && entryAsOf.isBefore(statusAsOf)) {
            older.add("the entry total");
        }
        if (live != null && live.fetchedAt().isBefore(statusAsOf)) {
            older.add("the per-player points");
        }
        if (!older.isEmpty()) {
            return "FPL's status snapshot marks Gameweek " + event + " final, but "
                    + String.join(" and ", older) + " were read before that confirmation. Treat"
                    + " those displayed scores as provisional snapshots; final bonus or automatic"
                    + " substitutions may be absent.";
        }
        return "Gameweek " + event + " is final; the displayed scores were read after FPL's"
                + " confirmation.";
    }

    static boolean hasProvisionalFinalScores(Result.Squad squad) {
        return squad.eventStatus() != null && squad.eventStatus().dataChecked()
                && (squad.locked().eventPoints() != null
                        && squad.entryPointsAsOf().isBefore(squad.eventStatusAsOf())
                    || squad.live() != null
                        && squad.live().fetchedAt().isBefore(squad.eventStatusAsOf()));
    }

    /**
     * The gameweek a chip activated right now would be played on. FPL's own {@code is_next} when
     * it has one; otherwise the gameweek after the one being played, which is the same thing
     * once the season's last deadline has passed and nothing is flagged next.
     */
    private static int chipTargetEvent(FplBootstrap.Snapshot snapshot, int currentEvent) {
        Integer next = snapshot.nextEvent();
        return next != null ? next : currentEvent + 1;
    }

    /**
     * A chip is playable when one of its own windows covers the gameweek being asked about and
     * the manager has not played it inside that window. The windows are per chip and there are
     * two sets a season — in 2026/27 the wildcard's first window is GW2-19 while the bench
     * boost's is GW1-19, because until the GW1 deadline transfers are unlimited and a wildcard
     * would buy nothing — so they are read from the payload rather than assumed. The next window
     * to open lists every chip it brings, including ones still playable now: an unused chip does
     * not carry over into the next window, so the next window is a fresh set.
     */
    private static Chips chipsFor(FplBootstrap.Snapshot snapshot, List<ChipPlayed> played,
                                  int event) {
        if (played == null || snapshot.chips().isEmpty()) {
            return new Chips(List.of(), List.of(), null, false);
        }
        List<String> playable = new ArrayList<>();
        Map<String, Integer> later = new LinkedHashMap<>();
        for (FplBootstrap.ChipWindow window : snapshot.chips()) {
            String name = CHIP_NAMES.getOrDefault(window.name(), window.name());
            if (window.covers(event)) {
                boolean spent = played.stream().anyMatch(chip ->
                        chip.name().equalsIgnoreCase(window.name()) && window.covers(chip.event()));
                if (!spent && !playable.contains(name)) {
                    playable.add(name);
                }
            } else if (window.startEvent() > event) {
                later.merge(name, window.startEvent(), Math::min);
            }
        }
        Integer opensAt = later.values().stream().min(Integer::compareTo).orElse(null);
        List<String> openingLater = opensAt == null ? List.of() : later.entrySet().stream()
                .filter(entry -> entry.getValue().equals(opensAt))
                .map(Map.Entry::getKey)
                .toList();
        return new Chips(playable, openingLater, openingLater.isEmpty() ? null : opensAt, true);
    }

    private static List<SquadPlayer> squadPlayers(LockedSquad squad, FplBootstrap.Snapshot snapshot,
                                                  SquadEnrichment.Enrichment depth) {
        List<SquadPlayer> players = new ArrayList<>();
        for (Pick pick : squad.picks().stream()
                .sorted(Comparator.comparingInt(Pick::position)).toList()) {
            players.add(new SquadPlayer(pick, snapshot.element(pick.element()).orElse(null),
                    depth.of(pick.element())));
        }
        return players;
    }

    // ---------------------------------------------------------------- rendering

    public String render(Result result) {
        return switch (result) {
            case Result.NoTeamId ignored -> """
                    No FPL team id is stored for this account, so the squad cannot be looked up. \
                    Ask the user to save their team id in their profile — it is the number in \
                    the URL of their FPL points page — and answer generally in the meantime.""";
            case Result.TeamNotFound(int entryId) ->
                    "FPL does not know entry " + entryId + ". The stored team id looks wrong;"
                            + " ask the user to check it in their profile.";
            case Result.NoSquadYet(int entryId) ->
                    "Entry " + entryId + " has not played a gameweek yet, so FPL publishes no"
                            + " squad for it. Advise generally until the first deadline passes.";
            case Result.Unavailable ignored ->
                    "FPL could not be reached just now, so the squad is unavailable. This says"
                            + " nothing about the stored team id — suggest trying again shortly.";
            case Result.Squad squad -> renderSquad(squad);
        };
    }

    private String renderSquad(Result.Squad squad) {
        StringBuilder out = new StringBuilder();
        boolean freeHit = "freehit".equals(squad.locked().activeChip());
        boolean continuingShown = squad.continuing() instanceof Continuing.Squad;
        out.append(freeHit ? "FREE HIT squad for Gameweek " : "Squad as locked for Gameweek ")
                .append(squad.event());
        squad.deadline().ifPresent(deadline ->
                out.append(" (deadline ").append(UkTime.format(deadline, "unknown")).append(")"));
        out.append("; fetched ").append(UkTime.format(squad.fetchedAt(), "just now")).append(".\n");
        if (freeHit) {
            out.append("These picks score in Gameweek ").append(squad.event())
                    .append(" only and do not carry forward.");
            if (continuingShown) {
                out.append(" At the Gameweek ").append(squad.event())
                        .append(" deadline, FPL restored the continuing squad used for transfers")
                        .append(" and future Gameweeks; it is shown below.\n");
            } else {
                out.append(" The squad used for transfers and future Gameweeks could not be")
                        .append(" reconstructed here.\n");
            }
        }
        out.append("Team: ").append(squad.entry().teamName())
                .append(" (entry ").append(squad.entry().id()).append(")");
        if (squad.entry().overallRank() != null) {
            out.append(" - overall rank ").append(String.format(Locale.UK, "%,d",
                    squad.entry().overallRank()));
        }
        if (squad.entry().totalPoints() != null) {
            out.append(", ").append(squad.entry().totalPoints()).append(" pts");
        }
        out.append(".\n");

        int bank = squad.locked().bank();
        int value = squad.locked().value();
        out.append("Bank ").append(money(bank)).append(", squad value ").append(money(value))
                .append(", total ").append(money(bank + value)).append(".");
        if (freeHit) {
            out.append(" These were the FREE HIT squad's funds and did not carry forward;")
                    .append(continuingShown
                            ? " the continuing squad's bank is shown with that block below.\n"
                            : " the continuing squad's bank could not be shown here.\n");
        } else {
            out.append(" FPL does not publish another manager's selling prices or free-transfer")
                    .append(" count, so treat available funds as approximate.\n");
        }

        out.append("Active chip: ")
                .append(squad.locked().activeChip() == null ? "none"
                        : CHIP_NAMES.getOrDefault(squad.locked().activeChip(),
                                squad.locked().activeChip()));
        if (squad.chipsKnown()) {
            out.append(". Playable at the next deadline (GW").append(squad.chipEvent()).append("): ")
                    .append(squad.playableChips().isEmpty() ? "none"
                            : String.join(", ", squad.playableChips()));
            if (!squad.chipsOpeningLater().isEmpty()) {
                out.append(". Second set from GW").append(squad.chipsOpenAt()).append(": ")
                        .append(String.join(", ", squad.chipsOpeningLater()));
            }
        }
        out.append(".\n\n");

        out.append("Gameweek ").append(squad.event()).append(" points: ");
        if (squad.locked().eventPoints() == null) {
            out.append("not published by FPL");
        } else {
            out.append(squad.locked().eventPoints())
                    .append(" (FPL's own total for this entry, before transfer costs)");
        }
        out.append(". ").append(gameweekStatus(squad.event(), squad.eventStatus(),
                squad.eventStatusAsOf(), squad.locked().eventPoints() == null
                        ? null : squad.entryPointsAsOf(), squad.live())).append(" ");
        if (squad.live() == null) {
            out.append("Per-player gameweek points could not be read from FPL just now.\n\n");
        } else {
            out.append("Player gameweek points read ")
                    .append(UkTime.format(squad.live().fetchedAt(), "just now"))
                    .append(" from FPL's live feed; the player lines need not sum to the entry total")
                    .append(" (captain multiplier, bench, automatic substitutions).\n\n");
        }

        if (squad.enrichment().available()) {
            out.append(squad.enrichment().season().provenance()).append("\n")
                    .append("xG/xA and next-5 FDR are stored database values, each tagged with the")
                    .append(" recorded write age of the rows behind it; price, form, points and")
                    .append(" ownership are FPL's own player data.\n")
                    .append(startingGameweekAge(squad.enrichment(), squad.fetchedAt()));
        } else {
            out.append("Season-long statistics come from FPL's own player data, captured just now."
                    + " Fixture difficulty and expected goals are not available yet: the"
                    + " database has not been loaded with " + squad.enrichment().season().currentSeasonCode()
                    + " data.").append("\n");
        }

        appendPicks(out, squad.players(), squad.fetchedAt(), squad.live());

        switch (squad.continuing()) {
            case null -> { }
            case Continuing.Squad(int event, LockedSquad previous, List<SquadPlayer> players) -> {
                out.append("\nContinuing squad (restored for transfers at the Gameweek ")
                        .append(squad.event()).append(" deadline from the Gameweek ").append(event)
                        .append(" picks). Transfers and all forward-looking advice apply to THIS")
                        .append(" squad, not the Free Hit one. Captain and bench order shown are")
                        .append(" Gameweek ").append(event)
                        .append(" choices; only the fifteen players were restored. Prices, form,")
                        .append(" points and ownership on the lines below are today's FPL player")
                        .append(" data, from the same source as above; any xG/xA and FDR are")
                        .append(" stored database values tagged with their own write age, as above.")
                        .append(" Restored bank was ").append(money(previous.bank()))
                        .append(" (approximate - transfers made since the Free Hit deadline are")
                        .append(" not visible).\n");
                appendPicks(out, players, squad.fetchedAt(), null);
            }
            case Continuing.Unknown(String reason) ->
                    out.append("\nA Free Hit was played for Gameweek ").append(squad.event())
                            .append(", so the picks above do not carry forward, but the squad used")
                            .append(" for transfers and future Gameweeks could not be shown: ")
                            .append(reason)
                            .append(". Keep transfers and forward-looking advice conditional, and")
                            .append(" say its exact makeup could not be verified.\n");
        }
        return out.toString();
    }

    /**
     * KAR-9: the age of the gameweek row that chose where every club's FDR run starts, stated
     * once. Empty when no run was looked up, since no displayed FDR depends on it then.
     */
    private static String startingGameweekAge(SquadEnrichment.Enrichment enrichment, Instant asOf) {
        SquadEnrichment.StartingGameweek start = enrichment.startingGameweek();
        if (start == null) {
            return "";
        }
        String scope = "starting-gameweek selection for the FDR run (gameweek row id=" + start.id()
                + "), season " + enrichment.season().code();
        return DataAge.storedDataAge(scope, "gameweek record", start.write(), asOf) + "\n"
                + DataAge.GAMEWEEK_CAVEAT + "\n";
    }

    /** {@code asOf} is the squad's fetch instant: every stored-row age on the card is measured from it. */
    private static void appendPicks(StringBuilder out, List<SquadPlayer> players, Instant asOf,
                                     FplLivePoints.Live live) {
        out.append("Starting XI\n");
        players.stream().filter(player -> player.pick().starting())
                .forEach(player -> out.append(line(player, asOf, live)));
        out.append("Bench\n");
        players.stream().filter(player -> !player.pick().starting())
                .forEach(player -> out.append(line(player, asOf, live)));
        String flagged = players.stream()
                .filter(player -> player.element() != null && player.element().availability() != null)
                .map(player -> player.element().webName() + " (" + player.element().teamShort()
                        + ") - " + player.element().availability())
                .reduce((a, b) -> a + "; " + b).orElse(null);
        if (flagged != null) {
            out.append("Flagged: ").append(flagged).append("\n");
        }
    }

    private static String line(SquadPlayer player, Instant asOf, FplLivePoints.Live live) {
        FplBootstrap.Element element = player.element();
        StringBuilder line = new StringBuilder(String.format(Locale.UK, "%2d ", player.pick().position()));
        if (element == null) {
            // FPL knows this element and our snapshot does not — a player added between
            // refreshes. Name what we do know rather than dropping a pick from the squad.
            line.append("element ").append(player.pick().element())
                    .append(" (").append(POSITIONS.getOrDefault(player.pick().elementType(), "?"))
                    .append(") - not in the current player list\n");
            return line.toString();
        }
        line.append(element.webName()).append(" (").append(element.teamShort()).append(", ")
                .append(POSITIONS.getOrDefault(element.elementType(), "?")).append(") ")
                .append(money(element.nowCost()));
        if (player.pick().captain()) {
            line.append(" - CAPTAIN");
        } else if (player.pick().viceCaptain()) {
            line.append(" - vice");
        }
        if (live != null) {
            line.append(" - GW").append(live.event()).append(": ");
            live.of(player.pick().element()).ifPresentOrElse(
                    points -> {
                        line.append(points.points()).append(" pts, ").append(points.minutes())
                                .append(" min");
                        if (player.pick().multiplier() > 1) {
                            line.append(", counts x").append(player.pick().multiplier());
                        }
                    },
                    () -> line.append("n/a"));
        }
        line.append(" - form ").append(element.form())
                .append(", ").append(element.totalPoints()).append(" pts")
                .append(", ").append(element.pointsPerGame()).append("/game")
                .append(", ").append(element.selectedByPercent()).append("% owned");
        SquadEnrichment.Depth depth = player.depth();
        // Each stored value is tagged with the rows behind it (KAR-9); FPL's own values are not.
        if (depth != null && depth.nextFiveFdr() != null) {
            line.append(String.format(Locale.UK, ", next 5 FDR %.1f", depth.nextFiveFdr()))
                    .append(written("fixture records", depth.fdrWrites(), asOf));
        }
        boolean expected = false;
        if (depth != null && depth.expectedGoals() != null) {
            line.append(", xG ").append(depth.expectedGoals());
            expected = true;
        }
        if (depth != null && depth.expectedAssists() != null) {
            line.append(", xA ").append(depth.expectedAssists());
            expected = true;
        }
        if (expected) {
            line.append(written("player record", depth.playerWrite(), asOf));
        }
        if (element.availability() != null) {
            line.append(" - ").append(element.availability());
        }
        return line.append("\n").toString();
    }

    /** e.g. {@code " (player record written 9 days ago)"} or {@code " (player record: write time unavailable)"}. */
    private static String written(String rows, DataAge.Span writes, Instant asOf) {
        String age = DataAge.annotation(writes, asOf);
        return " (" + rows + (age.startsWith("written") ? " " : ": ") + age + ")";
    }

    /** FPL keeps money in tenths of a million. */
    static String money(int tenths) {
        return String.format(Locale.UK, "GBP %.1fm", tenths / 10.0);
    }
}
