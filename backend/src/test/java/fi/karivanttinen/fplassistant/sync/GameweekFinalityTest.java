package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.ComponentCoverage;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.CurrentInvalidation;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.EventProgress;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.FinalReceipt;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.FixtureProgress;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.OptionalCoverageState;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.RowRevision;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.RowState;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.State;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.SweepCheckpoint;
import fi.karivanttinen.fplassistant.sync.HistoryParser.Component;

class GameweekFinalityTest {

    private static final Season SEASON = new Season(29_926, "2026-27",
            LocalDate.parse("2026-08-21"), LocalDate.parse("2027-05-23"));
    private static final Instant DEADLINE = Instant.parse("2026-08-21T17:30:00Z");
    private static final String ROW_REVISION = "f".repeat(64);
    private static final CurrentInvalidation INITIAL_INVALIDATION = new CurrentInvalidation(0, DEADLINE);

    @Test
    void eventFinishedCheckedAndFixtureProvisionalAreDistinctSignals() {
        assertThat(GameweekFinality.eventProgress(false, true)).isEqualTo(EventProgress.OPEN);
        assertThat(GameweekFinality.eventProgress(true, false)).isEqualTo(EventProgress.FINISHED);
        assertThat(GameweekFinality.eventProgress(true, true)).isEqualTo(EventProgress.CHECKED);
        assertThat(GameweekFinality.eventProgress(true, null)).isEqualTo(EventProgress.UNKNOWN);

        FixtureRow provisional = fixture(1, 1, false, true, 70, 1);
        assertThat(GameweekFinality.fixtureProgress(provisional)).isEqualTo(FixtureProgress.PROVISIONAL);
        assertThat(snapshot(true, true, true, List.of(provisional)).finalSweepEligible()).isFalse();

        FixtureRow finished = fixture(1, 1, true, true, 90, 1);
        assertThat(GameweekFinality.fixtureProgress(finished)).isEqualTo(FixtureProgress.FINISHED);
        assertThat(snapshot(true, true, true, List.of(finished)).finalSweepEligible()).isTrue();
    }

    @Test
    void checkedEventNeedsEveryAssignedFixtureFinishedAndAllowsACompleteBlankGameweek() {
        SourceSnapshot oneOutstanding = snapshot(true, true, true, List.of(
                fixture(1, 1, true, false, 90, 1), fixture(2, 1, false, false, 0, 0)));
        assertThat(oneOutstanding.eventProgress()).isEqualTo(EventProgress.CHECKED);
        assertThat(oneOutstanding.allAssignedFixturesFinished()).isFalse();
        assertThat(oneOutstanding.finalSweepEligible()).isFalse();

        SourceSnapshot blank = snapshot(true, true, true, List.of());
        assertThat(blank.assignedFixtures()).isEmpty();
        assertThat(blank.allAssignedFixturesFinished()).isTrue();
        assertThat(blank.finalSweepEligible()).isTrue();
        assertThat(GameweekFinality.observe(blank, ROW_REVISION, null, null, INITIAL_INVALIDATION))
                .isEqualTo(State.FINAL_PENDING);

        SourceSnapshot incompleteBlank = snapshot(true, true, false, List.of());
        assertThat(incompleteBlank.finalSweepEligible()).isFalse();
        assertThat(GameweekFinality.observe(incompleteBlank, ROW_REVISION, null, null, INITIAL_INVALIDATION))
                .isEqualTo(State.OPEN);
    }

