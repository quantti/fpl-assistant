package dev.quantti.fplassistant.price;

import static dev.quantti.fplassistant.price.PricePredictionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.quantti.fplassistant.price.PricePredictionSnapshot.Signal;
import dev.quantti.fplassistant.price.PricePredictionSnapshot.State;

class PricePredictionParserTest {

    @Test
    void capturesPriceIdentityExplicitTargetAndSourceTimeIndependently() {
        ObjectNode root = bootstrap();
        var snapshot = parse(root);
        var player = snapshot.players().get(1);

        assertThat(snapshot.season()).isEqualTo(SEASON);
        assertThat(snapshot.fetchedAt()).isEqualTo(READ);
        assertThat(snapshot.sourceUpdatedAt()).isEqualTo(UPDATED);
        assertThat(snapshot.target()).isEqualTo(TARGET);
        assertThat(player.webName()).isEqualTo("Player 1");
        assertThat(player.fullName()).isEqualTo("Test Player 1");
        assertThat(player.currentPrice()).isEqualTo(75);
        assertThat(player.teamId()).isEqualTo(1);
        assertThat(player.position()).isEqualTo(3);
        assertThat(player.forecast().progress()).isEqualByComparingTo("94.5");
        assertThat(player.forecast().projectedProgress()).isEqualByComparingTo("104.6");
        assertThat(player.forecast().state()).isEqualTo(State.READY);
        firstPlayer(root).put("now_cost", 90);
        assertThat(player.currentPrice()).isEqualTo(75);
        assertThatThrownBy(() -> snapshot.players().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.teams().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"5,VERY_LIKELY_RISE", "4,LIKELY_RISE", "-5,VERY_LIKELY_DROP", "-4,LIKELY_DROP",
            "-3,UNLIKELY_TO_CHANGE", "-2,UNLIKELY_TO_CHANGE", "-1,UNLIKELY_TO_CHANGE",
            "0,UNLIKELY_TO_CHANGE", "1,UNLIKELY_TO_CHANGE", "2,UNLIKELY_TO_CHANGE",
            "3,UNLIKELY_TO_CHANGE"})
    void matchesEveryObservedLikelihoodCode(int code, Signal signal) {
        ObjectNode root = bootstrap();
        projection(root).put("likelihood", code);
        var forecast = parse(root).players().get(1).forecast();
        assertThat(forecast.state()).isEqualTo(State.READY);
        assertThat(forecast.likelihood()).isEqualTo(code);
        assertThat(forecast.signal()).isEqualTo(signal);
    }

    @ParameterizedTest
    @ValueSource(ints = {-6, 6, 99})
    void futureLikelihoodCodesAreNotNeutralOrDirectional(int code) {
        ObjectNode root = bootstrap();
        projection(root).put("likelihood", code);
        var forecast = parse(root).players().get(1).forecast();
        assertThat(forecast.state()).isEqualTo(State.UNSUPPORTED);
        assertThat(forecast.signal()).isNull();
        assertThat(forecast.likelihood()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"104.6", "-154.2", "0", "+99.25"})
    void progressIsSignedThresholdProgressNotABoundedProbability(String progress) {
        ObjectNode root = bootstrap();
        firstPlayer(root).put("price_change_percent", progress);
        projection(root).put("projected_percent", progress);
        var forecast = parse(root).players().get(1).forecast();
        assertThat(forecast.state()).isEqualTo(State.READY);
        assertThat(forecast.progress()).isEqualByComparingTo(progress);
    }

    @Test
    void lockPrecedesCalibrationAndLikelihoodButDoesNotHideMarketPrice() {
        ObjectNode root = bootstrap();
        firstPlayer(root).put("price_change_locked_until", TARGET.toString())
                .put("price_change_calibrating", true);
        var player = parse(root).players().get(1);
        assertThat(player.currentPrice()).isEqualTo(75);
        assertThat(player.forecast().state()).isEqualTo(State.LOCKED);
        assertThat(player.forecast().lockedUntil()).isEqualTo(TARGET);
        assertThat(player.forecast().signal()).isNull();
    }

