package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FootballDataTeamAliasesTest {

    @Test
    void resolvesKnownFootballDataNamesToTheSameCodesVaastavUses() {
        // Cross-checked against real backfilled codes from VaastavTeamMapperTest/the real
        // backfill: Arsenal=3, Wolves=39 in both alias sets, confirming they agree.
        assertThat(FootballDataTeamAliases.resolve("Arsenal FC")).isEqualTo(3);
        assertThat(FootballDataTeamAliases.resolve("Wolverhampton Wanderers FC")).isEqualTo(39);
    }

    @Test
    void resolvesAClubOnlyRecentlyPromotedOrRelegated() {
        assertThat(FootballDataTeamAliases.resolve("Sunderland AFC")).isEqualTo(56);
        assertThat(FootballDataTeamAliases.resolve("Luton Town FC")).isEqualTo(102);
    }

    @Test
    void unknownNameFailsLoudlyNamingTheName() {
        assertThatThrownBy(() -> FootballDataTeamAliases.resolve("Not A Real Club FC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not A Real Club FC");
    }
}
