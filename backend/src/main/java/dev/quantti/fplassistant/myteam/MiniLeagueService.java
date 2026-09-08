package dev.quantti.fplassistant.myteam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.quantti.fplassistant.myteam.FplEntryClient.ClassicLeague;
import dev.quantti.fplassistant.myteam.FplEntryClient.EntrySummary;
import dev.quantti.fplassistant.myteam.FplEntryClient.LockedSquad;
import dev.quantti.fplassistant.myteam.FplEntryClient.Pick;
import dev.quantti.fplassistant.myteam.FplEntryClient.StandingsPage;
import dev.quantti.fplassistant.myteam.FplEntryClient.StandingsRow;
import dev.quantti.fplassistant.tools.UkTime;
import dev.quantti.fplassistant.user.UserProfileService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The mini-league view: who is around the user in a league they were invited to, and what those
 * managers own that the user does not.
 *
 * <p><b>Only the difference is reported.</b> An opponent's fifteen picks are fifteen lines that
 * re-enter the prompt on every later turn of the conversation; the three players they own and
 * the user does not are the part that changes any advice.
 *
 * <p><b>No real names.</b> FPL publishes each manager's legal name in the standings. They are
 * third parties who never used this product, and everything here is sent to a model provider, so
 * only the team name and rank travel.
 *
 * <p><b>Only leagues someone created.</b> FPL puts every manager in global leagues — overall,
 * their club, their region — and "Liverpool, 1,089,086 entries" beside "Office League" is noise
 * the model will happily reason about. FPL's own {@code league_type} tells them apart.
 */
@Service
public class MiniLeagueService {

    /** FPL serves standings fifty rows to a page. */
    static final int PAGE_SIZE = 50;
    /**
     * How many leagues the list names. A real account can be in thirty of them — verified
     * against a live entry that returned sixteen — and the whole list re-enters the prompt on
     * every later turn of the conversation. The smallest are listed first because a league of
     * eight is one the user can actually move in; a 1,879-entry league is a leaderboard.
     */
    static final int MAX_LEAGUES_LISTED = 12;
    /** How far either side of the user "nearby" reaches, before the opponent cap applies. */
    static final int NEARBY_REACH = 5;

    private final UserProfileService profiles;
    private final FplEntryClient fpl;
    private final MyTeamService myTeam;
    private final TtlCache<String, StandingsPage> standingsCache;
    private final int maxOpponents;

    public MiniLeagueService(UserProfileService profiles, FplEntryClient fpl, MyTeamService myTeam,
                             @Value("${fpl.myteam.cache-ttl-minutes}") long ttlMinutes,
                             @Value("${fpl.myteam.cache-max-entries}") int maxEntries,
                             @Value("${fpl.myteam.max-opponents}") int maxOpponents) {
        this.profiles = profiles;
        this.fpl = fpl;
        this.myTeam = myTeam;
        this.standingsCache = new TtlCache<>(java.time.Duration.ofMinutes(ttlMinutes), maxEntries);
        this.maxOpponents = maxOpponents;
    }

    public String myLeagues(UUID userId, FplBudget budget) {
        Optional<Integer> teamId = profiles.teamIdOf(userId);
        if (teamId.isEmpty()) {
            return noTeamId();
        }
        EntrySummary summary = entryOrNull(teamId.get(), budget);
        if (summary == null) {
            return unreachable(teamId.get());
        }
        List<ClassicLeague> leagues = summary.leagues().stream()
                .filter(ClassicLeague::userCreated)
                .sorted(java.util.Comparator.comparingInt(
                        league -> league.totalEntries() == null ? Integer.MAX_VALUE
                                : league.totalEntries()))
                .toList();
        if (leagues.isEmpty()) {
            return "This account is not in any mini-league that somebody created - only FPL's"
                    + " own global leagues, which are not what mini-league advice is about.";
        }
        StringBuilder out = new StringBuilder("Mini-leagues for ")
                .append(summary.teamName()).append(", smallest first:\n");
        for (ClassicLeague league : leagues.stream().limit(MAX_LEAGUES_LISTED).toList()) {
            out.append("- ").append(league.name()).append(" (id ").append(league.id()).append(")");
            if (league.rank() != null) {
                out.append(" - ").append(ordinal(league.rank()));
                if (league.totalEntries() != null) {
                    out.append(" of ").append(league.totalEntries());
                }
                if (league.lastRank() != null && league.lastRank() > 0) {
                    out.append(", was ").append(ordinal(league.lastRank()));
                }
            }
            out.append("\n");
        }
        if (leagues.size() > MAX_LEAGUES_LISTED) {
            out.append("...and ").append(leagues.size() - MAX_LEAGUES_LISTED)
                    .append(" larger mini-leagues not listed. Ask the user to name one if they")
                    .append(" mean a league that is not here.\n");
        }
        return out.toString();
    }

