package fi.karivanttinen.fplassistant.squad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The constraint syntax the model writes and the tool promises to honour.
 *
 * <p>A bare number means <b>exactly</b>. That is the common request — "all three from Arsenal" —
 * and it is the reading that fails loudly rather than quietly when it cannot be met.
 */
class ConstraintParserTest {

    private static SquadConstraints parse(String clubQuota) {
        return ConstraintParser.parse("", "", clubQuota, 100.0, 5, "");
    }

    @Test
    void aBareNumberMeansExactly() {
        var quota = parse("Arsenal:3").clubQuotas().getFirst();

        assertThat(quota.club()).isEqualTo("Arsenal");
        assertThat(quota.comparator()).isEqualTo(SquadConstraints.Comparator.EXACTLY);
        assertThat(quota.count()).isEqualTo(3);
    }

    @Test
    void readsAllThreeComparators() {
        var quotas = parse("Arsenal:>=2, Chelsea:<=1, Spurs:=3").clubQuotas();

        assertThat(quotas).extracting(SquadConstraints.ClubQuota::comparator)
                .containsExactly(SquadConstraints.Comparator.AT_LEAST,
                        SquadConstraints.Comparator.AT_MOST,
                        SquadConstraints.Comparator.EXACTLY);
        assertThat(quotas).extracting(SquadConstraints.ClubQuota::club)
                .containsExactly("Arsenal", "Chelsea", "Spurs");
    }

    @Test
    void zeroIsAClubLevelExclusion() {
        var quota = parse("Man Utd:0").clubQuotas().getFirst();

        assertThat(quota.comparator()).isEqualTo(SquadConstraints.Comparator.EXACTLY);
        assertThat(quota.count()).isZero();
    }

    @Test
    void budgetBecomesTenthsOfAMillion() {
        assertThat(ConstraintParser.parse("", "", "", 100.0, 5, "").budget()).isEqualTo(1000);
        assertThat(ConstraintParser.parse("", "", "", 99.4, 5, "").budget()).isEqualTo(994);
    }

    @Test
    void namesAreSplitAndTrimmedAndEmptyEntriesDropped() {
        var constraints = ConstraintParser.parse(" Haaland , Gabriel ,, ", "Salah", "", 100.0, 5, "");

        assertThat(constraints.mustInclude()).containsExactly("Haaland", "Gabriel");
        assertThat(constraints.mustExclude()).containsExactly("Salah");
    }

    @Test
    void anAbsentHorizonFallsBackToTheDefault() {
        assertThat(ConstraintParser.parse("", "", "", 100.0, 0, "").horizon()).isEqualTo(5);
        assertThat(ConstraintParser.parse("", "", "", 100.0, 3, "").horizon()).isEqualTo(3);
    }

    @Test
    void aMalformedQuotaNamesTheFragmentRatherThanFailingVaguely() {
        assertThatThrownBy(() -> parse("Arsenal:three"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("Arsenal:three");

        assertThatThrownBy(() -> parse("Arsenal"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("Arsenal");
    }

    @Test
    void aNegativeQuotaIsRejected() {
        assertThatThrownBy(() -> parse("Arsenal:-1"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class);
    }

    private static SquadConstraints.Formation formation(String formation) {
        return ConstraintParser.parse("", "", "", 100.0, 5, formation).formation();
    }

    @Test
    void aWellFormedFormationParsesIntoDefMidFwd() {
        var formation = formation("3-4-3");

        assertThat(formation.def()).isEqualTo(3);
        assertThat(formation.mid()).isEqualTo(4);
        assertThat(formation.fwd()).isEqualTo(3);
    }

    @Test
    void anAbsentFormationIsNull() {
        assertThat(formation("")).isNull();
        assertThat(formation(null)).isNull();
    }

    @Test
    void aMalformedFormationNamesTheInputRatherThanFailingVaguely() {
        assertThatThrownBy(() -> formation("3-4"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("3-4");

        assertThatThrownBy(() -> formation("three-four-three"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class)
                .hasMessageContaining("three-four-three");

        assertThatThrownBy(() -> formation("3-4-3-2"))
                .isInstanceOf(ConstraintParser.MalformedConstraintException.class);
    }
}
