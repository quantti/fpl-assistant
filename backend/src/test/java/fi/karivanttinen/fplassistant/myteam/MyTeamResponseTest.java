package fi.karivanttinen.fplassistant.myteam;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.myteam.FplEntryClient.ClassicLeague;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.EntrySummary;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.LockedSquad;
import fi.karivanttinen.fplassistant.myteam.FplEntryClient.Pick;
import fi.karivanttinen.fplassistant.tools.DataAge;
import fi.karivanttinen.fplassistant.tools.SeasonContext;

import static org.assertj.core.api.Assertions.assertThat;

class MyTeamResponseTest {

    private static final Instant DEADLINE = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant LOADED = Instant.parse("2026-09-08T12:34:56Z");

    @Test
    void everyServiceOutcomeHasAnExplicitPageStatus() {
        assertThat(MyTeamResponse.from(new MyTeamService.Result.NoTeamId()).status())
                .isEqualTo("no_team_id");
        assertThat(MyTeamResponse.from(new MyTeamService.Result.TeamNotFound(123)).status())
                .isEqualTo("team_not_found");
        assertThat(MyTeamResponse.from(new MyTeamService.Result.NoSquadYet(123)).status())
                .isEqualTo("no_squad_yet");
        assertThat(MyTeamResponse.from(new MyTeamService.Result.Unavailable()).status())
                .isEqualTo("unavailable");
        assertThat(MyTeamResponse.from(ordinarySquad(true)).status()).isEqualTo("ready");
    }

