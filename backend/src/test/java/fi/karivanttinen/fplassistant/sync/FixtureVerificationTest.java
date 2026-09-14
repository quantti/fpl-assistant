package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.Decision;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.Reason;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.State;
import fi.karivanttinen.fplassistant.sync.HistoryParser.CanonicalFixture;
import fi.karivanttinen.fplassistant.sync.HistoryParser.HistoryBatch;

class FixtureVerificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Season SEASON = new Season(27, "2026-27",
            LocalDate.parse("2026-08-21"), LocalDate.parse("2027-05-23"));
    private static final Instant BASE = Instant.parse("2026-10-20T22:00:00Z");
    private static final String MANIFEST = "[{\"identifier\":\"minutes\",\"h\":[{\"element\":1,\"value\":90}],\"a\":[{\"element\":2,\"value\":90}]}]";

    @Test
    void twoUnchangedIndependentObservationsVerifyAtExactTenMinuteBoundary() throws Exception {
        Inputs first = inputs(BASE, true, live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        Decision initial = decide(first, null, BASE.plusSeconds(6));
        Inputs second = inputs(BASE.plusSeconds(605), true, first.live.bytes(),
                histories(BASE.plusSeconds(605), history(1, true, 90, 2, true),
                        history(2, false, 90, 3, true)), MANIFEST);
        Decision verified = decide(second, initial.observation(), BASE.plusSeconds(611));

        assertThat(initial.state()).isEqualTo(State.PENDING);
        assertThat(initial.reasons()).containsExactly(Reason.NEEDS_SECOND_OBSERVATION);
        assertThat(verified.state()).isEqualTo(State.VERIFIED);
        assertThat(verified.reasons()).isEmpty();
        assertThat(verified.observation().fingerprint()).isEqualTo(initial.observation().fingerprint());
        assertThat(verified.observation().reads().histories()).hasSize(2);
    }

    @Test
    void emptyFixtureStatsManifestCannotVerify() throws Exception {
        Inputs first = inputs(BASE, true, live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), "[]");
        Decision initial = decide(first, null, BASE.plusSeconds(6));
        Inputs second = inputs(BASE.plusSeconds(600), true, first.live.bytes(),
                histories(BASE.plusSeconds(600), history(1, true, 90, 2, true),
                        history(2, false, 90, 3, true)), "[]");

        assertThat(decide(second, initial.observation(), BASE.plusSeconds(606)).reasons())
                .contains(Reason.MISSING_OR_INCOMPLETE_FIXTURE_MANIFEST);
    }

    @Test
    void sourceTimesAreRetainedButExcludedFromFingerprintAndNineMinutesIsTooSoon() throws Exception {
        Inputs first = normal(BASE);
        Decision initial = decide(first, null, BASE.plusSeconds(6));
        Inputs later = normal(BASE.plusSeconds(599));
        Decision current = decide(later, initial.observation(), BASE.plusSeconds(605));

        assertThat(current.observation().fingerprint()).isEqualTo(initial.observation().fingerprint());
        assertThat(current.observation().reads().completedAt()).isAfter(initial.observation().reads().completedAt());
        assertThat(current.reasons()).containsExactly(Reason.STABILITY_WINDOW);
    }

    @Test
    void slowHistorySweepCannotStretchIntoTheStabilityWindow() throws Exception {
        Decision initial = decide(normal(BASE), null, BASE.plusSeconds(6));
        Inputs parsed = normal(BASE.plusSeconds(300));
        List<HistoryBatch> slowHistories = parsed.histories.stream()
                .map(batch -> new HistoryBatch(batch.seasonId(), batch.seasonCode(), batch.playerId(),
                        new ReadInterval(batch.sourceRead().startedAt(), BASE.plusSeconds(705)),
                        batch.rows()))
                .toList();
        Inputs slow = new Inputs(parsed.fixture, parsed.fixtureRead, parsed.live, slowHistories);

        Decision decision = decide(slow, initial.observation(), BASE.plusSeconds(706));

        assertThat(decision.observation().reads().completedAt())
                .isAfter(initial.observation().reads().completedAt().plusSeconds(600));
        assertThat(decision.reasons()).containsExactly(Reason.STABILITY_WINDOW);
    }

    @Test
    void historyPointsAreAuthoritativeAndRawModificationOnlyChangesFingerprint() throws Exception {
        Inputs first = normal(BASE);
        Decision initial = decide(first, null, BASE.plusSeconds(6));
        // No points + points_modification arithmetic is performed; raw triples are stability evidence only.
        byte[] changedLive = live(player(1, 90, 99, -97), player(2, 90, 3, 0));
        Inputs correction = inputs(BASE.plusSeconds(700), true, changedLive,
                histories(BASE.plusSeconds(700), history(1, true, 90, 2, true),
                        history(2, false, 90, 3, true)), MANIFEST);

        Decision changed = decide(correction, initial.observation(), BASE.plusSeconds(706));
        assertThat(changed.reasons()).containsExactly(Reason.CHANGED_FINGERPRINT);
    }

    @Test
    void equivalentDecimalRepresentationsKeepTheSameFingerprint() throws Exception {
        Decision initial = decide(normal(BASE), null, BASE.plusSeconds(6));
        ObjectNode home = history(1, true, 90, 2, true);
        home.put("expected_goals", "0.0");
        Inputs equivalent = inputs(BASE.plusSeconds(605), true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE.plusSeconds(605), home, history(2, false, 90, 3, true)), MANIFEST);

        Decision result = decide(equivalent, initial.observation(), BASE.plusSeconds(611));
        assertThat(result.state()).isEqualTo(State.VERIFIED);
        assertThat(result.observation().fingerprint()).isEqualTo(initial.observation().fingerprint());
    }

    @Test
    void historyPointCorrectionAndOptionalPresenceChangeBothReopenStability() throws Exception {
        Decision initial = decide(normal(BASE), null, BASE.plusSeconds(6));
        Inputs points = inputs(BASE.plusSeconds(700), true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE.plusSeconds(700), history(1, true, 90, 7, true),
                        history(2, false, 90, 3, true)), MANIFEST);
        assertThat(decide(points, initial.observation(), BASE.plusSeconds(706)).reasons())
                .containsExactly(Reason.CHANGED_FINGERPRINT);

        Inputs mask = inputs(BASE.plusSeconds(700), true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE.plusSeconds(700), history(1, true, 90, 2, false),
                        history(2, false, 90, 3, true)), MANIFEST);
        assertThat(decide(mask, initial.observation(), BASE.plusSeconds(706)).reasons())
                .containsExactly(Reason.CHANGED_FINGERPRINT);
    }

    @Test
    void statsBeforeFinishAndFinishBeforeCompleteStatsEachNeedTwoPostFinishObservations() throws Exception {
        Inputs notFinished = inputs(BASE, false,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        Decision stagedStats = decide(notFinished, null, BASE.plusSeconds(6));
        assertThat(stagedStats.reasons()).contains(Reason.FIXTURE_NOT_FINISHED);
        Decision firstFinished = decide(normal(BASE.plusSeconds(700)), stagedStats.observation(), BASE.plusSeconds(706));
        assertThat(firstFinished.reasons()).containsExactly(Reason.NEEDS_SECOND_OBSERVATION);

        Inputs partial = inputs(BASE, true, live(player(1, 90, 2, 0)),
                histories(BASE, history(1, true, 90, 2, true)), MANIFEST);
        Decision finishedFirst = decide(partial, null, BASE.plusSeconds(6));
        assertThat(finishedFirst.reasons()).contains(Reason.BOTH_SIDES_NOT_EVIDENCED);
        Decision complete = decide(normal(BASE.plusSeconds(700)), finishedFirst.observation(), BASE.plusSeconds(706));
        assertThat(complete.reasons()).containsExactly(Reason.NEEDS_SECOND_OBSERVATION);
    }

    @Test
    void incompleteSideAndSymmetricPositiveMinuteCoverageStayPending() throws Exception {
        Inputs missingHistory = inputs(BASE, true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true)), MANIFEST);
        Decision missingHistoryDecision = decide(missingHistory, null, BASE.plusSeconds(6));
        assertThat(missingHistoryDecision.reasons())
                .contains(Reason.MISSING_HISTORY, Reason.BOTH_SIDES_NOT_EVIDENCED);
        assertThat(missingHistoryDecision.missingParticipantIds()).containsExactly(2);

        Inputs missingLive = inputs(BASE, true, live(player(1, 90, 2, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        assertThat(decide(missingLive, null, BASE.plusSeconds(6)).reasons())
                .contains(Reason.MISSING_LIVE_PLAYER, Reason.BOTH_SIDES_NOT_EVIDENCED);
    }

    @Test
    void zeroMinuteBenchExplanationIsNotAnAppearedPlayerButRemainsFingerprintEvidence() throws Exception {
        Inputs withBench = inputs(BASE, true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0), player(9, 0, -1, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        Decision first = decide(withBench, null, BASE.plusSeconds(6));
        assertThat(first.reasons()).containsExactly(Reason.NEEDS_SECOND_OBSERVATION);

        Inputs correctedBench = inputs(BASE.plusSeconds(700), true,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0), player(9, 0, -2, 0)),
                histories(BASE.plusSeconds(700), history(1, true, 90, 2, true),
                        history(2, false, 90, 3, true)), MANIFEST);
        assertThat(decide(correctedBench, first.observation(), BASE.plusSeconds(706)).reasons())
                .containsExactly(Reason.CHANGED_FINGERPRINT);
    }

    @Test
    void fixtureStatParticipantsMustExistInLiveAndHistoryOnTheRecordedSide() throws Exception {
        String participant = "[{\"identifier\":\"goals_scored\",\"h\":[{\"element\":1,\"value\":1}],"
                + "\"a\":[{\"element\":2,\"value\":0}]}]";
        Inputs valid = inputs(BASE, true, live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), participant);
        assertThat(decide(valid, null, BASE.plusSeconds(6)).reasons())
                .containsExactly(Reason.NEEDS_SECOND_OBSERVATION);

        String wrongSide = "[{\"identifier\":\"goals_scored\",\"h\":[{\"element\":2,\"value\":0}],"
                + "\"a\":[{\"element\":1,\"value\":1}]}]";
        Inputs invalid = inputs(BASE, true, valid.live.bytes(), valid.histories, wrongSide);
        assertThat(decide(invalid, null, BASE.plusSeconds(6)).reasons())
                .contains(Reason.FIXTURE_STAT_SIDE_MISMATCH);
    }

    @Test
    void dgwUsesOnlyFixtureScopedExplainAndNotTopLevelEventAggregate() throws Exception {
        byte[] dgw = ("{\"elements\":["
                + element(1, "[{\"fixture\":101,\"stats\":[" + stat("minutes", 90, 2, 0)
                        + "]},{\"fixture\":109,\"stats\":[" + stat("minutes", 1, 1, 0) + "]}]") + ","
                + element(2, "[{\"fixture\":101,\"stats\":[" + stat("minutes", 90, 2, 0) + "]}]")
                + "]}").getBytes(StandardCharsets.UTF_8);
        Inputs inputs = inputs(BASE, true, dgw,
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        assertThat(decide(inputs, null, BASE.plusSeconds(6)).reasons())
                .containsExactly(Reason.NEEDS_SECOND_OBSERVATION);
    }

    @Test
    void provisionalFinishNeverSubstitutesForFinishedAndRegressionRevokes() throws Exception {
        Inputs provisional = inputs(BASE, false,
                live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(BASE, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
        Decision pending = decide(provisional, null, BASE.plusSeconds(6));
        assertThat(pending.reasons()).contains(Reason.FIXTURE_NOT_FINISHED);

        Decision finished = decide(normal(BASE), null, BASE.plusSeconds(6));
        Decision regression = decide(provisionalAt(BASE.plusSeconds(700)), finished.observation(), BASE.plusSeconds(706));
        assertThat(regression.reasons()).contains(Reason.FINISHED_REGRESSION, Reason.FIXTURE_NOT_FINISHED);
    }

    @Test
    void evidenceReadBeforeFinishedFixtureObservationStaysPending() throws Exception {
        Inputs normal = normal(BASE);
        SourceDocument earlyLive = new SourceDocument(normal.live.bytes(), interval(BASE));
        Decision result = FixtureVerification.verify(SEASON, normal.fixture, normal.fixtureRead,
                earlyLive, normal.histories, null, BASE.plusSeconds(6));
        assertThat(result.reasons()).contains(Reason.EVIDENCE_NOT_POST_FINISH);
    }

    @Test
    void overlappingOrFutureReadsCannotVerify() throws Exception {
        Decision first = decide(normal(BASE), null, BASE.plusSeconds(6));
        Inputs overlap = normal(BASE.plusSeconds(1));
        assertThat(decide(overlap, first.observation(), BASE.plusSeconds(20)).reasons())
                .contains(Reason.SOURCE_READ_ORDER);

        Inputs future = normal(BASE.plusSeconds(700));
        assertThat(decide(future, first.observation(), BASE.plusSeconds(699)).reasons())
                .contains(Reason.SOURCE_FROM_FUTURE);
    }

    @Test
    void duplicateHistoryAndOpponentContradictionAreRejectedWithoutInventedRows() throws Exception {
        HistoryBatch one = histories(BASE, history(1, true, 90, 2, true)).getFirst();
        Inputs duplicate = inputs(BASE, true, live(player(1, 90, 2, 0)), List.of(one, one), MANIFEST);
        assertThat(decide(duplicate, null, BASE.plusSeconds(6)).reasons())
                .contains(Reason.DUPLICATE_HISTORY);

        ObjectNode bad = history(1, true, 90, 2, true);
        bad.put("opponent_team", 999);
        assertThatThrownBy(() -> histories(BASE, bad)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opponent contradicts configured-season fixture");
    }

    @Test
    void participantDiscoveryKeepsZeroMinuteAndZeroStatDepartedIds() throws Exception {
        String stats = "[{\"identifier\":\"minutes\",\"h\":[{\"element\":1,\"value\":90}],"
                + "\"a\":[{\"element\":658,\"value\":0}]}]";
        Inputs input = inputs(BASE, true, live(player(1, 90, 2, 0), player(557, 0, 0, 0)),
                List.of(), stats);

        assertThat(FixtureVerification.participantIds(input.fixture, input.live))
                .containsExactlyInAnyOrder(1, 557, 658);
    }

    @Test
    void capturedLiveAndCatalogueFixtureStayPendingWhenMostAppearedHistoriesAreAbsent() throws Exception {
        byte[] bootstrap = resource("bootstrap-static.json");
        byte[] fixtures = resource("fixtures.json");
        ReadInterval read = interval(Instant.parse("2026-09-13T17:45:06Z"));
        var snapshot = CatalogueScheduleParser.parse(SEASON, new SourceDocument(bootstrap, read),
                new SourceDocument(fixtures, read));
        FixtureRow fixture = snapshot.fixtures().stream().filter(row -> row.id() == 29).findFirst().orElseThrow();
        List<CanonicalFixture> canonical = snapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        List<HistoryBatch> histories = new ArrayList<>();
        for (int player : List.of(1, 2, 557)) {
            histories.add(HistoryParser.parse(SEASON, player,
                    new SourceDocument(resource("element-summary-" + player + ".json"), read), canonical));
        }
        SourceDocument live = new SourceDocument(resource("event-3-live.json"), read);

        Decision result = FixtureVerification.verify(SEASON, fixture, read, live, histories, null,
                Instant.parse("2026-09-13T18:00:00Z"));
        assertThat(result.state()).isEqualTo(State.PENDING);
        assertThat(result.reasons()).contains(Reason.MISSING_HISTORY,
                Reason.FIXTURE_STAT_PARTICIPANT_MISSING);
        assertThat(result.observation().fingerprint()).hasSize(64);
    }

    private static Inputs normal(Instant start) throws Exception {
        return inputs(start, true, live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(start, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
    }

    private static Inputs provisionalAt(Instant start) throws Exception {
        return inputs(start, false, live(player(1, 90, 2, 0), player(2, 90, 3, 0)),
                histories(start, history(1, true, 90, 2, true), history(2, false, 90, 3, true)), MANIFEST);
    }

    private static Inputs inputs(Instant start, boolean finished, byte[] live,
                                 List<HistoryBatch> histories, String fixtureStats) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("finished_provisional", true);
        fields.put("minutes", 90);
        fields.put("stats", fixtureStats);
        FixtureRow fixture = new FixtureRow(27, "2026-27", 101, 1001, 8, 10, 20,
                "Home", "HOM", "Away", "AWY", finished ? 2 : 1, finished ? 1 : 0,
                3, 3, Instant.parse("2026-10-20T19:00:00Z"), true, finished, fields);
        return new Inputs(fixture, interval(start),
                new SourceDocument(live, interval(start.plusSeconds(2))), histories);
    }

    private static Decision decide(Inputs input, FixtureVerification.Observation prior, Instant asOf) {
        return FixtureVerification.verify(SEASON, input.fixture, input.fixtureRead, input.live,
                input.histories, prior, asOf);
    }

    private static List<HistoryBatch> histories(Instant start, ObjectNode... rows) throws Exception {
        List<HistoryBatch> result = new ArrayList<>();
        CanonicalFixture fixture = new CanonicalFixture(27, "2026-27", 101, 8, 10, 20,
                Instant.parse("2026-10-20T19:00:00Z"));
        for (ObjectNode row : rows) {
            int player = row.get("element").intValue();
            ObjectNode root = JSON.createObjectNode();
            root.set("history", JSON.createArrayNode().add(row));
            result.add(HistoryParser.parse(SEASON, player,
                    new SourceDocument(JSON.writeValueAsBytes(root), interval(start.plusSeconds(4))),
                    List.of(fixture)));
        }
        return result;
    }

    private static ObjectNode history(int player, boolean home, int minutes, int points,
                                      boolean optionalPresent) {
        ObjectNode row = JSON.createObjectNode();
        row.put("element", player).put("fixture", 101).put("round", 8)
                .put("opponent_team", home ? 20 : 10).put("was_home", home)
                .put("kickoff_time", "2026-10-20T19:00:00Z").put("minutes", minutes)
                .put("total_points", points).put("bonus", 0).put("bps", 1)
                .put("goals_scored", 0).put("assists", 0).put("clean_sheets", 0)
                .put("goals_conceded", 1).put("own_goals", 0).put("penalties_saved", 0)
                .put("penalties_missed", 0).put("saves", 0).put("yellow_cards", 0)
                .put("red_cards", 0).put("starts", minutes > 0 ? 1 : 0).put("value", 50)
                .put("selected", 1).put("transfers_in", 0).put("transfers_out", 0);
        if (optionalPresent) {
            row.put("expected_goals", "0.00").put("expected_assists", "0.00")
                    .put("expected_goal_involvements", "0.00").put("expected_goals_conceded", "1.00")
                    .put("influence", "0.0").put("creativity", "0.0").put("threat", "0.0")
                    .put("ict_index", "0.0").put("clearances_blocks_interceptions", 0)
                    .put("recoveries", 0).put("tackles", 0).put("defensive_contribution", 0);
        }
        return row;
    }

    private static byte[] live(Player... players) {
        StringBuilder json = new StringBuilder("{\"elements\":[");
        for (int i = 0; i < players.length; i++) {
            if (i > 0) json.append(',');
            Player player = players[i];
            json.append("{\"id\":").append(player.id)
                    .append(",\"stats\":{\"total_points\":999},\"explain\":[{\"fixture\":101,\"stats\":[")
                    .append(stat("minutes", player.minutes, player.points, player.modification))
                    .append("]}]}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String element(int player, String explain) {
        return "{\"id\":" + player + ",\"stats\":{\"total_points\":999},\"explain\":" + explain + "}";
    }

    private static String stat(String identifier, int value, int points, int modification) {
        return "{\"identifier\":\"" + identifier + "\",\"value\":" + value
                + ",\"points\":" + points + ",\"points_modification\":" + modification + "}";
    }

    private static ReadInterval interval(Instant start) {
        return new ReadInterval(start, start.plusSeconds(1));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = FixtureVerificationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IOException("Missing resource " + name);
            return input.readAllBytes();
        }
    }

    private record Player(int id, int minutes, int points, int modification) { }
    private static Player player(int id, int minutes, int points, int modification) {
        return new Player(id, minutes, points, modification);
    }
    private record Inputs(FixtureRow fixture, ReadInterval fixtureRead, SourceDocument live,
                          List<HistoryBatch> histories) { }
}