    @Test
    void alreadyCheckedSourceIsLevelTriggeredAfterRestartAndReceiptMakesItIdempotent() {
        SourceSnapshot checked = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, null, null, INITIAL_INVALIDATION))
                .isEqualTo(State.FINAL_PENDING);

        FinalReceipt receipt = receipt(checked.signature());
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, null, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.FINALIZED);
        assertThat(receipt.optionalCoverage()).hasSize(Component.values().length);
        assertThat(receipt.optionalCoverage().get(Component.EXPECTED_GOALS).state())
                .isEqualTo(OptionalCoverageState.PARTIAL);
    }

    @Test
    void sourceSignatureCoversTheAssignedMembershipIndependentOfFixtureOrder() {
        FixtureRow first = fixture(1, 1, true, false, 90, 1);
        FixtureRow second = fixture(2, 1, true, false, 90, 2);
        SourceSnapshot ordered = snapshot(true, true, true, List.of(first, second));
        SourceSnapshot reversed = snapshot(true, true, true, List.of(second, first));
        SourceSnapshot missingMember = snapshot(true, true, true, List.of(first));

        assertThat(ordered.signature()).isEqualTo(reversed.signature());
        assertThat(ordered.signature()).isNotEqualTo(missingMember.signature());
    }

    @Test
    void changedCurrentRowsReopenFinalityWithoutChangingTheScheduleSignature() {
        SourceSnapshot source = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        RowRevision valid = new RowRevision(SEASON.id(), SEASON.code(), 1, 10,
                RowState.VALID, "a".repeat(64));
        RowRevision withdrawn = new RowRevision(SEASON.id(), SEASON.code(), 1, 11,
                RowState.WITHDRAWN, null);
        String coveredRows = GameweekFinality.rowRevisionFingerprint(source, List.of(valid, withdrawn));
        assertThat(GameweekFinality.rowRevisionFingerprint(source, List.of(withdrawn, valid)))
                .isEqualTo(coveredRows);

        FinalReceipt receipt = receipt(source.signature(), coveredRows);
        assertThat(GameweekFinality.observe(source, coveredRows, State.FINALIZED, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.FINALIZED);
        String correctedRows = GameweekFinality.rowRevisionFingerprint(source, List.of(
                new RowRevision(SEASON.id(), SEASON.code(), 1, 10, RowState.VALID, "b".repeat(64)), withdrawn));
        assertThat(correctedRows).isNotEqualTo(coveredRows);
        assertThat(GameweekFinality.observe(source, correctedRows, State.FINALIZED, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.FINAL_PENDING);
    }

    @Test
    void aCheckedFlagRegressionCannotResurrectAnOldReceiptAfterTheFlagReturns() {
        SourceSnapshot checked = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        FinalReceipt oldReceipt = receipt(checked.signature());
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, State.FINAL_PENDING, oldReceipt,
                INITIAL_INVALIDATION)).isEqualTo(State.FINALIZED);

        CurrentInvalidation invalidated = INITIAL_INVALIDATION.advance(DEADLINE.plusSeconds(60));
        SourceSnapshot regressed = snapshot(false, true, true, checked.assignedFixtures());
        assertThat(GameweekFinality.observe(regressed, ROW_REVISION, State.FINALIZED, oldReceipt, invalidated))
                .isEqualTo(State.OPEN);
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, State.OPEN, oldReceipt, invalidated))
                .isEqualTo(State.FINAL_PENDING);
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, State.FINAL_PENDING, oldReceipt, invalidated))
                .isEqualTo(State.FINAL_PENDING);
        assertThat(oldReceipt.covers(checked.signature(), ROW_REVISION, invalidated)).isFalse();

        Instant freshReadsFloor = invalidated.floor().plusSeconds(1);
        FinalReceipt freshReceipt = receipt(checked.signature(), ROW_REVISION, coverage(), invalidated,
                freshReadsFloor);
        assertThat(freshReceipt.sourceFloor()).isAfter(invalidated.floor());
        assertThat(GameweekFinality.observe(checked, ROW_REVISION, State.FINAL_PENDING,
                freshReceipt, invalidated)).isEqualTo(State.FINALIZED);
    }

    @Test
    void historyOnlyCorrectionAdvancesFenceAndRequiresFreshReadsWhileCheckedRemainsTrue() {
        SourceSnapshot checked = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        String originalRows = "a".repeat(64);
        FinalReceipt oldReceipt = receipt(checked.signature(), originalRows);
        CurrentInvalidation invalidated = INITIAL_INVALIDATION.advance(DEADLINE.plusSeconds(120));
        String correctedRows = "b".repeat(64);

        assertThat(checked.signature()).isEqualTo(oldReceipt.sourceSignature());
        assertThat(GameweekFinality.observe(checked, correctedRows, State.FINALIZED, oldReceipt, invalidated))
                .isEqualTo(State.FINAL_PENDING);
        SweepCheckpoint stale = new SweepCheckpoint(checked.signature(), DEADLINE, 0,
                Set.of(10), Set.of(), Set.of(10));
        assertThat(checked.canResume(stale, invalidated)).isFalse();
        Instant freshReadsFloor = invalidated.floor().plusSeconds(1);
        SweepCheckpoint fresh = new SweepCheckpoint(checked.signature(), freshReadsFloor,
                invalidated.revision(), Set.of(10), Set.of(10), Set.of());
        assertThat(fresh.sourceFloor()).isAfter(invalidated.floor());
        assertThat(checked.canResume(fresh, invalidated)).isTrue();

        // Even if the row later returns to the old fingerprint, its receipt is still from the old revision.
        assertThat(GameweekFinality.observe(checked, originalRows, State.FINAL_PENDING, oldReceipt, invalidated))
                .isEqualTo(State.FINAL_PENDING);
        FinalReceipt freshReceipt = receipt(checked.signature(), correctedRows, coverage(), invalidated,
                freshReadsFloor);
        assertThat(freshReceipt.sourceFloor()).isAfter(invalidated.floor());
        assertThat(GameweekFinality.observe(checked, correctedRows, State.FINAL_PENDING,
                freshReceipt, invalidated)).isEqualTo(State.FINALIZED);
    }

    @Test
    void sourceRevisionAndValidFlagRegressionReopenFinality() {
        SourceSnapshot original = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        FinalReceipt receipt = receipt(original.signature());
        assertThat(GameweekFinality.observe(original, ROW_REVISION, State.FINALIZED, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.FINALIZED);

        SourceSnapshot revised = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 2)));
        assertThat(revised.signature()).isNotEqualTo(original.signature());
        assertThat(GameweekFinality.observe(revised, ROW_REVISION, State.FINALIZED, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.FINAL_PENDING);

        SourceSnapshot regressed = snapshot(false, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        assertThat(GameweekFinality.observe(regressed, ROW_REVISION, State.FINALIZED, receipt, INITIAL_INVALIDATION))
                .isEqualTo(State.OPEN);
        SourceSnapshot unfinishedFixture = snapshot(true, true, true,
                List.of(fixture(1, 1, false, true, 70, 1)));
        assertThat(GameweekFinality.observe(unfinishedFixture, ROW_REVISION, State.FINALIZED, receipt,
                INITIAL_INVALIDATION)).isEqualTo(State.OPEN);
    }

    @Test
    void missingCheckedSignalOrMalformedMembershipCannotFinalizeOrErasePriorReceipt() {
        SourceSnapshot missingChecked = new SourceSnapshot(SEASON.id(), SEASON.code(), 1,
                true, null, true, List.of(fixture(1, 1, true, false, 90, 1)));
        assertThat(missingChecked.eventProgress()).isEqualTo(EventProgress.UNKNOWN);
        assertThat(missingChecked.finalSweepEligible()).isFalse();
        assertThat(GameweekFinality.observe(missingChecked, ROW_REVISION, null, null, INITIAL_INVALIDATION))
                .isEqualTo(State.OPEN);

        SourceSnapshot checked = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        assertThat(GameweekFinality.observe(missingChecked, ROW_REVISION, State.FINALIZED,
                receipt(checked.signature()), INITIAL_INVALIDATION)).isEqualTo(State.FINALIZED);
    }

    @Test
    void checkpointResumesOnlyForTheExactEligibleSourceRevision() {
        SourceSnapshot source = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 1)));
        SweepCheckpoint checkpoint = new SweepCheckpoint(source.signature(), DEADLINE, 0,
                Set.of(10, 11), Set.of(10), Set.of(11));
        assertThat(source.canResume(checkpoint, INITIAL_INVALIDATION)).isTrue();

        SourceSnapshot revision = snapshot(true, true, true, List.of(fixture(1, 1, true, false, 90, 2)));
        assertThat(revision.canResume(checkpoint, INITIAL_INVALIDATION)).isFalse();
        assertThat(snapshot(true, true, false, List.of(fixture(1, 1, true, false, 90, 1)))
                .canResume(checkpoint, INITIAL_INVALIDATION)).isFalse();
        assertThatThrownBy(() -> new SweepCheckpoint(source.signature(), DEADLINE, 0,
                Set.of(10, 11), Set.of(10), Set.of(10, 11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both completed and outstanding");
    }

    @Test
    void finalReceiptRequiresMandatoryManifestAndExplicitCoverageForEveryOptionalComponent() {
        Map<Component, ComponentCoverage> complete = coverage();
        FinalReceipt receipt = receipt("a".repeat(64), ROW_REVISION, complete);
        assertThat(receipt.mandatoryManifestComplete()).isTrue();
        assertThat(receipt.optionalCoverage().get(Component.EXPECTED_GOALS).state())
                .isEqualTo(OptionalCoverageState.PARTIAL);
        assertThat(receipt.optionalCoverage().get(Component.DEFENSIVE_CONTRIBUTION).state())
                .isEqualTo(OptionalCoverageState.NOT_SUPPORTED);

        EnumMap<Component, ComponentCoverage> missing = new EnumMap<>(complete);
        missing.remove(Component.EXPECTED_GOALS);
        assertThatThrownBy(() -> receipt("a".repeat(64), ROW_REVISION, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every optional component");
        EnumMap<Component, ComponentCoverage> nullValue = new EnumMap<>(complete);
        nullValue.put(Component.EXPECTED_GOALS, null);
        assertThatThrownBy(() -> receipt("a".repeat(64), ROW_REVISION, nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot contain null");
        assertThatThrownBy(() -> new FinalReceipt("a".repeat(64), ROW_REVISION, 1, DEADLINE, 0, DEADLINE,
                false, complete)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete mandatory manifest");
        assertThatThrownBy(() -> new FinalReceipt("a".repeat(64), ROW_REVISION, 1,
                DEADLINE.plusSeconds(1), 0, DEADLINE, true, complete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precedes its source floor");
    }

    private static SourceSnapshot snapshot(boolean finished, Boolean dataChecked,
                                           boolean fixtureManifestComplete, List<FixtureRow> fixtures) {
        GameweekRow event = new GameweekRow(SEASON.id(), SEASON.code(), 1, "Gameweek 1", DEADLINE,
                finished, Boolean.TRUE.equals(dataChecked), false, false, Map.of());
        if (dataChecked == null) {
            return new SourceSnapshot(SEASON.id(), SEASON.code(), 1, finished, null,
                    fixtureManifestComplete, fixtures);
        }
        return SourceSnapshot.from(event, fixtures, fixtureManifestComplete);
    }

    private static FixtureRow fixture(int id, int gameweek, boolean finished,
                                      boolean provisional, int minutes, int statValue) {
        return new FixtureRow(SEASON.id(), SEASON.code(), id, id, gameweek, 1, 2,
                "Home", "HOM", "Away", "AWY", finished ? 1 : null, finished ? 0 : null,
                3, 2, Instant.parse("2026-08-22T14:00:00Z"), true, finished,
                Map.of("finished_provisional", provisional, "minutes", minutes,
                        "stats", "[{\"identifier\":\"minutes\",\"h\":[{\"element\":10,\"value\":"
                                + statValue + "}],\"a\":[]}]"));
    }

    private static FinalReceipt receipt(String signature) {
        return receipt(signature, ROW_REVISION, coverage());
    }

    private static FinalReceipt receipt(String signature, String rowRevision) {
        return receipt(signature, rowRevision, coverage());
    }

    private static FinalReceipt receipt(String signature, String rowRevision,
                                        Map<Component, ComponentCoverage> coverage) {
        return receipt(signature, rowRevision, coverage, INITIAL_INVALIDATION);
    }

    private static FinalReceipt receipt(String signature, String rowRevision,
                                        Map<Component, ComponentCoverage> coverage,
                                        CurrentInvalidation invalidation) {
        return receipt(signature, rowRevision, coverage, invalidation, invalidation.floor());
    }

    private static FinalReceipt receipt(String signature, String rowRevision,
                                        Map<Component, ComponentCoverage> coverage,
                                        CurrentInvalidation invalidation, Instant sourceFloor) {
        return new FinalReceipt(signature, rowRevision, 1, sourceFloor, invalidation.revision(),
                sourceFloor, true, coverage);
    }

    private static Map<Component, ComponentCoverage> coverage() {
        EnumMap<Component, ComponentCoverage> coverage = new EnumMap<>(Component.class);
        for (Component component : Component.values()) coverage.put(component,
                component == Component.EXPECTED_GOALS
                        ? new ComponentCoverage(2, 1, true)
                        : component == Component.DEFENSIVE_CONTRIBUTION
                                ? new ComponentCoverage(2, 0, false)
                                : new ComponentCoverage(0, 0, true));
        return coverage;
    }
}