    /**
     * @param focus {@code nearby} (default), {@code topN} such as {@code top10}, or
     *        {@code entry:12345}. Anything else falls back to nearby: the string is written by a
     *        language model, and a typo does not deserve an error the user has to decode.
     */
    public String opponents(UUID userId, int leagueId, String focus, FplBudget budget) {
        Optional<Integer> teamId = profiles.teamIdOf(userId);
        if (teamId.isEmpty()) {
            return noTeamId();
        }
        int entryId = teamId.get();
        EntrySummary summary = entryOrNull(entryId, budget);
        if (summary == null) {
            return unreachable(entryId);
        }
        Optional<ClassicLeague> league = summary.leagues().stream()
                .filter(l -> l.id() == leagueId).findFirst();
        if (league.isEmpty()) {
            return "This account is not in league " + leagueId + ". Call getMyMiniLeagues first"
                    + " and use one of the ids it returns.";
        }
        if (!league.get().userCreated()) {
            return "League " + leagueId + " (" + league.get().name() + ") is one of FPL's global"
                    + " leagues, not a mini-league somebody set up. Mini-league advice only makes"
                    + " sense in a league with a handful of rivals.";
        }
        if (summary.currentEvent() == null) {
            return "No gameweek has been played yet, so there are no squads to compare.";
        }
        int event = summary.currentEvent();

        OwnedSquads mine = ownedSquads(entryId, event, budget);
        if (mine == null) {
            return "The user's own squad could not be read, so there is nothing to compare the"
                    + " league against. Suggest trying again shortly.";
        }

        Focus parsed = parseFocus(focus);
        return header(league.get(), summary, event) + freeHitNote("User", mine, event) + switch (parsed) {
            case Focus.OneEntry(int opponentEntry) ->
                    renderSingle(opponentEntry, event, mine, budget);
            case Focus.Top(int requested) ->
                    renderSlice(league.get(), summary, event, mine, budget, requested, true);
            case Focus.Nearby ignored ->
                    renderSlice(league.get(), summary, event, mine, budget, maxOpponents, false);
        };
    }

    // ---------------------------------------------------------------- focus

    sealed interface Focus {
        record Nearby() implements Focus { }

        record Top(int requested) implements Focus { }

        record OneEntry(int entryId) implements Focus { }
    }

