package dev.quantti.fplassistant.tools;

import dev.quantti.fplassistant.testsupport.TapasSeed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the resolver says when the tapas {@code season} table is in a state it cannot resolve.
 *
 * <p>It threw before this test existed too — but with {@code queryForMap}'s own words
 * ("Incorrect result size: expected 1, actual 2"), which send whoever reads the log into this
 * code, where nothing is wrong. The season table is owned by the other project; the message has
 * to point there. The exception stays a {@link org.springframework.dao.DataAccessException} so
 * the tools' existing catch keeps turning it into a sentence rather than a dead stream.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeasonResolverGuardTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SeasonResolver resolver;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    /** The fixture league's own arrangement: exactly one current season, and it is 9027. */
    @AfterEach
    void restoreTheFlags() {
        jdbc.update("UPDATE season SET is_current = (id = ?)", TapasSeed.CURRENT_SEASON_ID);
    }

    @Test
    void twoCurrentSeasonsAreNamedRatherThanCounted() {
        jdbc.update("UPDATE season SET is_current = true WHERE id IN (?, ?)",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.CURRENT_SEASON_ID);

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IncorrectResultSizeDataAccessException.class)
                .hasMessageContaining("Exactly one season must be flagged is_current")
                .hasMessageContaining(TapasSeed.POPULATED_SEASON_CODE)
                .hasMessageContaining(TapasSeed.CURRENT_SEASON_CODE)
                .hasMessageContaining("Clear the flag on all but one");
    }

    @Test
    void noCurrentSeasonSaysWhatToSetRatherThanWhatWasExpected() {
        jdbc.update("UPDATE season SET is_current = false");

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IncorrectResultSizeDataAccessException.class)
                .hasMessageContaining("found 0")
                .hasMessageContaining("Flag the season being played");
    }

    @Test
    void theOrdinaryCaseStillResolves() {
        assertThat(resolver.resolve().currentSeasonCode())
                .isEqualTo(TapasSeed.CURRENT_SEASON_CODE);
    }
}
