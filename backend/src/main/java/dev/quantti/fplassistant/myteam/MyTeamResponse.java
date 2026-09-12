package dev.quantti.fplassistant.myteam;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.myteam.FplEntryClient.Pick;

/**
 * The purpose-built response for the read-only My team page.
 *
 * <p>It deliberately does not serialize {@link MyTeamService.Result} directly. That service
 * record also carries a manager's legal name, league memberships and statistical enrichment —
 * none of which the page renders, and none of which should cross the HTTP boundary by accident.
 */
public sealed interface MyTeamResponse {

    Map<Integer, String> POSITIONS = Map.of(1, "GK", 2, "DEF", 3, "MID", 4, "FWD");

    String status();

    /** A complete page. Money remains in FPL's integer tenths until the browser displays it. */
    record Ready(String status, Entry entry, int lockedGameweek, Instant deadline,
                 Instant loadedAt, PageSquad lockedSquad, Chips chips,
                 Continuation continuing, GameweekPoints gameweekPoints) implements MyTeamResponse {
    }

    /** A page state with no squad payload, distinguished by {@link #status()}. */
    record State(String status) implements MyTeamResponse {
    }

    record Entry(int id, String name) {
    }

    record PageSquad(int bank, int value, String activeChip, List<Player> players) {
    }

    record Player(int elementId, int pickPosition, boolean captain, boolean viceCaptain,
                  String position, String name, String club, Integer marketPrice,
                  String availability, Integer gameweekPoints, Integer gameweekMinutes) {
    }

    /**
     * @param status in_progress | finished_unchecked | final | unknown
     * @param entryTotal FPL's own total for the entry; null when it published none
     * @param playersAsOf when the per-player numbers were read; null when they were not
     */
    record GameweekPoints(int gameweek, String status, Integer entryTotal, Instant playersAsOf) {
    }

    record Chips(boolean known, int gameweek, List<String> playable,
                 List<String> openingLater, Integer openingGameweek) {
    }

    /** Whether a separate squad was restored when the locked picks were a Free Hit. */
    sealed interface Continuation {
        String status();

        record NotApplicable(String status) implements Continuation {
        }

        record Available(String status, int sourceGameweek, int bank, int value,
                         List<Player> players) implements Continuation {
        }

        record Unknown(String status, String reason) implements Continuation {
        }
    }

    static MyTeamResponse from(MyTeamService.Result result) {
        return switch (result) {
            case MyTeamService.Result.Squad squad -> ready(squad);
            case MyTeamService.Result.NoTeamId ignored -> new State("no_team_id");
            case MyTeamService.Result.TeamNotFound ignored -> new State("team_not_found");
            case MyTeamService.Result.NoSquadYet ignored -> new State("no_squad_yet");
            case MyTeamService.Result.Unavailable ignored -> new State("unavailable");
        };
    }

    private static Ready ready(MyTeamService.Result.Squad squad) {
        return new Ready("ready", new Entry(squad.entry().id(), squad.entry().teamName()),
                squad.event(), squad.deadline().orElse(null), squad.fetchedAt(),
                pageSquad(squad.locked(), squad.players(), squad.live()),
                new Chips(squad.chipsKnown(), squad.chipEvent(), squad.playableChips(),
                        squad.chipsOpeningLater(), squad.chipsOpenAt()),
                continuation(squad.continuing()), gameweekPoints(squad));
    }

    private static GameweekPoints gameweekPoints(MyTeamService.Result.Squad squad) {
        FplBootstrap.EventStatus status = squad.eventStatus();
        String code = status == null ? "unknown"
                : status.dataChecked() ? "final"
                : status.finished() ? "finished_unchecked"
                : "in_progress";
        return new GameweekPoints(squad.event(), code, squad.locked().eventPoints(),
                squad.live() == null ? null : squad.live().fetchedAt());
    }

    private static Continuation continuation(MyTeamService.Continuing continuing) {
        return switch (continuing) {
            case null -> new Continuation.NotApplicable("not_applicable");
            case MyTeamService.Continuing.Squad restored ->
                    new Continuation.Available("available", restored.event(),
                            restored.locked().bank(), restored.locked().value(),
                            players(restored.players(), null));
            case MyTeamService.Continuing.Unknown unknown ->
                    new Continuation.Unknown("unknown", unknown.reason());
        };
    }

    private static PageSquad pageSquad(FplEntryClient.LockedSquad locked,
                                       List<MyTeamService.SquadPlayer> players,
                                       FplLivePoints.Live live) {
        return new PageSquad(locked.bank(), locked.value(), locked.activeChip(),
                players(players, live));
    }

    private static List<Player> players(List<MyTeamService.SquadPlayer> players,
                                        FplLivePoints.Live live) {
        return players.stream()
                .map(player -> player(player, live))
                .sorted(Comparator.comparingInt(Player::pickPosition))
                .toList();
    }

    private static Player player(MyTeamService.SquadPlayer player, FplLivePoints.Live live) {
        Pick pick = player.pick();
        FplBootstrap.Element element = player.element();
        FplLivePoints.ElementPoints points = live == null ? null
                : live.of(pick.element()).orElse(null);
        return new Player(pick.element(), pick.position(), pick.captain(), pick.viceCaptain(),
                POSITIONS.getOrDefault(pick.elementType(), "Unknown"),
                element == null || element.webName() == null || element.webName().isBlank()
                        ? "Element " + pick.element() : element.webName(),
                element == null ? null : element.teamShort(),
                element == null ? null : element.nowCost(),
                element == null ? null : element.availability(),
                points == null ? null : points.points(),
                points == null ? null : points.minutes());
    }
}