    static Focus parseFocus(String focus) {
        String value = focus == null ? "" : focus.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("entry:")) {
            try {
                return new Focus.OneEntry(Integer.parseInt(value.substring("entry:".length()).trim()));
            } catch (NumberFormatException e) {
                return new Focus.Nearby();
            }
        }
        if (value.startsWith("top")) {
            String digits = value.substring(3).replaceAll("\\D", "");
            if (digits.isEmpty()) {
                return new Focus.Top(Integer.MAX_VALUE);
            }
            try {
                return new Focus.Top(Math.max(1, Integer.parseInt(digits)));
            } catch (NumberFormatException e) {
                return new Focus.Top(Integer.MAX_VALUE);
            }
        }
        return new Focus.Nearby();
    }

    // ---------------------------------------------------------------- rendering

    /** An empty continuing set means unknown ownership, never that the manager owns nobody. */
    private record OwnedSquads(Set<Integer> locked, Set<Integer> continuing, boolean freeHit) { }

    private String renderSlice(ClassicLeague league, EntrySummary summary, int event,
                               OwnedSquads mine, FplBudget budget, int requested, boolean fromTop) {
        int wanted = Math.min(requested, maxOpponents);
        List<StandingsRow> rows;
        StandingsPage page;
        if (fromTop) {
            page = standings(league.id(), 1, budget);
            if (page == null) {
                return standingsUnavailable(league);
            }
            rows = page.rows().stream().limit((long) wanted + 1).toList();
        } else {
            page = pageHolding(league, summary.id(), budget);
            if (page == null) {
                return standingsUnavailable(league);
            }
            rows = nearbyRows(page.rows(), summary.id());
        }

        StringBuilder out = new StringBuilder();
        if (requested > maxOpponents && requested != Integer.MAX_VALUE) {
            out.append("Asked for ").append(requested).append(" teams; ").append(maxOpponents)
                    .append(" is the maximum this tool fetches, so these are the top ")
                    .append(maxOpponents).append(".\n");
        }
        int shown = 0;
        boolean dropped = false;
        for (StandingsRow row : rows) {
            if (row.entryId() == summary.id() || shown >= wanted) {
                continue;
            }
            String line = opponentLine(row, event, mine, budget);
            if (line == null) {
                dropped = true;
                continue;
            }
            out.append(line);
            shown++;
        }
        if (shown == 0) {
            out.append("No other teams could be read from this league right now.\n");
        }
        if (dropped) {
            out.append("Some teams were left out: FPL either could not be reached for them or"
                    + " this answer reached its request limit.\n");
        }
        return out.toString();
    }

    private String renderSingle(int opponentEntry, int event, OwnedSquads mine, FplBudget budget) {
        EntrySummary opponent = entryOrNull(opponentEntry, budget);
        if (opponent == null) {
            return "Entry " + opponentEntry + " could not be read from FPL.";
        }
        OwnedSquads theirs = ownedSquads(opponentEntry, event, budget);
        if (theirs == null) {
            return "Entry " + opponentEntry + " has no squad published for gameweek " + event + ".";
        }
        StringBuilder out = new StringBuilder();
        out.append(opponent.teamName()).append(" (entry ").append(opponentEntry).append(")");
        if (opponent.totalPoints() != null) {
            out.append(" - ").append(opponent.totalPoints()).append(" pts");
        }
        out.append("\n");
        out.append(comparison(theirs, mine, event, budget));
        return out.toString();
    }

    private String header(ClassicLeague league, EntrySummary summary, int event) {
        StringBuilder out = new StringBuilder(league.name() + " (id " + league.id() + ")");
        if (league.rank() != null) {
            out.append(" - the user is ").append(ordinal(league.rank()));
            if (league.totalEntries() != null) {
                out.append(" of ").append(league.totalEntries());
            }
        }
        out.append(".\nStandings and squads as at Gameweek ").append(event)
                .append("; fetched ").append(UkTime.format(Instant.now(), "just now"))
                .append(". Locked picks describe that gameweek only; transfers since that deadline")
                .append(" are not visible.\n");
        return out.toString();
    }

    /** One opponent, or null when their squad could not be read within the budget. */
    private String opponentLine(StandingsRow row, int event, OwnedSquads mine, FplBudget budget) {
        OwnedSquads theirs = ownedSquads(row.entryId(), event, budget);
        if (theirs == null) {
            return null;
        }
        return " " + ordinal(row.rank()) + "  " + row.entryName() + "  " + row.totalPoints()
                + " pts (+" + row.eventPoints() + " GW" + event + ") - "
                + comparison(theirs, mine, event, budget);
    }

    private String comparison(OwnedSquads theirs, OwnedSquads mine, int event, FplBudget budget) {
        boolean freeHit = mine.freeHit() || theirs.freeHit();
        StringBuilder out = new StringBuilder();
        if (freeHit) {
            out.append("Gameweek ").append(event).append(" locked picks only - ");
        }
        out.append("owns that the user does not: ")
                .append(difference(theirs.locked(), mine.locked(), budget)).append("\n");
        if (freeHit) {
            out.append(freeHitNote("Opponent", theirs, event));
            if (mine.continuing().isEmpty() || theirs.continuing().isEmpty()) {
                out.append("Continuing-squad comparison unavailable; keep forward-looking advice")
                        .append(" conditional rather than using the Free Hit picks.\n");
            } else {
                out.append("Continuing squads - owns that the user does not: ")
                        .append(difference(theirs.continuing(), mine.continuing(), budget))
                        .append("\n");
            }
        }
        return out.toString();
    }

    private static String freeHitNote(String subject, OwnedSquads squad, int event) {
        if (!squad.freeHit()) {
            return "";
        }
        String prefix = subject + ": FREE HIT in Gameweek " + event + " (one-week scoring picks only); ";
        if (squad.continuing().isEmpty()) {
            String reason = event <= 1 ? "Gameweek 1 has no earlier squad"
                    : "Gameweek " + (event - 1) + " picks could not be read"
                            + " (missing, unreachable or request limit)";
            return prefix + "continuing squad unavailable: " + reason
                    + "; keep forward-looking advice conditional.\n";
        }
        return prefix + "continuing squad restored for transfers at the Gameweek " + event
                + " deadline from Gameweek " + (event - 1) + " picks.\n";
    }

    private String difference(Set<Integer> theirs, Set<Integer> mine, FplBudget budget) {
        Optional<FplBootstrap.Snapshot> snapshot = myTeam.bootstrap(budget);
        List<String> names = new ArrayList<>();
        for (Integer element : theirs) {
            if (mine.contains(element)) {
                continue;
            }
            names.add(snapshot.flatMap(s -> s.element(element))
                    .map(FplBootstrap.Element::webName)
                    .orElse("element " + element));
        }
        return names.isEmpty() ? "nothing - the same fifteen players" : String.join(", ", names);
    }

    // ---------------------------------------------------------------- fetching

    private EntrySummary entryOrNull(int entryId, FplBudget budget) {
        return myTeam.entry(entryId, budget) instanceof FplEntryClient.Result.Ok<EntrySummary>(
                EntrySummary summary) ? summary : null;
    }

    /** Locked and continuing ownership, or null when the locked squad could not be read. */
    private OwnedSquads ownedSquads(int entryId, int event, FplBudget budget) {
        if (myTeam.lockedSquad(entryId, event, budget)
                instanceof FplEntryClient.Result.Ok<Optional<LockedSquad>>(Optional<LockedSquad> squad)
                && squad.isPresent() && !squad.get().picks().isEmpty()) {
            Set<Integer> locked = elements(squad.get());
            if (!"freehit".equals(squad.get().activeChip())) {
                return new OwnedSquads(locked, locked, false);
            }
            // The same previous-deadline squad getMyTeam uses: Free Hit transfers never touch
            // the saved fifteen. Read through its cache and budget, not a separate FPL path.
            Set<Integer> continuing = Set.of();
            if (event > 1 && myTeam.lockedSquad(entryId, event - 1, budget)
                    instanceof FplEntryClient.Result.Ok<Optional<LockedSquad>>(
                            Optional<LockedSquad> previous) && previous.isPresent()) {
                continuing = elements(previous.get());
            }
            return new OwnedSquads(locked, continuing, true);
        }
        return null;
    }

    private static Set<Integer> elements(LockedSquad squad) {
        return squad.picks().stream().map(Pick::element)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private StandingsPage standings(int leagueId, int page, FplBudget budget) {
        String key = leagueId + "#" + page;
        Optional<StandingsPage> cached = standingsCache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (!budget.take()) {
            return null;
        }
        if (fpl.standings(leagueId, page) instanceof FplEntryClient.Result.Ok<StandingsPage>(
                StandingsPage body)) {
            standingsCache.put(key, body);
            return body;
        }
        return null;
    }

    /**
     * The page the user is actually on. {@code rank} lags the row position when managers are
     * tied, so the computed page can be one out; the neighbouring pages are worth one look each
     * and no more.
     */
    private StandingsPage pageHolding(ClassicLeague league, int entryId, FplBudget budget) {
        int rank = league.rank() == null ? 1 : Math.max(1, league.rank());
        int guess = ((rank - 1) / PAGE_SIZE) + 1;
        for (int page : new int[] {guess, guess - 1, guess + 1}) {
            if (page < 1) {
                continue;
            }
            StandingsPage fetched = standings(league.id(), page, budget);
            if (fetched == null) {
                return null;
            }
            if (fetched.rows().stream().anyMatch(row -> row.entryId() == entryId)) {
                return fetched;
            }
        }
        // Not found where the rank said it would be: the first guess is still the closest thing
        // to "nearby" we have, and its rows are real opponents.
        return standings(league.id(), guess, budget);
    }

    static List<StandingsRow> nearbyRows(List<StandingsRow> rows, int entryId) {
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entryId() == entryId) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return rows.stream().limit(NEARBY_REACH * 2L).toList();
        }
        int from = Math.max(0, index - NEARBY_REACH);
        int to = Math.min(rows.size(), index + NEARBY_REACH + 1);
        return rows.subList(from, to);
    }

    private String standingsUnavailable(ClassicLeague league) {
        return "The standings for " + league.name() + " could not be read from FPL just now."
                + " Suggest trying again shortly.";
    }

    private static String noTeamId() {
        return "No FPL team id is stored for this account, so their mini-leagues cannot be looked"
                + " up. Ask the user to save it in their profile.";
    }

    private static String unreachable(int entryId) {
        return "FPL could not be reached for entry " + entryId + ", so the leagues are unavailable"
                + " right now. This says nothing about the stored team id.";
    }

    static String ordinal(int value) {
        int lastTwo = value % 100;
        String suffix = (lastTwo >= 11 && lastTwo <= 13) ? "th" : switch (value % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return value + suffix;
    }
}