    @Test
    void ordinarySquadMapsOnlyTheFieldsThePageRenders() throws Exception {
        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(
                ordinarySquad(true));

        assertThat(response.entry()).isEqualTo(new MyTeamResponse.Entry(123, "Deadline FC"));
        assertThat(response.lockedGameweek()).isEqualTo(5);
        assertThat(response.deadline()).isEqualTo(DEADLINE);
        assertThat(response.loadedAt()).isEqualTo(LOADED);
        assertThat(response.lockedSquad().bank()).isEqualTo(75);
        assertThat(response.lockedSquad().value()).isEqualTo(1001);
        assertThat(response.lockedSquad().players()).containsExactly(
                new MyTeamResponse.Player(302, 1, true, false, "MID", "Salah", "LIV", 126,
                        "doubtful (75% chance) - Knock", 8, 90),
                new MyTeamResponse.Player(17, 12, false, true, "GK", "Sels", "NFO", 50, null,
                        null, null));
        assertThat(response.continuing())
                .isEqualTo(new MyTeamResponse.Continuation.NotApplicable("not_applicable"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(json)
                .doesNotContain("Private Manager", "leagues", "enrichment", "form",
                        "selectedByPercent", "expectedGoals", "nextFiveFdr", "playerWrite",
                        "fdrWrites", "startingGameweek", "depth");
    }

    @Test
    void aFreeHitKeepsLockedAndRestoredFundsDistinct() {
        MyTeamService.Result.Squad freeHit = baseSquad(
                locked("freehit", 12, 1003, salahPick()), players(salahPick()), true,
                new MyTeamService.Continuing.Squad(4,
                        locked(null, 5, 995, woodPick()), players(woodPick())),
                Optional.of(DEADLINE), null, null);

        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(freeHit);
        MyTeamResponse.Continuation.Available continuing =
                (MyTeamResponse.Continuation.Available) response.continuing();

        assertThat(response.lockedSquad().bank()).isEqualTo(12);
        assertThat(response.lockedSquad().value()).isEqualTo(1003);
        assertThat(response.lockedSquad().activeChip()).isEqualTo("freehit");
        assertThat(continuing.status()).isEqualTo("available");
        assertThat(continuing.sourceGameweek()).isEqualTo(4);
        assertThat(continuing.bank()).isEqualTo(5);
        assertThat(continuing.value()).isEqualTo(995);
        assertThat(continuing.players()).extracting(MyTeamResponse.Player::name)
                .containsExactly("Wood");
        assertThat(continuing.players().get(0).gameweekPoints())
                .as("the continuing squad's players are mapped with no live feed").isNull();
    }

    @Test
    void anUnknownContinuingSquadCarriesTheReasonAndNoInventedFunds() {
        MyTeamService.Result.Squad freeHit = baseSquad(
                locked("freehit", 12, 1003, salahPick()), players(salahPick()), true,
                new MyTeamService.Continuing.Unknown("FPL could not be reached"),
                Optional.of(DEADLINE), null, null);

        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(freeHit);

        assertThat(response.continuing()).isEqualTo(
                new MyTeamResponse.Continuation.Unknown("unknown",
                        "FPL could not be reached"));
    }

    @Test
    void knownEmptyAndUnknownChipListsStayDifferent() {
        MyTeamResponse.Ready known = (MyTeamResponse.Ready) MyTeamResponse.from(
                ordinarySquad(true));
        MyTeamResponse.Ready unknown = (MyTeamResponse.Ready) MyTeamResponse.from(
                ordinarySquad(false));

        assertThat(known.chips().known()).isTrue();
        assertThat(known.chips().playable()).isEmpty();
        assertThat(unknown.chips().known()).isFalse();
        assertThat(unknown.chips().playable()).isEmpty();
    }

    @Test
    void laterChipWindowAndMissingDeadlineArePreserved() {
        MyTeamService.Result.Squad squad = new MyTeamService.Result.Squad(entry(), 5,
                Optional.empty(), locked(null, 75, 1001, salahPick()), players(salahPick()),
                6, List.of("bench boost"), List.of("wildcard", "free hit"), 20, true,
                enrichment(), null, LOADED, null, null);

        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(squad);

        assertThat(response.deadline()).isNull();
        assertThat(response.chips()).isEqualTo(new MyTeamResponse.Chips(true, 6,
                List.of("bench boost"), List.of("wildcard", "free hit"), 20));
    }

    @Test
    void aPickMissingFromBootstrapIsNotDroppedOrTurnedIntoZeroes() {
        Pick missing = new Pick(999, 3, 1, true, false, 2);
        MyTeamService.SquadPlayer row = new MyTeamService.SquadPlayer(missing, null, null);
        MyTeamService.Result.Squad squad = baseSquad(locked(null, 0, 0, missing),
                List.of(row), true, null, Optional.empty(), null, null);

        MyTeamResponse.Player player = ((MyTeamResponse.Ready) MyTeamResponse.from(squad))
                .lockedSquad().players().getFirst();

        assertThat(player.elementId()).isEqualTo(999);
        assertThat(player.name()).isEqualTo("Element 999");
        assertThat(player.position()).isEqualTo("DEF");
        assertThat(player.captain()).isTrue();
        assertThat(player.club()).isNull();
        assertThat(player.marketPrice()).isNull();
        assertThat(player.availability()).isNull();
    }

    @Test
    void gameweekPointsTravelWithTheirStatusAndPerPlayer() {
        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(ordinarySquad(true));

        assertThat(response.gameweekPoints()).isEqualTo(
                new MyTeamResponse.GameweekPoints(5, "finished_unchecked", 50, LOADED));
        assertThat(response.lockedSquad().players().get(0).gameweekPoints()).isEqualTo(8);
        assertThat(response.lockedSquad().players().get(0).gameweekMinutes()).isEqualTo(90);
        assertThat(response.lockedSquad().players().get(1).gameweekPoints())
                .as("an element the feed does not carry").isNull();
    }

    @Test
    void anUnreadFeedAndAnUnknownStatusAreNullNotZero() {
        MyTeamService.Result.Squad squad = baseSquad(locked(null, 75, 1001, salahPick()),
                players(salahPick()), true, null, Optional.of(DEADLINE), null, null);

        MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(squad);

        assertThat(response.gameweekPoints().status()).isEqualTo("unknown");
        assertThat(response.gameweekPoints().playersAsOf()).isNull();
        assertThat(response.lockedSquad().players().get(0).gameweekPoints()).isNull();
    }

    private static MyTeamService.Result.Squad ordinarySquad(boolean chipsKnown) {
        Pick starter = salahPick();
        Pick bench = new Pick(17, 12, 0, false, true, 1);
        return baseSquad(locked(null, 75, 1001, starter, bench),
                List.of(player(bench, sels()), player(starter, salah())), chipsKnown, null,
                Optional.of(DEADLINE), new FplBootstrap.EventStatus(true, false),
                new FplLivePoints.Live(5, LOADED, Map.of(302, new FplLivePoints.ElementPoints(8, 90))));
    }

    private static MyTeamService.Result.Squad baseSquad(LockedSquad locked,
                                                         List<MyTeamService.SquadPlayer> players,
                                                         boolean chipsKnown,
                                                         MyTeamService.Continuing continuing,
                                                         Optional<Instant> deadline,
                                                         FplBootstrap.EventStatus eventStatus,
                                                         FplLivePoints.Live live) {
        return new MyTeamService.Result.Squad(entry(), 5, deadline, locked, players, 6,
                List.of(), List.of(), null, chipsKnown, enrichment(), continuing, LOADED,
                eventStatus, live);
    }

    private static EntrySummary entry() {
        return new EntrySummary(123, "Deadline FC", "Private Manager", 42, 100, 5,
                List.of(new ClassicLeague(9, "Office", "x", 1, 2, 10)));
    }

    /** Available and fully dated, so the page mapping is proven to drop all of it. */
    private static SquadEnrichment.Enrichment enrichment() {
        return new SquadEnrichment.Enrichment(true,
                new SeasonContext(1, "2026-27", true, "2026-27",
                        LocalDate.of(2026, 8, 1)), Map.of(302, DEPTH),
                new SquadEnrichment.StartingGameweek(5, new DataAge.Span(DEADLINE, DEADLINE, 1, 0)));
    }

    private static final SquadEnrichment.Depth DEPTH = new SquadEnrichment.Depth(3.0,
            new BigDecimal("3.40"), new BigDecimal("1.20"),
            new DataAge.Span(DEADLINE, DEADLINE, 1, 0), new DataAge.Span(DEADLINE, LOADED, 5, 1));

    private static LockedSquad locked(String chip, int bank, int value, Pick... picks) {
        return new LockedSquad(chip, bank, value, 50, List.of(picks));
    }

    private static List<MyTeamService.SquadPlayer> players(Pick... picks) {
        return java.util.Arrays.stream(picks)
                .map(pick -> player(pick, pick.element() == 44 ? wood() : salah()))
                .toList();
    }

    private static MyTeamService.SquadPlayer player(Pick pick, FplBootstrap.Element element) {
        return new MyTeamService.SquadPlayer(pick, element, DEPTH);
    }

    private static Pick salahPick() {
        return new Pick(302, 1, 2, true, false, 3);
    }

    private static Pick woodPick() {
        return new Pick(44, 1, 1, false, false, 4);
    }

    private static FplBootstrap.Element salah() {
        return new FplBootstrap.Element(302, "Salah", 14, "LIV", 3, 126, "8.5", 12,
                "12.0", "42.1", "d", "Knock", 75);
    }

    private static FplBootstrap.Element sels() {
        return new FplBootstrap.Element(17, "Sels", 4, "NFO", 1, 50, "2.0", 2,
                "2.0", "18.3", "a", "", null);
    }

    private static FplBootstrap.Element wood() {
        return new FplBootstrap.Element(44, "Wood", 4, "NFO", 4, 71, "5.0", 18,
                "6.0", "25.0", "a", "", null);
    }
}
