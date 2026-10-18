package fi.karivanttinen.fplassistant.price;

import static fi.karivanttinen.fplassistant.price.PricePredictionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import fi.karivanttinen.fplassistant.price.PricePredictionEvidence.Refusal;

class PricePredictionEvidenceTest {

    @Test
    void aReadyOfficialSignalIsUsableWithoutPretendingToBeAnObservedEvent() {
        var assessment = PricePredictionEvidence.assess(parse(bootstrap()), 1, READ, true);
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.forecastUsable()).isTrue();
        assertThat(assessment.delayed()).isFalse();
        assertThat(assessment.refusal()).isEqualTo(Refusal.NONE);
    }

    @ParameterizedTest
    @CsvSource({"2700,true", "2701,false"})
    void sourceAgeCeilingIsInclusiveEvenOnANewFetch(long seconds, boolean usable) {
        ObjectNode root = bootstrap();
        sourceTime(root, READ.minusSeconds(seconds));
        var assessment = PricePredictionEvidence.assess(parse(root), 1, READ, true);
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.forecastUsable()).isEqualTo(usable);
        assertThat(assessment.refusal()).isEqualTo(usable ? Refusal.NONE : Refusal.SOURCE_AGE);
    }

    @ParameterizedTest
    @CsvSource({"2700,true", "2701,false"})
    void captureAgeCeilingIsInclusiveAndIndependentOfSourceAge(long seconds, boolean usable) {
        ObjectNode root = bootstrap();
        sourceTime(root, READ);
        var assessment = PricePredictionEvidence.assess(parse(root), 1, READ.plusSeconds(seconds), false);
        assertThat(assessment.currentPriceUsable()).isEqualTo(usable);
        assertThat(assessment.forecastUsable()).isEqualTo(usable);
        assertThat(assessment.refusal()).isEqualTo(usable ? Refusal.NONE : Refusal.CAPTURE_AGE);
    }

    @Test
    void repeatedIdenticalFetchCannotRenewSourceAge() {
        ObjectNode root = bootstrap();
        var first = parse(root);
        var later = PricePredictionParser.parse(bytes(root), SEASON, READ.plusSeconds(2400));
        assertThat(later.sourceUpdatedAt()).isEqualTo(first.sourceUpdatedAt());
        assertThat(PricePredictionEvidence.assess(later, 1, later.fetchedAt(), true).forecastUsable()).isFalse();
        assertThat(PricePredictionEvidence.assess(later, 1, later.fetchedAt(), true).currentPriceUsable()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void targetExpiresAtExactInstantRegardlessOfFetchAge(long secondsAfter) {
        ObjectNode root = bootstrap();
        sourceTime(root, TARGET.minusSeconds(10));
        var snapshot = PricePredictionParser.parse(bytes(root), SEASON, TARGET.minusSeconds(1));
        var assessment = PricePredictionEvidence.assess(snapshot, 1, TARGET.plusSeconds(secondsAfter), true);
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.forecastUsable()).isFalse();
        assertThat(assessment.refusal()).isEqualTo(Refusal.TARGET);
    }

    @Test
    void malformedTargetOrMissingSourceTimeDoesNotHideCurrentPrice() {
        ObjectNode root = bootstrap();
        ((ObjectNode) root.path("game_config")).remove("status");
        var assessment = PricePredictionEvidence.assess(parse(root), 1, READ, false);
        assertThat(assessment.forecastUsable()).isFalse();
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.refusal()).isEqualTo(Refusal.SOURCE_AGE);

        root = bootstrap();
        ((ArrayNode) root.path("game_config").path("settings").path("price_change_deadlines")).removeAll();
        assessment = PricePredictionEvidence.assess(parse(root), 1, READ, false);
        assertThat(assessment.forecastUsable()).isFalse();
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.refusal()).isEqualTo(Refusal.TARGET);
    }

    @ParameterizedTest
    @CsvSource({"1200,false", "1201,true"})
    void eveningWarningUsesSourceAgeNotOnlyReadAge(long seconds, boolean delayed) {
        ObjectNode root = bootstrap();
        sourceTime(root, READ.minusSeconds(seconds));
        var assessment = PricePredictionEvidence.assess(parse(root), 1, READ, true);
        assertThat(assessment.forecastUsable()).isTrue();
        assertThat(assessment.delayed()).isEqualTo(delayed);
        assertThat(PricePredictionEvidence.assess(parse(root), 1, READ, false).delayed()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2026-10-18T15:00:00Z,false", "2026-10-18T15:09:59Z,false",
            "2026-10-18T15:10:00Z,true", "2026-10-19T00:10:00Z,true"})
    void warningGraceOnlyCoversFirstEveningSlotNotAfterMidnight(String asOfText, boolean delayed) {
        Instant asOf = Instant.parse(asOfText);
        ObjectNode root = bootstrap();
        sourceTime(root, asOf.minusSeconds(1800));
        var snapshot = PricePredictionParser.parse(bytes(root), SEASON, asOf);
        assertThat(PricePredictionEvidence.assess(snapshot, 1, asOf, true).delayed()).isEqualTo(delayed);
    }

    @Test
    void futureCaptureAndUnknownPlayerAreNeverCurrent() {
        var snapshot = parse(bootstrap());
        assertThat(PricePredictionEvidence.assess(snapshot, 1, READ.minusSeconds(1), true).refusal())
                .isEqualTo(Refusal.CAPTURE_AGE);
        var missing = PricePredictionEvidence.assess(snapshot, 999, READ, true);
        assertThat(missing.forecastUsable()).isFalse();
        assertThat(missing.currentPriceUsable()).isFalse();
        assertThat(missing.refusal()).isEqualTo(Refusal.PLAYER_STATE);
    }

    @Test
    void calibrationDoesNotTurnIntoAnActionableForecast() {
        ObjectNode root = bootstrap();
        firstPlayer(root).put("price_change_calibrating", true);
        var assessment = PricePredictionEvidence.assess(parse(root), 1, READ, true);
        assertThat(assessment.forecastUsable()).isFalse();
        assertThat(assessment.currentPriceUsable()).isTrue();
        assertThat(assessment.refusal()).isEqualTo(Refusal.PLAYER_STATE);
    }

    @Test
    void laterTargetWithPostTargetSourceUpdateSignalsCycleRolloverOnly() {
        Instant previousTarget = READ.minusSeconds(600);
        var snapshot = parse(bootstrap());
        assertThat(PricePredictionEvidence.cycleRolledOver(previousTarget, snapshot, READ)).isTrue();
        assertThat(PricePredictionEvidence.cycleRolledOver(null, snapshot, READ)).isFalse();
        assertThat(PricePredictionEvidence.cycleRolledOver(TARGET, snapshot, READ)).isFalse();
        assertThat(PricePredictionEvidence.cycleRolledOver(previousTarget, snapshot,
                READ.plus(Duration.ofMinutes(46)))).isFalse();
    }

    @Test
    void oldOrMissingSourceTimestampDoesNotConfirmRollover() {
        Instant previousTarget = READ.minusSeconds(300);
        assertThat(PricePredictionEvidence.cycleRolledOver(previousTarget, parse(bootstrap()), READ)).isFalse();
        ObjectNode root = bootstrap();
        ((ObjectNode) root.path("game_config")).remove("status");
        assertThat(PricePredictionEvidence.cycleRolledOver(previousTarget, parse(root), READ)).isFalse();
    }

    private static void sourceTime(ObjectNode root, Instant instant) {
        ((ObjectNode) root.path("game_config").path("status"))
                .put("price_change_last_updated", instant.toString());
    }
}