    @Test
    void calibrationIsNotAnUnlikelyToChangeForecast() {
        ObjectNode root = bootstrap();
        firstPlayer(root).put("price_change_calibrating", true);
        var forecast = parse(root).players().get(1).forecast();
        assertThat(forecast.state()).isEqualTo(State.CALIBRATING);
        assertThat(forecast.signal()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-18T15:59:59Z", "2026-10-18T16:00:00Z", "not-a-time"})
    void retainedExpiredOrMalformedLockDoesNotBecomeUnlocked(String lock) {
        ObjectNode root = bootstrap();
        firstPlayer(root).put("price_change_locked_until", lock);
        assertThat(parse(root).players().get(1).forecast().state()).isEqualTo(State.UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 2})
    void laterOrUnknownOffsetIsNotRelabelledTonight(int offset) {
        ObjectNode root = bootstrap();
        projection(root).put("offset", offset);
        ((ArrayNode) firstPlayer(root).path("price_change_projections"))
                .add(projection(bootstrap()).deepCopy());
        var forecast = parse(root).players().get(1).forecast();
        assertThat(forecast.state()).isEqualTo(State.UNSUPPORTED);
        assertThat(forecast.signal()).isNull();
    }

    static Stream<Arguments> optionalFailures() {
        return Stream.of(
                mutation("missing projections", player -> player.remove("price_change_projections")),
                mutation("empty projections", player -> player.putArray("price_change_projections")),
                mutation("null projections", player -> player.putNull("price_change_projections")),
                mutation("missing lock", player -> player.remove("price_change_locked_until")),
                mutation("missing calibration", player -> player.remove("price_change_calibrating")),
                mutation("string calibration", player -> player.put("price_change_calibrating", "false")),
                mutation("invalid progress", player -> player.put("price_change_percent", "NaN")),
                mutation("null progress", player -> player.putNull("price_change_percent")),
                mutation("huge progress", player -> player.put("price_change_percent", "1e999999")),
                mutation("fractional code", player -> ((ObjectNode) player.path("price_change_projections")
                        .get(0)).put("likelihood", 4.5)),
                mutation("string offset", player -> ((ObjectNode) player.path("price_change_projections")
                        .get(0)).put("offset", "0")),
                mutation("invalid projection", player -> ((ObjectNode) player.path("price_change_projections")
                        .get(0)).put("projected_percent", "infinite")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionalFailures")
    void forecastFailureIsLocalAndPriceSurvives(String label, Consumer<ObjectNode> mutate) {
        ObjectNode root = bootstrap();
        mutate.accept(firstPlayer(root));
        var snapshot = parse(root);
        assertThat(snapshot.players().get(1).currentPrice()).isEqualTo(75);
        assertThat(snapshot.players().get(1).forecast().state()).isEqualTo(State.UNAVAILABLE);
        assertThat(snapshot.players().get(2).forecast().state()).isEqualTo(State.READY);
    }

    static Stream<Arguments> structuralFailures() {
        return Stream.of(
                mutation("empty players", root -> root.putArray("elements")),
                mutation("duplicate player", root -> ((ArrayNode) root.path("elements")).add(player(1))),
                mutation("unknown club", root -> firstPlayer(root).put("team", 999)),
                mutation("invalid position", root -> firstPlayer(root).put("element_type", 5)),
                mutation("missing price", root -> firstPlayer(root).remove("now_cost")),
                mutation("string price", root -> firstPlayer(root).put("now_cost", "75")),
                mutation("fractional price", root -> firstPlayer(root).put("now_cost", 75.5)),
                mutation("negative price", root -> firstPlayer(root).put("now_cost", -1)),
                mutation("missing name", root -> firstPlayer(root).put("web_name", " ")),
                mutation("duplicate club", root -> ((ObjectNode) root.path("teams").get(1)).put("id", 1)),
                mutation("missing event", root -> ((ArrayNode) root.path("events")).remove(0)),
                mutation("duplicate event", root -> ((ObjectNode) root.path("events").get(1)).put("id", 1)),
                mutation("invalid event time", root -> ((ObjectNode) root.path("events").get(0))
                        .put("deadline_time", "invalid")),
                mutation("wrong event year", root -> ((ObjectNode) root.path("events").get(37))
                        .put("deadline_time", "2028-05-01T12:00:00Z")),
                mutation("unordered event dates", root -> ((ObjectNode) root.path("events").get(1))
                        .put("deadline_time", "2026-08-20T12:00:00Z")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralFailures")
    void requiredCorruptionRejectsWholeCapture(String label, Consumer<ObjectNode> mutate) {
        ObjectNode root = bootstrap();
        mutate.accept(root);
        assertThatThrownBy(() -> parse(root)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-26", "2026-28", "2026/27", "", "2026_27"})
    void cannotAttributeReusedElementIdsToDifferentOrInvalidConfiguredSeason(String season) {
        assertThatThrownBy(() -> PricePredictionParser.parse(bytes(bootstrap()), season, READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://example.com/2026_27/",
            "https://fantasy.premierleague.com/gcs/plfpl-prod-static-content/plfpl-production/2025_26/"})
    void requiresExplicitOfficialSeasonMarker(String marker) {
        ObjectNode root = bootstrap();
        ((ObjectNode) root.path("game_config").path("settings")).put("static_content_url", marker);
        assertThatThrownBy(() -> parse(root)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "2026-10-18T16:00:01Z", ""})
    void invalidSourceTimeDoesNotInvalidateCurrentPriceOrInventSourceFreshness(String time) {
        ObjectNode root = bootstrap();
        ((ObjectNode) root.path("game_config").path("status")).put("price_change_last_updated", time);
        var snapshot = parse(root);
        assertThat(snapshot.sourceUpdatedAt()).isNull();
        assertThat(snapshot.players().get(1).currentPrice()).isEqualTo(75);
    }

    @Test
    void missingSourceTimestampRemainsUnknown() {
        ObjectNode root = bootstrap();
        ((ObjectNode) root.path("game_config")).remove("status");
        assertThat(parse(root).sourceUpdatedAt()).isNull();
    }

    @Test
    void expiredFirstTargetIsNotReplacedWithALaterDeadline() {
        ObjectNode root = bootstrap();
        ((ArrayNode) root.path("game_config").path("settings").path("price_change_deadlines"))
                .set(0, JSON.getNodeFactory().textNode(READ.minusSeconds(1).toString()));
        assertThat(parse(root).target()).isEqualTo(READ.minusSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "null", "[]", "{} {}", "{\"elements\":[],\"elements\":[]}"})
    void rejectsMalformedDuplicateTrailingOrWrongShapeDocuments(String body) {
        assertThatThrownBy(() -> PricePredictionParser.parse(bytes(body), SEASON, READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedPayloadBeforeParsing() {
        assertThatThrownBy(() -> PricePredictionParser.parse(new byte[8 * 1024 * 1024 + 1], SEASON, READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removedPlayersAreNotRetainedFromPreviousSnapshot() {
        var previous = parse(bootstrap());
        ObjectNode root = bootstrap();
        ((ArrayNode) root.path("elements")).remove(0);
        assertThat(parse(root).players()).containsOnlyKeys(2);
        assertThat(previous.players()).containsKeys(1, 2);
    }

    @Test
    void recentEventsUseTheirExactTimeAndCanNameAPreviousClub() {
        ArrayNode rows = history();
        var changes = PricePredictionParser.parseRecentChanges(bytes(rows), parse(bootstrap()), READ.plusSeconds(1));
        var change = changes.changes().getFirst();
        assertThat(changes.season()).isEqualTo(SEASON);
        assertThat(changes.fetchedAt()).isEqualTo(READ.plusSeconds(1));
        assertThat(changes.identityFetchedAt()).isEqualTo(READ);
        assertThat(change.changedAt().toString()).isEqualTo("2026-10-17T23:00:08.123456Z");
        assertThat(change.teamId()).isEqualTo(2);
        assertThat(change.priceBefore()).isEqualTo(74);
        assertThat(change.priceAfter()).isEqualTo(75);
        rows.removeAll();
        assertThat(changes.changes()).hasSize(1);
        assertThatThrownBy(() -> changes.changes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyRecentFeedIsValidButDoesNotCreateUnchangedEvents() {
        var changes = PricePredictionParser.parseRecentChanges(bytes("[]"), parse(bootstrap()), READ);
        assertThat(changes.changes()).isEmpty();
    }

    static Stream<Arguments> historyFailures() {
        return Stream.of(
                mutation("unknown element", row -> row.put("element", 999)),
                mutation("unknown team", row -> row.put("team", 999)),
                mutation("missing price", row -> row.remove("cost_after")),
                mutation("zero price", row -> row.put("cost_after", 0)),
                mutation("no actual change", row -> row.put("cost_after", 74)),
                mutation("invalid gameweek", row -> row.put("event", 39)),
                mutation("previous season", row -> row.put("changed_at", "2025-10-17T23:00:00Z")),
                mutation("future event", row -> row.put("changed_at", READ.plusSeconds(1).toString())),
                mutation("missing event timestamp", row -> row.remove("changed_at")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("historyFailures")
    void historyFailureDoesNotMutateOrInvalidateCompanionForecast(String label, Consumer<ObjectNode> mutate) {
        var snapshot = parse(bootstrap());
        ArrayNode rows = history();
        mutate.accept((ObjectNode) rows.get(0));
        assertThatThrownBy(() -> PricePredictionParser.parseRecentChanges(bytes(rows), snapshot, READ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot.players().get(1).forecast().state()).isEqualTo(State.READY);
    }

    @Test
    void duplicateHistoryEventsRejectWholeHistoryNotTheForecast() {
        ArrayNode rows = history();
        rows.add(rows.get(0).deepCopy());
        assertThatThrownBy(() -> PricePredictionParser.parseRecentChanges(bytes(rows), parse(bootstrap()), READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 601})
    void historyRequiresABoundedSequentialCompanionRead(long seconds) {
        assertThatThrownBy(() -> PricePredictionParser.parseRecentChanges(bytes(history()), parse(bootstrap()),
                READ.plusSeconds(seconds))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenMinuteCompanionBoundaryIsInclusive() {
        assertThat(PricePredictionParser.parseRecentChanges(bytes(history()), parse(bootstrap()),
                READ.plus(Duration.ofMinutes(10))).changes()).hasSize(1);
    }

    private static Arguments mutation(String label, Consumer<ObjectNode> mutate) {
        return Arguments.of(label, mutate);
    }
}
